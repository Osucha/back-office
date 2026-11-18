package pl.mos.backoffice.shared.llm

/**
 * LLM vendors supported by the gateway. `OPENAI_COMPATIBLE` covers OpenRouter, Groq, vLLM and similar.
 * [GITHUB_COPILOT] (ADR 0069) is not a gateway provider: it names the models of a GitHub Copilot subscription, which
 * only OpenCode on the user's personal runner calls, with the user's own Copilot login ([ModelRuntime.COPILOT_LOGIN]).
 */
enum class Provider {
    ANTHROPIC, OPENAI, GOOGLE, MISTRAL, OLLAMA, OPENAI_COMPATIBLE, GITHUB_COPILOT;

    /** ADR 0069: the LLM gateway can call this provider (with a connection); false for [GITHUB_COPILOT]. */
    val viaGateway: Boolean get() = this != GITHUB_COPILOT
}

/**
 * Vendor-neutral reasoning depth. The gateway maps it per provider:
 * Anthropic `effort`, OpenAI `reasoning_effort`, Gemini `thinkingLevel`, Mistral `reasoningEffort`,
 * Ollama `think`. `NONE` disables reasoning where the provider allows it.
 */
enum class Reasoning { NONE, LOW, MEDIUM, HIGH, XHIGH, MAX }

data class ModelOptions(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: Reasoning = Reasoning.MEDIUM,
    /** Provider-specific pass-through options (e.g. `"topP"`, `"stopSequences"`). */
    val extra: Map<String, Any?> = emptyMap(),
) {
    companion object {
        val DEFAULT = ModelOptions()
    }
}

/**
 * Fully resolved pointer to a model: which vendor, which model id, how to call it. [connection] (ADR 0045) names the
 * provider connection added in Back Office that serves it (several per provider type are possible, e.g. OpenRouter and
 * Groq as two OPENAI_COMPATIBLE connections); null = the first connection of [provider], else its environment keys.
 */
data class ModelRef(
    val provider: Provider,
    val modelId: String,
    val options: ModelOptions = ModelOptions.DEFAULT,
    val connection: String? = null,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /** Stable label used in ledgers, traces and the runner (`provider/modelId`). */
    val label: String get() = "${provider.name.lowercase()}/$modelId"

    fun withOptions(transform: (ModelOptions) -> ModelOptions): ModelRef = copy(options = transform(options))
}
