package pl.mos.backoffice.llm.gateway

import com.anthropic.models.messages.OutputConfig
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel
import org.springframework.ai.mistralai.MistralAiChatOptions
import org.springframework.ai.mistralai.api.MistralAiApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning

/**
 * Maps the vendor-neutral [ModelOptions] onto provider-specific Spring AI options
 * (docs/architecture/05-integrations.md, "LLM providers"):
 *
 * | provider | max tokens | reasoning | structured output |
 * |---|---|---|---|
 * | Anthropic | `maxTokens` | adaptive thinking + `effort`; `NONE` disables thinking on models that allow it | `outputSchema` |
 * | OpenAI / compatible | `maxCompletionTokens` | `reasoningEffort` low/medium/high | `response_format` json_schema / json_object |
 * | Google | `maxOutputTokens` | `thinkingLevel` | `responseMimeType=application/json` |
 * | Mistral | `maxTokens` | `reasoningEffort` HIGH/NONE | `response_format` json_object |
 * | Ollama | `numPredict` | `think` on/off | `format=json` |
 *
 * Temperature is never sent while reasoning/thinking is on; reasoning models reject it.
 * `extra` keys `topP`, `topK`, `stopSequences`, `frequencyPenalty`, `presencePenalty` pass through.
 */
class ChatOptionsMapper(
    private val defaultMaxOutputTokens: Int,
    private val nativeStructuredOutput: Boolean,
) {

    fun map(model: ModelRef, options: ModelOptions, schema: String?): ChatOptions {
        val maxTokens = options.maxOutputTokens ?: defaultMaxOutputTokens
        val reasoning = options.reasoning
        val useSchema = schema != null && nativeStructuredOutput
        return when (model.provider) {
            Provider.ANTHROPIC -> anthropic(model.modelId, options, maxTokens, reasoning, schema.takeIf { useSchema })
            Provider.OPENAI -> openAi(model.modelId, options, maxTokens, reasoning, schema.takeIf { useSchema }, strictSchema = true)
            Provider.OPENAI_COMPATIBLE -> openAi(model.modelId, options, maxTokens, reasoning, schema.takeIf { useSchema }, strictSchema = false)
            Provider.GOOGLE -> google(model.modelId, options, maxTokens, reasoning, useSchema)
            Provider.MISTRAL -> mistral(model.modelId, options, maxTokens, reasoning, useSchema)
            Provider.OLLAMA -> ollama(model.modelId, options, maxTokens, reasoning, useSchema)
        }
    }

    private fun anthropic(modelId: String, options: ModelOptions, maxTokens: Int, reasoning: Reasoning, schema: String?): AnthropicChatOptions {
        val b = AnthropicChatOptions.builder().model(modelId).maxTokens(maxTokens)
        val thinkingOff = reasoning == Reasoning.NONE && allowsThinkingDisabled(modelId)
        if (thinkingOff) {
            b.thinkingDisabled()
            options.temperature?.let { b.temperature(it) }
        } else {
            b.thinkingAdaptive()
            b.effort(anthropicEffort(reasoning))
        }
        schema?.let { b.outputSchema(it) }
        passThrough(options, b)
        return b.build()
    }

    private fun openAi(modelId: String, options: ModelOptions, maxTokens: Int, reasoning: Reasoning, schema: String?, strictSchema: Boolean): OpenAiChatOptions {
        val b = OpenAiChatOptions.builder().model(modelId).maxCompletionTokens(maxTokens)
        if (reasoning == Reasoning.NONE) {
            options.temperature?.let { b.temperature(it) }
        } else {
            b.reasoningEffort(openAiEffort(reasoning))
        }
        if (schema != null) {
            val format = if (strictSchema) {
                OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schema).strict(false).build()
            } else {
                OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build()
            }
            b.responseFormat(format)
        }
        passThrough(options, b)
        return b.build()
    }

    private fun google(modelId: String, options: ModelOptions, maxTokens: Int, reasoning: Reasoning, json: Boolean): GoogleGenAiChatOptions {
        val b = GoogleGenAiChatOptions.builder().model(modelId).maxOutputTokens(maxTokens).thinkingLevel(googleThinking(reasoning))
        if (reasoning == Reasoning.NONE) options.temperature?.let { b.temperature(it) }
        if (json) b.responseMimeType("application/json")
        passThrough(options, b)
        return b.build()
    }

    private fun mistral(modelId: String, options: ModelOptions, maxTokens: Int, reasoning: Reasoning, json: Boolean): MistralAiChatOptions {
        val b = MistralAiChatOptions.builder().model(modelId).maxTokens(maxTokens)
        when (reasoning) {
            Reasoning.NONE -> {
                b.reasoningEffort(MistralAiApi.ChatCompletionRequest.ReasoningEffort.NONE)
                options.temperature?.let { b.temperature(it) }
            }
            Reasoning.HIGH, Reasoning.XHIGH, Reasoning.MAX -> b.reasoningEffort(MistralAiApi.ChatCompletionRequest.ReasoningEffort.HIGH)
            Reasoning.LOW, Reasoning.MEDIUM -> Unit
        }
        if (json) b.responseFormat(MistralAiApi.ChatCompletionRequest.ResponseFormat.jsonObject())
        passThrough(options, b)
        return b.build()
    }

    private fun ollama(modelId: String, options: ModelOptions, maxTokens: Int, reasoning: Reasoning, json: Boolean): OllamaChatOptions {
        val b = OllamaChatOptions.builder().model(modelId).numPredict(maxTokens)
        if (reasoning == Reasoning.NONE) {
            b.disableThinking()
            options.temperature?.let { b.temperature(it) }
        } else {
            b.enableThinking()
        }
        if (json) b.format("json")
        passThrough(options, b)
        return b.build()
    }

    private fun passThrough(options: ModelOptions, b: ChatOptions.Builder<*>) {
        (options.extra["topP"] as? Number)?.let { b.topP(it.toDouble()) }
        (options.extra["topK"] as? Number)?.let { b.topK(it.toInt()) }
        (options.extra["frequencyPenalty"] as? Number)?.let { b.frequencyPenalty(it.toDouble()) }
        (options.extra["presencePenalty"] as? Number)?.let { b.presencePenalty(it.toDouble()) }
        (options.extra["stopSequences"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }?.let { b.stopSequences(it) }
    }

    companion object {
        /** Fable and Opus 5 always think (adaptive); disabling is only honoured on Haiku and older models. */
        fun allowsThinkingDisabled(modelId: String): Boolean {
            val id = modelId.lowercase()
            return !(id.startsWith("claude-fable") || id.startsWith("claude-opus-5"))
        }

        fun anthropicEffort(reasoning: Reasoning): OutputConfig.Effort = when (reasoning) {
            Reasoning.NONE, Reasoning.LOW -> OutputConfig.Effort.LOW
            Reasoning.MEDIUM -> OutputConfig.Effort.MEDIUM
            Reasoning.HIGH -> OutputConfig.Effort.HIGH
            Reasoning.XHIGH -> OutputConfig.Effort.XHIGH
            Reasoning.MAX -> OutputConfig.Effort.MAX
        }

        fun openAiEffort(reasoning: Reasoning): String = when (reasoning) {
            Reasoning.NONE, Reasoning.LOW -> "low"
            Reasoning.MEDIUM -> "medium"
            Reasoning.HIGH, Reasoning.XHIGH, Reasoning.MAX -> "high"
        }

        fun googleThinking(reasoning: Reasoning): GoogleGenAiThinkingLevel = when (reasoning) {
            Reasoning.NONE -> GoogleGenAiThinkingLevel.MINIMAL
            Reasoning.LOW -> GoogleGenAiThinkingLevel.LOW
            Reasoning.MEDIUM -> GoogleGenAiThinkingLevel.MEDIUM
            Reasoning.HIGH, Reasoning.XHIGH, Reasoning.MAX -> GoogleGenAiThinkingLevel.HIGH
        }
    }
}
