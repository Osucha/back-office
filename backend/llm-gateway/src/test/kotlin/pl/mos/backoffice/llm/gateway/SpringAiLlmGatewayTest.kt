package pl.mos.backoffice.llm.gateway

import com.anthropic.models.messages.OutputConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel
import org.springframework.ai.mistralai.MistralAiChatOptions
import org.springframework.ai.mistralai.api.MistralAiApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import pl.mos.backoffice.llm.config.LlmProperties
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.FinishReason
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.Pricing
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.llm.ratelimit.ProviderLimits
import pl.mos.backoffice.llm.ratelimit.ProviderRateLimiter
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.EnumMap
import java.util.Optional
import java.util.UUID

class SpringAiLlmGatewayTest {

    private class RecordingLedger : UsageLedger {
        val records = mutableListOf<UsageRecord>()
        override fun record(record: UsageRecord) { records += record }
        override fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary = error("not used")
        override fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> = error("not used")
        override fun totalCost(query: UsageQuery): Money = error("not used")
    }

    private val fakes = EnumMap<Provider, FakeChatModel>(Provider::class.java)
    private fun fake(provider: Provider): FakeChatModel = fakes.getOrPut(provider) { FakeChatModel() }
    private val ledger = RecordingLedger()
    private val catalog = mockk<ModelCatalog>()
    private val sleeps = mutableListOf<Duration>()
    private val properties = LlmProperties(
        openaiCompatible = LlmProperties.OpenAiCompatible(baseUrl = "https://openrouter.example/v1", apiKey = "or-key"),
        defaultTimeout = Duration.ofSeconds(5),
        maxRetries = 2,
        retryBackoff = Duration.ofMillis(10),
    )
    private val registry = ProviderRegistry(
        mapOf(
            Provider.ANTHROPIC to ProviderSettings(apiKey = "sk-ant"),
            Provider.OPENAI to ProviderSettings(apiKey = "sk-openai"),
            Provider.GOOGLE to ProviderSettings(apiKey = "g-key"),
            Provider.MISTRAL to ProviderSettings(apiKey = "m-key"),
            Provider.OLLAMA to ProviderSettings(baseUrl = "http://ollama:11434"),
            Provider.OPENAI_COMPATIBLE to ProviderSettings(apiKey = "or-key", baseUrl = "https://openrouter.example/v1"),
        ),
    )
    private val gateway = SpringAiLlmGateway(
        registry = registry,
        factory = { provider, _ -> fake(provider) },
        optionsMapper = ChatOptionsMapper(defaultMaxOutputTokens = 4096, nativeStructuredOutput = true),
        parser = StructuredOutputParser(JsonMapper.builder().build()),
        catalog = catalog,
        ledger = ledger,
        clock = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z")),
        properties = properties,
        sleeper = { sleeps += it },
    )

    private val context = UsageContext(kind = UsageKind.PIPELINE_STEP, runId = UUID.randomUUID(), stepId = "specify", projectId = UUID.randomUUID())

    init {
        every { catalog.findPricing(any()) } returns Pricing(BigDecimal("2"), BigDecimal("10"), cacheReadPerMTok = BigDecimal("0.2"))
    }

    @Test
    fun `anthropic options use adaptive thinking with effort and never send temperature while thinking`() {
        val model = ModelRef(Provider.ANTHROPIC, "claude-fable-5-1", ModelOptions(temperature = 0.2, maxOutputTokens = 2048, reasoning = Reasoning.HIGH))
        fake(Provider.ANTHROPIC).reply("hello")

        val result = gateway.chat(model, LlmRequest.of(null, "hi", context))

        assertThat(result.content).isEqualTo("hello")
        val options = fake(Provider.ANTHROPIC).prompts.single().options as AnthropicChatOptions
        assertThat(options.model).isEqualTo("claude-fable-5-1")
        assertThat(options.maxTokens).isEqualTo(2048)
        assertThat(options.thinking!!.isAdaptive()).isTrue()
        assertThat(options.outputConfig!!.effort().get()).isEqualTo(OutputConfig.Effort.HIGH)
        assertThat(options.temperature).isNull()
    }

