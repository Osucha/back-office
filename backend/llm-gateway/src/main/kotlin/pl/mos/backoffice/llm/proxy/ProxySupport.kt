package pl.mos.backoffice.llm.proxy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.JobTokenVerifier
import tools.jackson.databind.JsonNode
import java.time.Duration

/** `backoffice.llm.proxy.*`: the provider-compatible endpoints for sandboxed agents (ADR 0024). */
@ConfigurationProperties(prefix = "backoffice.llm.proxy")
data class ProxyProperties(
    val enabled: Boolean = true,
    /**
     * Upstream base URLs per provider (kebab case, e.g. `anthropic`, `openai`, `openai-compatible`); without an
     * entry: Anthropic `spring.ai.anthropic.base-url` or `https://api.anthropic.com`, OpenAI
     * `https://api.openai.com/v1`, OpenAI-compatible `backoffice.llm.openai-compatible.base-url`, Google
     * `https://generativelanguage.googleapis.com/v1beta/openai`, Mistral `https://api.mistral.ai/v1`, Ollama `<base>/v1`.
     */
    val baseUrls: Map<String, String> = emptyMap(),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    /** Whole-response limit of one proxied call (streams included). */
    val requestTimeout: Duration = Duration.ofMinutes(15),
    /** The budget pre-check assumes at most this many output tokens (or `max_tokens` when lower). */
    val estimateOutputTokensCap: Int = 4096,
    val defaultAnthropicVersion: String = "2023-06-01",
)

/**
 * Authenticates `/api/v1/llm/` with the job token (`x-api-key` as Anthropic clients send it, or
 * `Authorization: Bearer` as OpenAI clients do). Runner (`bo_rt_`), personal and CI tokens are refused.
 */
class JobTokenFilter(private val verifier: () -> JobTokenVerifier?) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.removePrefix(request.contextPath ?: "").startsWith(PREFIX)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = request.getHeader("x-api-key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (token == null || !token.startsWith(JOB_PREFIX)) return ProxyProblems.write(response, 401, "Unauthorized", "The LLM proxy needs the job token (bo_job_) as x-api-key or bearer", "invalid-token")
        val credential = verifier()?.verify(token)
            ?: return ProxyProblems.write(response, 401, "Unauthorized", "The job token is invalid, expired or the job is no longer active", "invalid-token")
        request.setAttribute(CREDENTIAL, credential)
        chain.doFilter(request, response)
    }

    companion object {
        const val PREFIX = "/api/v1/llm/"
        const val JOB_PREFIX = "bo_job_"
        const val CREDENTIAL = "pl.mos.backoffice.llm.proxy.credential"

        fun credential(request: HttpServletRequest): JobCredential =
            request.getAttribute(CREDENTIAL) as? JobCredential ?: throw IllegalStateException("no job credential on the request")
    }
}

/** RFC 9457 bodies written straight to the response (also after the filter chain, before any controller). */
object ProxyProblems {
    fun write(response: HttpServletResponse, status: Int, title: String, detail: String, code: String, extra: Map<String, Any?> = emptyMap()) {
        response.status = status
        response.contentType = "application/problem+json"
        response.characterEncoding = "UTF-8"
        val fields = linkedMapOf<String, Any?>(
            "type" to "https://back-office.dev/problems/$code", "title" to title, "status" to status, "detail" to detail, "code" to code,
        ) + extra
        response.writer.write(fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + render(v) })
        response.writer.flush()
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
    }
}

/** Collects token usage from provider responses, streamed or not. */
class UsageAccumulator(private val protocol: ProxyProtocol) {
    private var input = 0L
    private var output = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    var seen = false
        private set

    val usage: TokenUsage get() = TokenUsage(input, output, cacheRead, cacheWrite)

    /** A complete JSON response body. */
    fun fromBody(root: JsonNode) {
        root.get("usage")?.takeIf { it.isObject }?.let { apply(it, streamingDelta = false) }
    }

    /** One SSE `data:` payload. */
    fun fromEvent(root: JsonNode) {
        when (protocol) {
            ProxyProtocol.ANTHROPIC -> when (root.get("type")?.asString()) {
                "message_start" -> root.get("message")?.get("usage")?.let { apply(it, streamingDelta = false) }
                "message_delta" -> root.get("usage")?.let { apply(it, streamingDelta = true) }
            }
            ProxyProtocol.OPENAI -> root.get("usage")?.takeIf { it.isObject }?.let { apply(it, streamingDelta = false) }
        }
    }

    private fun long(node: JsonNode, name: String): Long? = node.get(name)?.takeIf { it.isNumber }?.asLong()

    private fun apply(u: JsonNode, streamingDelta: Boolean) {
        seen = true
        when (protocol) {
            ProxyProtocol.ANTHROPIC -> {
                // message_delta carries cumulative output tokens (and, in newer API versions, the input counts again)
                long(u, "input_tokens")?.let { if (!streamingDelta || it > 0) input = it }
                long(u, "output_tokens")?.let { output = it }
                long(u, "cache_read_input_tokens")?.let { if (!streamingDelta || it > 0) cacheRead = it }
                long(u, "cache_creation_input_tokens")?.let { if (!streamingDelta || it > 0) cacheWrite = it }
            }
            ProxyProtocol.OPENAI -> {
                val prompt = long(u, "prompt_tokens") ?: 0
                val cached = u.get("prompt_tokens_details")?.let { long(it, "cached_tokens") } ?: 0
                input = (prompt - cached).coerceAtLeast(0)
                cacheRead = cached
                output = long(u, "completion_tokens") ?: 0
            }
        }
    }
}

enum class ProxyProtocol { ANTHROPIC, OPENAI }
