package pl.mos.backoffice.llm.proxy

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import pl.mos.backoffice.llm.budget.application.port.`in`.EnforceBudgets
import pl.mos.backoffice.llm.budget.domain.BudgetExceededException
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.llm.ratelimit.CallPriority
import pl.mos.backoffice.llm.ratelimit.ProviderRateLimiter
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageRecord
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import pl.mos.backoffice.llm.connections.ConnectionQuota

/**
 * The LLM gateway proxy for sandboxes (ADR 0024 "credential brokering"): the agent calls Anthropic- or
 * OpenAI-compatible endpoints with its job token; the proxy checks the model allowlist, the budgets and the
 * rate limiter, calls the real provider with the platform's key and passes the response (or SSE stream)
 * through unchanged while reading the usage from it, then books the usage into the ledger (which also
 * moves the monthly budget state) and the job's spend.
 */
class LlmProxy(
    private val registry: ProviderRegistry,
    private val properties: ProxyProperties,
    private val openAiCompatibleBaseUrl: String?,
    private val rateLimiter: ProviderRateLimiter,
    private val budgets: EnforceBudgets,
    private val ledger: UsageLedger,
    private val catalog: ModelCatalog?,
    private val gate: AuthorizationGate,
    private val json: JsonMapper,
    private val clock: Clock,
    /** ADR 0046: request limits of provider connections; agents' calls count against them too. */
    private val quota: ConnectionQuota = ConnectionQuota.NONE,
) {
    private val log = LoggerFactory.getLogger(LlmProxy::class.java)

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).version(HttpClient.Version.HTTP_1_1).build()

    private val openAiProviders = listOf(Provider.OPENAI, Provider.OPENAI_COMPATIBLE, Provider.GOOGLE, Provider.MISTRAL, Provider.OLLAMA)

    // ---------------------------------------------------------------- endpoints

    fun anthropicMessages(credential: JobCredential, request: HttpServletRequest, response: HttpServletResponse, body: ByteArray) =
        call(credential, ProxyProtocol.ANTHROPIC, "/v1/messages", request, response, body, metered = true)

    fun anthropicCountTokens(credential: JobCredential, request: HttpServletRequest, response: HttpServletResponse, body: ByteArray) =
        call(credential, ProxyProtocol.ANTHROPIC, "/v1/messages/count_tokens", request, response, body, metered = false)

    fun openAiChat(credential: JobCredential, request: HttpServletRequest, response: HttpServletResponse, body: ByteArray) =
        call(credential, ProxyProtocol.OPENAI, "/chat/completions", request, response, body, metered = true)

    /** Only the models on the job's allowlist (patterns are not listed). */
    fun openAiModels(credential: JobCredential): Map<String, Any?> = mapOf(
        "object" to "list",
        "data" to credential.modelAllowlist.filter { '*' !in it }.mapNotNull { label ->
            val provider = label.substringBefore('/')
            if (openAiProviders.none { it.name.lowercase() == provider }) null
            else mapOf("id" to label.substringAfter('/'), "object" to "model", "created" to 0, "owned_by" to provider)
        },
    )

    // ---------------------------------------------------------------- the call

    private fun call(
        credential: JobCredential,
        protocol: ProxyProtocol,
        path: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        rawBody: ByteArray,
        metered: Boolean,
    ) {
        val body = runCatching { json.readTree(rawBody) as? ObjectNode }.getOrNull()
            ?: return ProxyProblems.write(response, 400, "Bad request", "The request body must be a JSON object", "invalid-request")
        val modelId = body.get("model")?.asString()?.takeIf { it.isNotBlank() }
            ?: return ProxyProblems.write(response, 400, "Bad request", "model is required", "invalid-request")
        val provider = resolveProvider(protocol, modelId, credential)
            ?: return ProxyProblems.write(
                response, 403, "Model not allowed", "Model $modelId is not on this job's allowlist (${credential.modelAllowlist.joinToString()})", "model-not-allowed",
                mapOf("model" to modelId),
            )
        // ADR 0045: the job's model may be served by a named provider connection
        val connection = credential.modelConnections["${provider.name.lowercase()}/$modelId"]
        val endpoint = registry.resolve(provider, connection)
            ?: return ProxyProblems.write(
                response, 502, "Provider not configured",
                "No usable connection is configured for ${provider.name.lowercase()}" + (connection?.let { " (connection $it)" } ?: ""), "provider-not-configured",
            )
        try {
            quota.admit(endpoint)
        } catch (e: IntegrationException) {
            response.setHeader("retry-after", if (e.retryable) "60" else "3600")
            return ProxyProblems.write(response, 429, "Connection limit reached", e.message ?: "connection request limit reached", "connection-limit")
        }
        val settings = endpoint.settings
        val streaming = body.get("stream")?.asBoolean() == true
        if (protocol == ProxyProtocol.OPENAI && streaming && metered) {
            // the final chunk then carries the usage; without it the call could not be metered
            (body.get("stream_options") as? ObjectNode ?: body.putObject("stream_options")).put("include_usage", true)
        }
        val modelRef = ModelRef(provider, modelId, connection = connection)
        val context = contextOf(credential)
        val pricing = catalog?.findPricing(modelRef)
        val maxOutput = (body.get("max_tokens") ?: body.get("max_completion_tokens"))?.asInt() ?: properties.estimateOutputTokensCap
        val estimatedTokens = ProviderRateLimiter.estimateTokens(rawBody.size.toLong(), minOf(maxOutput, properties.estimateOutputTokensCap))
        var permit: ProviderRateLimiter.Permit? = null
        if (metered) {
            val estimatedInput = (rawBody.size.toLong() + 3) / 4
            val estimatedCost = pricing?.costOf(TokenUsage(estimatedInput, minOf(maxOutput, properties.estimateOutputTokensCap).toLong()))?.amount ?: BigDecimal.ZERO
            permit = when (val admission = admit(credential, context, provider, estimatedCost, estimatedTokens, response)) {
                Admission.Refused -> return
                is Admission.Granted -> admission.permit
            }
        }

        val upstream = upstreamRequest(protocol, provider, path, settings, request, body, streaming)
        val started = clock.now()
        val usage = UsageAccumulator(protocol)
        try {
            relay(upstream, response, usage)
        } catch (e: java.io.IOException) {
            log.warn("LLM proxy upstream failure provider={} jobId={} error={}", provider, credential.jobId, e.toString())
            if (!response.isCommitted) {
                response.reset()
                ProxyProblems.write(response, 502, "Provider unreachable", "${provider.name.lowercase()}: ${e.message}", "provider-error")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IntegrationException(provider.name.lowercase(), "interrupted", e)
        } finally {
            if (metered) book(credential, context, modelRef, usage, started, permit, pricing)
        }
    }

    /** Result of the budget check and rate limit of a metered call. */
    private sealed interface Admission {
        /** The refusal (402 or 429) is already written to the response. */
        data object Refused : Admission
        data class Granted(val permit: ProviderRateLimiter.Permit) : Admission
    }

    /** Budget pre-check, per-job request cap and provider rate-limit permit of a metered call. */
    private fun admit(
        credential: JobCredential,
        context: UsageContext,
        provider: Provider,
        estimatedCost: BigDecimal,
        estimatedTokens: Long,
        response: HttpServletResponse,
    ): Admission {
        try {
            gate.asSystem("LLM proxy budget check") {
                budgets.precheck(credential, context, estimatedCost, estimatedTokens)
                budgets.countJobRequest(credential, context)
            }
        } catch (e: BudgetExceededException) {
            log.warn("LLM proxy refused jobId={} scope={} reason={}", credential.jobId, e.scope, e.message)
            runCatching { gate.asSystem("LLM proxy budget refusal") { budgets.recordRefusal(credential.jobId, e.message ?: "budget exceeded") } }
                .onFailure { log.warn("could not record the budget refusal of job {}: {}", credential.jobId, it.message) }
            ProxyProblems.write(
                response, 402, "Budget exceeded", e.message ?: "budget exceeded", "budget-exceeded",
                mapOf("scope" to e.scope.name, "projectId" to e.projectId?.toString(), "limit" to e.limit, "spent" to e.spent),
            )
            return Admission.Refused
        }
        return try {
            Admission.Granted(rateLimiter.acquire(provider, if (credential.benchmarkId != null) CallPriority.BATCH else CallPriority.INTERACTIVE, estimatedTokens))
        } catch (e: IntegrationException) {
            response.setHeader("retry-after", "10")
            ProxyProblems.write(response, 429, "Rate limited", e.message ?: "rate limit reached", "rate-limited")
            Admission.Refused
        }
    }

    /** The upstream request with the provider credentials and the protocol headers passed through. */
    private fun upstreamRequest(
        protocol: ProxyProtocol,
        provider: Provider,
        path: String,
        settings: ProviderSettings,
        request: HttpServletRequest,
        body: ObjectNode,
        streaming: Boolean,
    ): HttpRequest {
        val upstream = HttpRequest.newBuilder(URI.create(baseUrl(provider, settings).trimEnd('/') + path))
            .timeout(properties.requestTimeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
        when (protocol) {
            ProxyProtocol.ANTHROPIC -> {
                upstream.header("x-api-key", settings.apiKey.orEmpty())
                upstream.header("anthropic-version", request.getHeader("anthropic-version") ?: properties.defaultAnthropicVersion)
                request.getHeader("anthropic-beta")?.let { upstream.header("anthropic-beta", it) }
            }
            ProxyProtocol.OPENAI -> settings.apiKey?.takeIf { it.isNotBlank() }?.let { upstream.header("Authorization", "Bearer $it") }
        }
        if (streaming) upstream.header("accept", "text/event-stream")
        return upstream.build()
    }

    /** Sends [upstream] and copies status, headers and body (SSE streamed) to [response], collecting usage. */
    private fun relay(upstream: HttpRequest, response: HttpServletResponse, usage: UsageAccumulator) {
        val upstreamResponse = http.send(upstream, HttpResponse.BodyHandlers.ofInputStream())
        response.status = upstreamResponse.statusCode()
        val contentType = upstreamResponse.headers().firstValue("content-type").orElse("application/json")
        response.contentType = contentType
        PASSTHROUGH_HEADERS.forEach { name -> upstreamResponse.headers().firstValue(name).ifPresent { response.setHeader(name, it) } }
        val ok = upstreamResponse.statusCode() in 200..299
        upstreamResponse.body().use { input ->
            if (ok && contentType.startsWith("text/event-stream")) {
                response.setHeader("cache-control", "no-cache")
                pipeEvents(BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)), response, usage)
            } else {
                val bytes = input.readAllBytes()
                if (ok) runCatching { usage.fromBody(json.readTree(bytes)) }
                response.outputStream.write(bytes)
                response.outputStream.flush()
            }
        }
    }

    /** SSE passthrough: every line is forwarded as it arrives, the stream is flushed at each event boundary. */
    private fun pipeEvents(reader: BufferedReader, response: HttpServletResponse, usage: UsageAccumulator) {
        val out = response.outputStream
        while (true) {
            val line = reader.readLine() ?: break
            out.write(line.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            if (line.isEmpty()) out.flush()
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data.startsWith("{")) runCatching { usage.fromEvent(json.readTree(data)) }
            }
        }
        out.flush()
    }

    private fun book(
        credential: JobCredential,
        context: UsageContext,
        modelRef: ModelRef,
        usage: UsageAccumulator,
        started: java.time.Instant,
        permit: ProviderRateLimiter.Permit?,
        pricing: pl.mos.backoffice.shared.usage.Pricing?,
    ) {
        val tokens = usage.usage
        permit?.complete(tokens.totalTokens)
        if (!usage.seen || tokens.totalTokens == 0L) return
        try {
            val cost = pricing?.costOf(tokens) ?: Money.ZERO
            gate.asSystem("LLM proxy usage booking") {
                ledger.record(
                    UsageRecord(
                        occurredAt = clock.now(), modelRef = modelRef, usage = tokens, cost = cost, estimatedCost = cost,
                        durationMs = clock.now().toEpochMilli() - started.toEpochMilli(), context = context,
                    ),
                )
                budgets.recordJobSpend(credential.jobId, cost.amount, tokens.totalTokens)
            }
        } catch (e: RuntimeException) {
            log.error("LLM proxy usage not booked jobId={} model={} usage={}: {}", credential.jobId, modelRef.label, tokens, e.toString())
        }
    }

    private fun resolveProvider(protocol: ProxyProtocol, modelId: String, credential: JobCredential): Provider? {
        val candidates = if (protocol == ProxyProtocol.ANTHROPIC) listOf(Provider.ANTHROPIC) else openAiProviders
        return candidates.firstOrNull { allowed(credential, "${it.name.lowercase()}/$modelId") }
    }

    private fun allowed(credential: JobCredential, label: String): Boolean =
        credential.modelAllowlist.any { pattern -> Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(label) }

    private fun baseUrl(provider: Provider, settings: ProviderSettings): String {
        properties.baseUrls[provider.name.lowercase().replace('_', '-')]?.takeIf { it.isNotBlank() }?.let { return it }
        val own = settings.baseUrl?.takeIf { it.isNotBlank() }
        return when (provider) {
            Provider.ANTHROPIC -> own ?: "https://api.anthropic.com"
            Provider.OPENAI -> own ?: "https://api.openai.com/v1"
            Provider.OPENAI_COMPATIBLE -> own ?: openAiCompatibleBaseUrl?.takeIf { it.isNotBlank() } ?: throw IntegrationException("openai-compatible", "no base URL configured")
            Provider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai"
            Provider.MISTRAL -> own ?: "https://api.mistral.ai/v1"
            Provider.OLLAMA -> (own ?: "http://localhost:11434").trimEnd('/') + "/v1"
        }
    }

    companion object {
        val PASSTHROUGH_HEADERS = listOf("request-id", "x-request-id", "retry-after", "anthropic-organization-id")

        fun contextOf(credential: JobCredential) = UsageContext(
            kind = if (credential.benchmarkId != null) UsageKind.BENCHMARK_ATTEMPT else UsageKind.PIPELINE_STEP,
            projectId = credential.projectId,
            runId = credential.runId,
            stepId = credential.nodeId,
            benchmarkId = credential.benchmarkId,
            attemptId = credential.attemptId,
        )
    }
}