    @Test
    fun `anthropic NONE disables thinking on haiku but keeps adaptive on fable and opus 5`() {
        fake(Provider.ANTHROPIC).reply("a").reply("b").reply("c")

        gateway.chat(ModelRef(Provider.ANTHROPIC, "claude-haiku-4-5", ModelOptions(temperature = 0.1, reasoning = Reasoning.NONE)), LlmRequest.of(null, "hi", context))
        gateway.chat(ModelRef(Provider.ANTHROPIC, "claude-fable-5-1", ModelOptions(reasoning = Reasoning.NONE)), LlmRequest.of(null, "hi", context))
        gateway.chat(ModelRef(Provider.ANTHROPIC, "claude-opus-5", ModelOptions(reasoning = Reasoning.XHIGH)), LlmRequest.of(null, "hi", context))

        val prompts = fake(Provider.ANTHROPIC).prompts
        val haiku = prompts[0].options as AnthropicChatOptions
        assertThat(haiku.thinking!!.isDisabled()).isTrue()
        assertThat(haiku.temperature).isEqualTo(0.1)
        assertThat(haiku.maxTokens).isEqualTo(4096)
        val fable = prompts[1].options as AnthropicChatOptions
        assertThat(fable.thinking!!.isAdaptive()).isTrue()
        val opus = prompts[2].options as AnthropicChatOptions
        assertThat(opus.outputConfig!!.effort().get()).isEqualTo(OutputConfig.Effort.XHIGH)
    }

    @Test
    fun `openai options map reasoning effort, completion tokens and json schema response format`() {
        fake(Provider.OPENAI).reply("""{"ok":true}""")
        val model = ModelRef(Provider.OPENAI, "gpt-5", ModelOptions(maxOutputTokens = 1000, reasoning = Reasoning.MEDIUM))

        val result = gateway.chat(model, LlmRequest.of("system", "user", context, responseSchema = SCHEMA))

        val options = fake(Provider.OPENAI).prompts.single().options as OpenAiChatOptions
        assertThat(options.model).isEqualTo("gpt-5")
        assertThat(options.maxCompletionTokens).isEqualTo(1000)
        assertThat(options.reasoningEffort).isEqualTo("medium")
        assertThat(options.responseFormat!!.type).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
        assertThat(options.responseFormat!!.jsonSchema).isEqualTo(SCHEMA)
        assertThat(result.structured).isEqualTo(mapOf("ok" to true))
    }

    @Test
    fun `openai compatible uses configured base url, json_object format and subtracts cached tokens from input`() {
        fake(Provider.OPENAI_COMPATIBLE).reply("""{"ok":1}""", usage = DefaultUsage(100, 20, 120, null, 40L, 0L))
        val model = ModelRef(Provider.OPENAI_COMPATIBLE, "llama-4", ModelOptions(reasoning = Reasoning.NONE, temperature = 0.7))

        val result = gateway.chat(model, LlmRequest.of(null, "user", context, responseSchema = SCHEMA))

        val options = fake(Provider.OPENAI_COMPATIBLE).prompts.single().options as OpenAiChatOptions
        assertThat(options.responseFormat!!.type).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
        assertThat(options.reasoningEffort).isNull()
        assertThat(options.temperature).isEqualTo(0.7)
        assertThat(result.usage).isEqualTo(TokenUsage(inputTokens = 60, outputTokens = 20, cacheReadTokens = 40))
        assertThat(registry.baseUrl(Provider.OPENAI_COMPATIBLE)).isEqualTo("https://openrouter.example/v1")
    }

