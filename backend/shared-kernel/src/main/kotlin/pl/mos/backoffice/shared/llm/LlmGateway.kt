package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.usage.UsageContext
import java.time.Duration

enum class Role { SYSTEM, USER, ASSISTANT }

data class LlmMessage(val role: Role, val content: String) {
    companion object {
        fun system(content: String) = LlmMessage(Role.SYSTEM, content)
        fun user(content: String) = LlmMessage(Role.USER, content)
        fun assistant(content: String) = LlmMessage(Role.ASSISTANT, content)
    }
}

/**
 * One chat request. `responseSchema` (a JSON Schema document) asks for structured output; the gateway
 * uses the provider's native schema enforcement where available and validates the JSON otherwise.
 */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val responseSchema: String? = null,
    /** Where to book the usage; the gateway records a `UsageRecord` for every call. */
    val usageContext: UsageContext,
    /** Optional per-request override of the model options. */
    val optionsOverride: ModelOptions? = null,
) {
    init {
        require(messages.isNotEmpty()) { "messages must not be empty" }
    }

    companion object {
        fun of(system: String?, user: String, usageContext: UsageContext, responseSchema: String? = null) =
            LlmRequest(
                messages = listOfNotNull(system?.let { LlmMessage.system(it) }, LlmMessage.user(user)),
                responseSchema = responseSchema,
                usageContext = usageContext,
            )
    }
}

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
    )

    val totalTokens: Long get() = inputTokens + outputTokens

    companion object {
        val ZERO = TokenUsage()
    }
}

enum class FinishReason { STOP, LENGTH, TOOL_USE, REFUSAL, OTHER }

data class LlmResult(
    val content: String,
    /** Parsed JSON object when `responseSchema` was requested; null otherwise. */
    val structured: Map<String, Any?>? = null,
    val usage: TokenUsage,
    val finishReason: FinishReason = FinishReason.STOP,
    val latency: Duration,
    val modelRef: ModelRef,
    val providerResponseId: String? = null,
)

/**
 * Outbound port: call any configured model. Implemented in `llm-gateway` with Spring AI.
 * Implementations must record a `UsageRecord` through `UsageLedger` for every successful call and must
 * throw `IntegrationException(system = provider name)` on provider failure.
 */
interface LlmGateway {
    fun chat(model: ModelRef, request: LlmRequest): LlmResult

    /** Whether credentials for the provider are configured on this server. */
    fun isConfigured(provider: Provider): Boolean

    /** ADR 0045: whether [model] can be called (its connection, or a configured endpoint of its provider). */
    fun isConfigured(model: ModelRef): Boolean = isConfigured(model.provider)

    /**
     * ADR 0050 step 2b: the owner of [model]'s connection when it is a personal one (only their work may use it); null
     * for a platform connection, an environment endpoint or no connection.
     */
    fun connectionOwner(model: ModelRef): java.util.UUID? = null
}
