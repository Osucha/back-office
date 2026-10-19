package pl.mos.backoffice.llm.gateway

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import pl.mos.backoffice.llm.config.LlmProperties
import pl.mos.backoffice.llm.ratelimit.CallPriority
import pl.mos.backoffice.llm.ratelimit.ProviderRateLimiter
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.FinishReason
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmMessage
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Role
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageRecord
import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ThreadLocalRandom
import pl.mos.backoffice.llm.connections.ConnectionQuota

/**
 * `LlmGateway` on Spring AI (ADR 0003). One lazily built `ChatModel` per configured provider; every call
 * carries provider-specific options mapped by [ChatOptionsMapper], is bounded by a per-provider semaphore
 * and a wall-clock timeout, retried on 429/5xx/connection errors with jittered backoff, and booked in the
 * [UsageLedger] with cost from the [ModelCatalog] pricing (ADR 0008).
 *
 * Structured output: the JSON schema is appended to the system prompt and, where the provider supports it,
 * enforced natively. The answer is parsed to a map (fences stripped); one corrective round-trip is made
 * when parsing fails.
 */
class SpringAiLlmGateway(
    private val registry: ProviderRegistry,
    private val factory: ChatModelFactory,
    private val optionsMapper: ChatOptionsMapper,
    private val parser: StructuredOutputParser,
    private val catalog: ModelCatalog,
    private val ledger: UsageLedger,
    private val clock: Clock,
    private val properties: LlmProperties,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    /** Per-provider requests/tokens per minute (ADR 0020); unlimited unless configured. */
    private val rateLimiter: ProviderRateLimiter = ProviderRateLimiter.UNLIMITED,
    /**
     * Hard budget stop for in-process calls (ADR 0025), the same check the sandbox LLM proxy makes:
     * `(context, estimatedCostUsd, estimatedTokens)`; throws when a hard limit would be crossed.
     */
    private val budgetPrecheck: (UsageContext, java.math.BigDecimal, Long) -> Unit = { _, _, _ -> },
    /** ADR 0046: request limits of provider connections. */
    private val quota: ConnectionQuota = ConnectionQuota.NONE,
) : LlmGateway {

    private val log = LoggerFactory.getLogger(SpringAiLlmGateway::class.java)
    /** ADR 0045: one client per endpoint (connection or environment), replaced when its URL or key changes. */
    private val models = ConcurrentHashMap<String, Pair<String, ChatModel>>()
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun isConfigured(provider: Provider): Boolean = registry.isConfigured(provider)

    override fun isConfigured(model: ModelRef): Boolean = registry.isConfigured(model)

    override fun chat(model: ModelRef, request: LlmRequest): LlmResult {
        val provider = model.provider
        val system = provider.name.lowercase()
        val endpoint = registry.resolve(model) ?: throw IntegrationException(
            system,
            model.connection?.let { "provider connection $it ($system) is missing, disabled or has no API key / base URL" }
                ?: "provider is not configured on this server (add a connection under Settings, LLM providers)",
            persistent = true,
        )
        val options = merge(model.options, request.optionsOverride)
        val chatOptions = optionsMapper.map(model, options, request.responseSchema)
        val messages = toSpringMessages(request.messages, request.responseSchema)
        val chatModel = models.compute(endpoint.id) { _, cached ->
            if (cached != null && cached.first == endpoint.fingerprint) cached else endpoint.fingerprint to factory.create(provider, endpoint.settings)
        }!!.second

        val observation = Observation.createNotStarted("backoffice.llm.chat", observationRegistry)
            .lowCardinalityKeyValue("provider", system)
            .lowCardinalityKeyValue("model", model.modelId)
            .lowCardinalityKeyValue("context_kind", request.usageContext.kind.name)
        contextTags(request.usageContext).forEach { (k, v) -> observation.highCardinalityKeyValue(k, v) }

        val maxOutput = options.maxOutputTokens ?: properties.defaultMaxOutputTokens
        val promptChars = request.messages.sumOf { it.content.length.toLong() } + (request.responseSchema?.length?.toLong() ?: 0L)
        val estimatedCost = catalog.findPricing(model)?.costOf(TokenUsage((promptChars + 3) / 4, maxOutput.toLong()))?.amount ?: java.math.BigDecimal.ZERO
        budgetPrecheck(request.usageContext, estimatedCost, ProviderRateLimiter.estimateTokens(promptChars, maxOutput))
        val permit = rateLimiter.acquire(provider, CallPriority.of(request.usageContext.kind), ProviderRateLimiter.estimateTokens(promptChars, maxOutput))
        quota.admit(endpoint)

        val semaphore = semaphores.computeIfAbsent(endpoint.id) { Semaphore(properties.concurrencyPerProvider.coerceAtLeast(1), true) }
        if (!semaphore.tryAcquire(properties.defaultTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw IntegrationException(system, "timed out waiting for a free slot (concurrency ${properties.concurrencyPerProvider})", retryable = true)
        }
        val startedAt = clock.now()
        val startNanos = System.nanoTime()
        return try {
            withMdc(request.usageContext) {
                observation.observe<LlmResult> {
                    var totalUsage = TokenUsage.ZERO
                    var response = callWithRetry(chatModel, system, Prompt(messages, chatOptions))
                    totalUsage += usageOf(provider, response)
                    var content = textOf(response)
                    var structured: Map<String, Any?>? = null
                    if (request.responseSchema != null) {
                        structured = try {
                            parser.parse(content)
                        } catch (first: StructuredOutputParser.ParseFailure) {
                            log.debug("structured output parse failed provider={} model={} reason={}; retrying once", system, model.modelId, first.message)
                            val corrective = messages + AssistantMessage(content) + UserMessage(CORRECTIVE_MESSAGE)
                            response = callWithRetry(chatModel, system, Prompt(corrective, chatOptions))
                            totalUsage += usageOf(provider, response)
                            content = textOf(response)
                            try {
                                parser.parse(content)
                            } catch (second: StructuredOutputParser.ParseFailure) {
                                throw IntegrationException(system, "structured output could not be parsed: ${second.message}")
                            }
                        }
                    }
                    val latency = Duration.ofNanos(System.nanoTime() - startNanos)
                    // Replace the estimate with what the provider reported (cache reads do not count against quota).
                    permit.complete(totalUsage.inputTokens + totalUsage.outputTokens + totalUsage.cacheWriteTokens)
                    val cost = catalog.findPricing(model)?.costOf(totalUsage) ?: Money.ZERO
                    record(model, totalUsage, cost, latency, request.usageContext, startedAt)
                    observation.highCardinalityKeyValue("backoffice.cost_usd", cost.amount.toPlainString())
                    log.debug(
                        "llm call provider={} model={} kind={} input={} output={} cacheRead={} latencyMs={} cost={}",
                        system, model.modelId, request.usageContext.kind, totalUsage.inputTokens, totalUsage.outputTokens,
                        totalUsage.cacheReadTokens, latency.toMillis(), cost.amount.toPlainString(),
                    )
                    LlmResult(
                        content = content,
                        structured = structured,
                        usage = totalUsage,
                        finishReason = finishReasonOf(response),
                        latency = latency,
                        modelRef = model,
                        providerResponseId = response.metadata?.id?.takeIf { it.isNotBlank() },
                    )
                }!!
            }
        } finally {
            semaphore.release()
        }
    }

    private fun callWithRetry(chatModel: ChatModel, system: String, prompt: Prompt): ChatResponse {
        var attempt = 0
        while (true) {
            try {
                return callWithTimeout(chatModel, system, prompt)
            } catch (e: IntegrationException) {
                if (!e.retryable || attempt >= properties.maxRetries) throw e
                attempt++
                val backoff = backoff(attempt)
                log.warn("provider call failed system={} attempt={} retryIn={}ms message={}", system, attempt, backoff.toMillis(), e.message)
                sleeper(backoff)
            }
        }
    }

    private fun callWithTimeout(chatModel: ChatModel, system: String, prompt: Prompt): ChatResponse {
        val future = executor.submit<ChatResponse> { chatModel.call(prompt) }
        try {
            return future.get(properties.defaultTimeout.toMillis(), TimeUnit.MILLISECONDS)
                ?: throw IntegrationException(system, "provider returned no response")
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IntegrationException(system, "call timed out after ${properties.defaultTimeout}", e, retryable = false)
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            if (cause is IntegrationException) throw cause
            val verdict = ProviderErrorClassifier.classify(cause)
            throw IntegrationException(system, verdict.message, cause, verdict.retryable, verdict.persistent)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw IntegrationException(system, "interrupted while waiting for the provider", e)
        }
    }

    private fun backoff(attempt: Int): Duration {
        val base = properties.retryBackoff.toMillis().coerceAtLeast(1)
        val exponential = base * (1L shl (attempt - 1))
        val jitter = ThreadLocalRandom.current().nextLong(0, base / 2 + 1)
        return Duration.ofMillis(exponential + jitter)
    }

    private fun record(model: ModelRef, usage: TokenUsage, cost: Money, latency: Duration, context: UsageContext, occurredAt: java.time.Instant) {
        try {
            ledger.record(
                UsageRecord(
                    occurredAt = occurredAt,
                    modelRef = model,
                    usage = usage,
                    cost = cost,
                    estimatedCost = cost,
                    durationMs = latency.toMillis(),
                    context = context,
                ),
            )
        } catch (e: RuntimeException) {
            log.error("usage record could not be written model={} kind={}: {}", model.label, context.kind, e.message)
        }
    }

    private fun toSpringMessages(messages: List<LlmMessage>, schema: String?): List<Message> {
        val converted: MutableList<Message> = messages.map {
            when (it.role) {
                Role.SYSTEM -> SystemMessage(it.content)
                Role.USER -> UserMessage(it.content)
                Role.ASSISTANT -> AssistantMessage(it.content)
            }
        }.toMutableList()
        if (schema != null) {
            val instruction = schemaInstruction(schema)
            val systemIndex = converted.indexOfFirst { it is SystemMessage }
            if (systemIndex >= 0) {
                converted[systemIndex] = SystemMessage(converted[systemIndex].text + "\n\n" + instruction)
            } else {
                converted.add(0, SystemMessage(instruction))
            }
        }
        return converted
    }

    private fun usageOf(provider: Provider, response: ChatResponse): TokenUsage {
        val usage: Usage = response.metadata?.usage ?: return TokenUsage.ZERO
        var input = (usage.promptTokens ?: 0).toLong()
        val output = (usage.completionTokens ?: 0).toLong()
        var cacheRead = usage.cacheReadInputTokens ?: 0L
        var cacheWrite = usage.cacheWriteInputTokens ?: 0L
        (usage.nativeUsage as? com.anthropic.models.messages.Usage)?.let { native ->
            if (cacheRead == 0L) cacheRead = native.cacheReadInputTokens().orElse(0L)
            if (cacheWrite == 0L) cacheWrite = native.cacheCreationInputTokens().orElse(0L)
        }
        // OpenAI-style usage reports cached tokens as a subset of prompt tokens; keep the buckets disjoint for pricing.
        if ((provider == Provider.OPENAI || provider == Provider.OPENAI_COMPATIBLE) && cacheRead > 0) {
            input = (input - cacheRead).coerceAtLeast(0)
        }
        return TokenUsage(input, output, cacheRead, cacheWrite)
    }

    private fun textOf(response: ChatResponse): String =
        response.results.orEmpty().mapNotNull { it.output?.text }.joinToString("").ifEmpty { response.result?.output?.text ?: "" }

    private fun finishReasonOf(response: ChatResponse): FinishReason {
        val raw = response.result?.metadata?.finishReason?.lowercase() ?: return FinishReason.STOP
        return when {
            raw.isBlank() || raw == "stop" || raw == "end_turn" || raw == "stop_sequence" -> FinishReason.STOP
            raw == "length" || raw == "max_tokens" || raw == "model_length" -> FinishReason.LENGTH
            raw.contains("tool") -> FinishReason.TOOL_USE
            raw == "refusal" || raw == "content_filter" || raw == "safety" -> FinishReason.REFUSAL
            else -> FinishReason.OTHER
        }
    }

    private fun contextTags(context: UsageContext): Map<String, String> = buildMap {
        context.runId?.let { put("backoffice.run_id", it.toString()) }
        context.stepId?.let { put("backoffice.step_id", it) }
        context.projectId?.let { put("backoffice.project_id", it.toString()) }
        context.reviewId?.let { put("backoffice.review_id", it.toString()) }
        context.benchmarkId?.let { put("backoffice.benchmark_id", it.toString()) }
        context.attemptId?.let { put("backoffice.attempt_id", it.toString()) }
    }

    private inline fun <T> withMdc(context: UsageContext, block: () -> T): T {
        val tags = contextTags(context)
        tags.forEach { (k, v) -> MDC.put(k, v) }
        try {
            return block()
        } finally {
            tags.keys.forEach { MDC.remove(it) }
        }
    }

    companion object {
        const val CORRECTIVE_MESSAGE = "Your previous reply was not a valid JSON object matching the required schema. " +
            "Reply again with only the JSON object, no Markdown fences, no commentary."

        fun schemaInstruction(schema: String): String =
            "Respond with a single JSON object that conforms to the following JSON Schema. Output only the JSON, " +
                "without Markdown code fences or any text before or after it.\n\nJSON Schema:\n$schema"

        /** Request-level override wins field by field; `extra` maps are merged. */
        fun merge(base: ModelOptions, override: ModelOptions?): ModelOptions {
            if (override == null) return base
            return ModelOptions(
                temperature = override.temperature ?: base.temperature,
                maxOutputTokens = override.maxOutputTokens ?: base.maxOutputTokens,
                reasoning = override.reasoning,
                extra = base.extra + override.extra,
            )
        }
    }
}