    @Test
    fun `google, mistral and ollama options are mapped`() {
        fake(Provider.GOOGLE).reply("g")
        fake(Provider.MISTRAL).reply("m")
        fake(Provider.OLLAMA).reply("o")

        gateway.chat(ModelRef(Provider.GOOGLE, "gemini-2.5-pro", ModelOptions(maxOutputTokens = 512, reasoning = Reasoning.MAX)), LlmRequest.of(null, "hi", context, responseSchema = SCHEMA).copy(responseSchema = null))
        gateway.chat(ModelRef(Provider.MISTRAL, "mistral-large-latest", ModelOptions(maxOutputTokens = 300, reasoning = Reasoning.HIGH)), LlmRequest.of(null, "hi", context))
        gateway.chat(ModelRef(Provider.OLLAMA, "qwen3:32b", ModelOptions(maxOutputTokens = 256, reasoning = Reasoning.NONE, temperature = 0.0)), LlmRequest.of(null, "hi", context))

        val google = fake(Provider.GOOGLE).prompts.single().options as GoogleGenAiChatOptions
        assertThat(google.model).isEqualTo("gemini-2.5-pro")
        assertThat(google.maxOutputTokens).isEqualTo(512)
        assertThat(google.thinkingLevel).isEqualTo(GoogleGenAiThinkingLevel.HIGH)
        assertThat(google.responseMimeType).isNull()

        val mistral = fake(Provider.MISTRAL).prompts.single().options as MistralAiChatOptions
        assertThat(mistral.model).isEqualTo("mistral-large-latest")
        assertThat(mistral.maxTokens).isEqualTo(300)
        assertThat(mistral.reasoningEffort).isEqualTo(MistralAiApi.ChatCompletionRequest.ReasoningEffort.HIGH)

        val ollama = fake(Provider.OLLAMA).prompts.single().options as OllamaChatOptions
        assertThat(ollama.model).isEqualTo("qwen3:32b")
        assertThat(ollama.numPredict).isEqualTo(256)
        assertThat(ollama.thinkOption!!.toJsonValue()).isEqualTo(false)
        assertThat(ollama.temperature).isEqualTo(0.0)
    }

    @Test
    fun `structured output strips fences and appends the schema to the system prompt`() {
        fake(Provider.ANTHROPIC).reply("Here you go:\n```json\n{\"score\": 91, \"passed\": true}\n```")
        val model = ModelRef(Provider.ANTHROPIC, "claude-sonnet-5")

        val result = gateway.chat(model, LlmRequest.of("You judge.", "Judge this.", context, responseSchema = SCHEMA))

        assertThat(result.structured).isEqualTo(mapOf("score" to 91, "passed" to true))
        val prompt = fake(Provider.ANTHROPIC).prompts.single()
        val system = prompt.instructions.filterIsInstance<SystemMessage>().single()
        assertThat(system.text).startsWith("You judge.").contains("JSON Schema").contains(SCHEMA)
        assertThat(prompt.instructions).hasSize(2)
        assertThat((prompt.options as AnthropicChatOptions).outputSchema).isEqualTo(SCHEMA)
    }

    @Test
    fun `unparseable structured output is retried once with a corrective message and usage is summed`() {
        fake(Provider.OPENAI).reply("Sure! Let me think about it.", usage = DefaultUsage(100, 10)).reply("""{"score": 5}""", usage = DefaultUsage(120, 8))

        val result = gateway.chat(ModelRef(Provider.OPENAI, "gpt-5"), LlmRequest.of(null, "judge", context, responseSchema = SCHEMA))

        assertThat(result.structured).isEqualTo(mapOf("score" to 5))
        assertThat(result.usage).isEqualTo(TokenUsage(220, 18))
        val prompts = fake(Provider.OPENAI).prompts
        assertThat(prompts).hasSize(2)
        assertThat(prompts[1].instructions.last().text).isEqualTo(SpringAiLlmGateway.CORRECTIVE_MESSAGE)
        assertThat(prompts[1].instructions[prompts[1].instructions.size - 2].text).isEqualTo("Sure! Let me think about it.")
        assertThat(ledger.records).hasSize(1)

        fake(Provider.OPENAI).reply("nope").reply("still nope")
        assertThatThrownBy { gateway.chat(ModelRef(Provider.OPENAI, "gpt-5"), LlmRequest.of(null, "judge", context, responseSchema = SCHEMA)) }
            .isInstanceOf(IntegrationException::class.java)
            .hasMessageContaining("structured output")
    }

