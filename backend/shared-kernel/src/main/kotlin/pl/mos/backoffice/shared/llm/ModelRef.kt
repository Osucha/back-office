package pl.mos.backoffice.shared.llm

/** LLM vendors supported by the gateway. `OPENAI_COMPATIBLE` covers OpenRouter, Groq, vLLM and similar. */
enum class Provider { ANTHROPIC, OPENAI, GOOGLE, MISTRAL, OLLAMA, OPENAI_COMPATIBLE }

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

/** Fully resolved pointer to a model: which vendor, which model id, how to call it. */
data class ModelRef(
    val provider: Provider,
    val modelId: String,
    val options: ModelOptions = ModelOptions.DEFAULT,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /** Stable label used in ledgers, traces and the runner (`provider/modelId`). */
    val label: String get() = "${provider.name.lowercase()}/$modelId"

    fun withOptions(transform: (ModelOptions) -> ModelOptions): ModelRef = copy(options = transform(options))
}
