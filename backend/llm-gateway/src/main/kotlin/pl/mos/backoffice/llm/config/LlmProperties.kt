package pl.mos.backoffice.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.llm.ratelimit.ProviderLimits
import pl.mos.backoffice.shared.llm.Provider
import java.time.Duration

/** `backoffice.llm.*` settings of the gateway. Provider API keys live under `spring.ai.<provider>.*`. */
@ConfigurationProperties(prefix = "backoffice.llm")
data class LlmProperties(
    val openaiCompatible: OpenAiCompatible = OpenAiCompatible(),
    /** Wall-clock limit of one provider call (including provider-side retries). */
    val defaultTimeout: Duration = Duration.ofMinutes(10),
    /** Concurrent in-flight calls per provider; further callers wait. */
    val concurrencyPerProvider: Int = 4,
    /** Additional attempts on 429 / 5xx / connection errors. */
    val maxRetries: Int = 2,
    /** Base delay of the jittered exponential backoff between retries. */
    val retryBackoff: Duration = Duration.ofMillis(750),
    /** Use provider-side schema enforcement for structured output where the SDK supports it. */
    val nativeStructuredOutput: Boolean = true,
    /** Default `max_tokens` when neither the profile nor the request set one. */
    val defaultMaxOutputTokens: Int = 8192,
    /**
     * Provider rate limits (ADR 0020), keyed by provider in kebab case: `anthropic`, `openai`, `google`,
     * `mistral`, `ollama`, `openai-compatible`. Providers without an entry are not limited.
     */
    val rateLimits: Map<String, RateLimit> = emptyMap(),
) {
    data class OpenAiCompatible(val baseUrl: String = "", val apiKey: String = "")

    /** `backoffice.llm.rate-limits.<provider>.*`; limits are per API replica. */
    data class RateLimit(
        val requestsPerMinute: Int? = null,
        val tokensPerMinute: Long? = null,
        /** Share of each budget reserved for interactive calls (workflow steps, reviews, model tests). */
        val interactiveReserve: Double = 0.2,
        /** Waits up to this long inside the call before failing with a retryable error. */
        val maxWait: Duration = Duration.ofSeconds(2),
    )

    /** [rateLimits] by provider; unknown keys fail at startup instead of being ignored silently. */
    fun providerLimits(): Map<Provider, ProviderLimits> = rateLimits.entries.associate { (key, value) ->
        val provider = Provider.entries.firstOrNull { it.name == key.uppercase().replace('-', '_') }
            ?: throw IllegalArgumentException("backoffice.llm.rate-limits.$key: unknown provider (use ${Provider.entries.joinToString { it.name.lowercase().replace('_', '-') }})")
        provider to ProviderLimits(value.requestsPerMinute, value.tokensPerMinute, value.interactiveReserve, value.maxWait)
    }
}