    @Test
    fun `retryable provider errors are retried with backoff, non retryable ones are not`() {
        fake(Provider.OPENAI)
            .fail(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate limited", org.springframework.http.HttpHeaders.EMPTY, ByteArray(0), null))
            .fail(TransientAiException("502 upstream"))
            .reply("finally")

        val result = gateway.chat(ModelRef(Provider.OPENAI, "gpt-5"), LlmRequest.of(null, "hi", context))

        assertThat(result.content).isEqualTo("finally")
        assertThat(fake(Provider.OPENAI).prompts).hasSize(3)
        assertThat(sleeps).hasSize(2)
        assertThat(sleeps[1]).isGreaterThanOrEqualTo(sleeps[0].minusMillis(5))

        fake(Provider.ANTHROPIC).fail(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized", org.springframework.http.HttpHeaders.EMPTY, ByteArray(0), null))
        assertThatThrownBy { gateway.chat(ModelRef(Provider.ANTHROPIC, "claude-sonnet-5"), LlmRequest.of(null, "hi", context)) }
            .isInstanceOfSatisfying(IntegrationException::class.java) {
                assertThat(it.system).isEqualTo("anthropic")
                assertThat(it.retryable).isFalse()
                assertThat(it.message).contains("401").contains("API key")
            }
        assertThat(fake(Provider.ANTHROPIC).prompts).hasSize(1)
    }

    @Test
    fun `gives up after max retries with a retryable integration exception`() {
        fake(Provider.MISTRAL)
            .fail(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .fail(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .fail(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { gateway.chat(ModelRef(Provider.MISTRAL, "mistral-large-latest"), LlmRequest.of(null, "hi", context)) }
            .isInstanceOfSatisfying(IntegrationException::class.java) {
                assertThat(it.retryable).isTrue()
                assertThat(it.system).isEqualTo("mistral")
            }
        assertThat(fake(Provider.MISTRAL).prompts).hasSize(3)
        assertThat(ledger.records).isEmpty()
    }

    @Test
    fun `records usage with cache tokens from native anthropic usage and cost from catalogue pricing`() {
        val native = com.anthropic.models.messages.Usage.builder()
            .inputTokens(1000)
            .outputTokens(500)
            .cacheReadInputTokens(2000L)
            .cacheCreationInputTokens(0L)
            .cacheCreation(Optional.empty())
            .serverToolUse(Optional.empty())
            .serviceTier(Optional.empty())
            .inferenceGeo(Optional.empty())
            .outputTokensDetails(Optional.empty())
            .build()
        fake(Provider.ANTHROPIC).reply("cached answer", usage = DefaultUsage(1000, 500, 1500, native), finishReason = "max_tokens", id = "msg_123")
        val model = ModelRef(Provider.ANTHROPIC, "claude-sonnet-5")

        val result = gateway.chat(model, LlmRequest.of(null, "hi", context))

        assertThat(result.usage).isEqualTo(TokenUsage(inputTokens = 1000, outputTokens = 500, cacheReadTokens = 2000))
        assertThat(result.finishReason).isEqualTo(FinishReason.LENGTH)
        assertThat(result.providerResponseId).isEqualTo("msg_123")
        val record = ledger.records.single()
        assertThat(record.modelRef).isEqualTo(model)
        assertThat(record.usage).isEqualTo(result.usage)
        // 1000*2/1e6 + 500*10/1e6 + 2000*0.2/1e6 = 0.002 + 0.005 + 0.0004
        assertThat(record.cost).isEqualTo(Money.usd("0.0074"))
        assertThat(record.estimatedCost).isEqualTo(record.cost)
        assertThat(record.context).isEqualTo(context)
        assertThat(record.durationMs).isNotNull()
        assertThat(record.occurredAt).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"))
    }

    @Test
    fun `request options override profile options field by field`() {
        fake(Provider.OPENAI).reply("x")
        val model = ModelRef(Provider.OPENAI, "gpt-5", ModelOptions(maxOutputTokens = 100, reasoning = Reasoning.HIGH, extra = mapOf("topP" to 0.9)))

        gateway.chat(model, LlmRequest.of(null, "hi", context).copy(optionsOverride = ModelOptions(maxOutputTokens = 64, reasoning = Reasoning.LOW)))

        val options = fake(Provider.OPENAI).prompts.single().options as OpenAiChatOptions
        assertThat(options.maxCompletionTokens).isEqualTo(64)
        assertThat(options.reasoningEffort).isEqualTo("low")
        assertThat(options.topP).isEqualTo(0.9)
    }

    @Test
    fun `unconfigured provider fails fast and isConfigured follows the registry rules`() {
        val empty = ProviderRegistry(mapOf(Provider.OLLAMA to ProviderSettings(baseUrl = "http://x"), Provider.OPENAI_COMPATIBLE to ProviderSettings(baseUrl = "http://y")))
        val bare = SpringAiLlmGateway(empty, { p, _ -> fake(p) }, ChatOptionsMapper(1, true), StructuredOutputParser(JsonMapper.builder().build()), catalog, ledger, Clock.SYSTEM, properties)

        assertThat(bare.isConfigured(Provider.OLLAMA)).isTrue()
        assertThat(bare.isConfigured(Provider.OPENAI_COMPATIBLE)).isFalse()
        assertThat(bare.isConfigured(Provider.ANTHROPIC)).isFalse()
        assertThatThrownBy { bare.chat(ModelRef(Provider.ANTHROPIC, "claude-sonnet-5"), LlmRequest.of(null, "hi", context)) }
            .isInstanceOf(IntegrationException::class.java)
            .hasMessageContaining("not configured")
    }

    @Test
    fun `the rate limiter corrects the token estimate with actual usage and refuses batch calls beyond the budget as retryable`() {
        val limiter = ProviderRateLimiter(
            mapOf(Provider.ANTHROPIC to ProviderLimits(requestsPerMinute = 5, tokensPerMinute = 5_000, interactiveReserve = 0.2, maxWait = Duration.ZERO)),
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z")),
        ) { error("no waiting expected") }
        val limited = SpringAiLlmGateway(
            registry, { p, _ -> fake(p) }, ChatOptionsMapper(4096, true), StructuredOutputParser(JsonMapper.builder().build()),
            catalog, ledger, Clock.fixed(Instant.parse("2026-09-26T12:00:00Z")), properties, rateLimiter = limiter,
        )
        val model = ModelRef(Provider.ANTHROPIC, "claude-haiku-4-5", ModelOptions(maxOutputTokens = 1_000))
        val batch = UsageContext(kind = UsageKind.BENCHMARK_ATTEMPT, benchmarkId = UUID.randomUUID())
        fake(Provider.ANTHROPIC).reply("a").reply("b").reply("c")

        limited.chat(model, LlmRequest.of(null, "x".repeat(400), batch))
        assertThat(limiter.usage(Provider.ANTHROPIC)).describedAs("estimate 1100 replaced by 10 + 20").isEqualTo(1L to 30L)
        limited.chat(model, LlmRequest.of(null, "hi", batch))
        limited.chat(model, LlmRequest.of(null, "hi", batch))
        limited.chat(ModelRef(Provider.ANTHROPIC, "claude-haiku-4-5", ModelOptions(maxOutputTokens = 3_000)), LlmRequest.of(null, "hi", context).also { fake(Provider.ANTHROPIC).reply("d") })

        assertThatThrownBy { limited.chat(model, LlmRequest.of(null, "hi", batch)) }
            .isInstanceOf(IntegrationException::class.java)
            .satisfies({ assertThat((it as IntegrationException).retryable).isTrue() })
            .hasMessageContaining("rate limit")
        assertThat(fake(Provider.ANTHROPIC).prompts).hasSize(4)
    }

    companion object {
        val SCHEMA ="""{"type":"object","properties":{"score":{"type":"number"},"passed":{"type":"boolean"}}}"""
    }
}
