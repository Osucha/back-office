package pl.mos.backoffice.llm.gateway

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.mistralai.MistralAiChatModel
import org.springframework.ai.mistralai.MistralAiChatOptions
import org.springframework.ai.mistralai.api.MistralAiApi
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import pl.mos.backoffice.shared.llm.Provider
import java.time.Duration

/** Creates the Spring AI `ChatModel` of a provider from server settings. Swapped for a fake in tests. */
fun interface ChatModelFactory {
    fun create(provider: Provider, settings: ProviderSettings): ChatModel
}

/**
 * Builds one `ChatModel` per provider explicitly (ADR 0003: `spring.ai.model.chat=none` disables the
 * auto-configured default so several vendors coexist). Model id and options are supplied per call.
 */
class SpringAiChatModelFactory(
    private val observationRegistry: ObservationRegistry,
    private val timeout: Duration,
) : ChatModelFactory {

    override fun create(provider: Provider, settings: ProviderSettings): ChatModel = when (provider) {
        Provider.ANTHROPIC -> AnthropicChatModel.builder()
            .options(
                AnthropicChatOptions.builder()
                    .apiKey(settings.apiKey)
                    .also { b -> settings.baseUrl?.takeIf { it.isNotBlank() }?.let { b.baseUrl(it) } }
                    .timeout(timeout)
                    .maxRetries(0)
                    .model(PLACEHOLDER_MODEL)
                    .build(),
            )
            .observationRegistry(observationRegistry)
            .build()

        Provider.OPENAI, Provider.OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .apiKey(settings.apiKey)
                    .also { b -> settings.baseUrl?.takeIf { it.isNotBlank() }?.let { b.baseUrl(it) } }
                    .timeout(timeout)
                    .maxRetries(0)
                    .model(PLACEHOLDER_MODEL)
                    .build(),
            )
            .observationRegistry(observationRegistry)
            .build()

        Provider.GOOGLE -> GoogleGenAiChatModel.builder()
            .genAiClient(Client.builder().apiKey(settings.apiKey).build())
            .options(GoogleGenAiChatOptions.builder().model(PLACEHOLDER_MODEL).build())
            .observationRegistry(observationRegistry)
            .build()

        Provider.MISTRAL -> MistralAiChatModel.builder()
            .mistralAiApi(
                MistralAiApi.builder()
                    .apiKey(settings.apiKey ?: "")
                    .also { b -> settings.baseUrl?.takeIf { it.isNotBlank() }?.let { b.baseUrl(it) } }
                    .build(),
            )
            .options(MistralAiChatOptions.builder().model(PLACEHOLDER_MODEL).build())
            .observationRegistry(observationRegistry)
            .build()

        Provider.OLLAMA -> OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(settings.baseUrl ?: DEFAULT_OLLAMA_URL).build())
            .options(OllamaChatOptions.builder().model(PLACEHOLDER_MODEL).build())
            .observationRegistry(observationRegistry)
            .build()
    }

    companion object {
        /** Never sent: every call carries its own model id in the prompt options. */
        const val PLACEHOLDER_MODEL = "unset"
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    }
}
