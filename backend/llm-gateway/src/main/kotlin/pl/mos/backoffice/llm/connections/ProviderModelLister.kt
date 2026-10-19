package pl.mos.backoffice.llm.connections

import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.shared.llm.Provider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Lists the models a provider endpoint offers (ADR 0045): the connection test of the UI and a help to pick model ids
 * for profiles. Each provider's own list endpoint: Anthropic `/v1/models`, Ollama `/api/tags`, the rest the
 * OpenAI-style `/models` (Gemini through its OpenAI-compatible URL).
 */
class ProviderModelLister(
    private val json: JsonMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(20),
) {

    fun list(provider: Provider, settings: ProviderSettings): ProviderModels {
        val request = request(provider, settings)
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return ProviderModels(false, emptyList(), "HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
            val infos = infos(provider, json.readTree(response.body())).sortedBy { it.id }
            ProviderModels(true, infos.map { it.id }, null, infos)
        } catch (e: java.io.IOException) {
            ProviderModels(false, emptyList(), "${request.uri().host}: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ProviderModels(false, emptyList(), "interrupted")
        } catch (e: RuntimeException) {
            ProviderModels(false, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    private fun request(provider: Provider, settings: ProviderSettings): HttpRequest {
        val own = settings.baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val url = when (provider) {
            Provider.ANTHROPIC -> (own ?: "https://api.anthropic.com") + "/v1/models?limit=1000"
            Provider.OPENAI -> (own ?: "https://api.openai.com/v1") + "/models"
            Provider.MISTRAL -> (own ?: "https://api.mistral.ai/v1") + "/models"
            Provider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai/models"
            Provider.OPENAI_COMPATIBLE -> (own ?: throw IllegalArgumentException("no base URL")) + "/models"
            Provider.OLLAMA -> (own ?: "http://localhost:11434") + "/api/tags"
        }
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().header("accept", "application/json")
        val key = settings.apiKey?.takeIf { it.isNotBlank() }
        when (provider) {
            Provider.ANTHROPIC -> {
                key?.let { builder.header("x-api-key", it) }
                builder.header("anthropic-version", "2023-06-01")
            }
            else -> key?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder.build()
    }

    /**
     * Model entries with what the provider tells about them: OpenRouter-style `pricing` (USD per token, turned into per
     * million), `context_length` / `max_context_length` / `context_window`, Anthropic `display_name`. Local Ollama
     * models cost nothing. Missing data stays null.
     */
    private fun infos(provider: Provider, body: JsonNode): List<ProviderModelInfo> {
        val items = if (provider == Provider.OLLAMA) body.get("models") else body.get("data")
        if (items == null || !items.isArray) return emptyList()
        return items.mapNotNull { item ->
            val raw = (if (provider == Provider.OLLAMA) item.get("name") else item.get("id"))?.asString()
            val id = raw?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pricing = item.get("pricing")
            val input = perMillion(pricing?.get("prompt"))
            val output = perMillion(pricing?.get("completion"))
            val local = provider == Provider.OLLAMA
            ProviderModelInfo(
                id = id,
                name = (item.get("name") ?: item.get("display_name"))?.asString()?.takeIf { it.isNotBlank() && it != id },
                contextWindow = listOf("context_length", "max_context_length", "context_window")
                    .firstNotNullOfOrNull { k -> item.get(k)?.takeIf { it.isNumber }?.asInt() },
                inputPerMTok = if (local) java.math.BigDecimal.ZERO else input,
                outputPerMTok = if (local) java.math.BigDecimal.ZERO else output,
                free = local || id.endsWith(":free") || (input != null && output != null && input.signum() == 0 && output.signum() == 0),
            )
        }.distinctBy { it.id }
    }

    private fun perMillion(node: JsonNode?): java.math.BigDecimal? {
        val text = node?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val perToken = text.toBigDecimalOrNull() ?: return null
        if (perToken.signum() < 0) return null
        return perToken.multiply(java.math.BigDecimal(1_000_000)).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    }
}
