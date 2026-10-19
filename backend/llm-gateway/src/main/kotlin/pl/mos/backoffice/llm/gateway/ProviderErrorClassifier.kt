package pl.mos.backoffice.llm.gateway

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.google.genai.errors.ApiException
import com.google.genai.errors.GenAiIOException
import com.google.genai.errors.ServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Turns SDK- and transport-specific failures into one verdict: message plus whether a retry may help
 * (HTTP 429, 5xx, connection problems). Walks the cause chain because Spring AI wraps SDK errors.
 */
object ProviderErrorClassifier {

    /** Message of a retryable transport failure that carries no message of its own. */
    private const val IO_ERROR = "I/O error"

    /** [persistent]: the same call keeps failing until somebody acts (key, access, model id, a daily quota used up). */
    data class Verdict(val message: String, val retryable: Boolean, val status: Int? = null, val persistent: Boolean = false)

    /** A 429 that will not clear within minutes: a daily or monthly quota, or no credit left (e.g. OpenRouter free-models-per-day). */
    private val QUOTA = Regex("(?i)per[-_ ]?day|daily|quota|insufficient[_ ]credits|billing")

    fun classify(error: Throwable): Verdict {
        var current: Throwable? = error
        while (current != null) {
            specific(current)?.let { return it }
            current = current.cause?.takeIf { it !== current }
        }
        return Verdict(error.message ?: error.javaClass.simpleName, retryable = false)
    }

    private fun specific(e: Throwable): Verdict? = when (e) {
        is AnthropicServiceException -> status(e.statusCode(), e.message)
        is AnthropicRetryableException, is AnthropicIoException -> Verdict(e.message ?: IO_ERROR, retryable = true)
        is OpenAIServiceException -> status(e.statusCode(), e.message)
        is OpenAIRetryableException, is OpenAIIoException -> Verdict(e.message ?: IO_ERROR, retryable = true)
        is ServerException -> status(e.code(), e.message())
        is ApiException -> status(e.code(), e.message())
        is GenAiIOException -> Verdict(e.message ?: IO_ERROR, retryable = true)
        is HttpStatusCodeException -> status(e.statusCode.value(), e.responseBodyAsString.ifBlank { e.statusText })
        is ResourceAccessException -> Verdict("connection failed: ${e.message}", retryable = true)
        is TransientAiException -> Verdict(e.message ?: "transient provider error", retryable = true)
        is NonTransientAiException -> Verdict(e.message ?: "provider error", retryable = false)
        is TimeoutException -> Verdict(e.message ?: "timed out", retryable = false)
        is ConnectException, is SocketTimeoutException -> Verdict("connection failed: ${e.message}", retryable = true)
        is IOException -> Verdict(e.message ?: IO_ERROR, retryable = true)
        else -> null
    }

    fun status(status: Int, message: String?): Verdict {
        val quota = status == 429 && message != null && QUOTA.containsMatchIn(message)
        val hint = when (status) {
            401 -> "authentication failed, check the API key"
            402 -> "payment required, the account has no credit"
            403 -> "forbidden, the key lacks access to this model or organisation"
            404 -> "not found, check the model id"
            429 -> if (quota) "quota used up, it resets later" else "rate limited"
            in 500..599 -> "provider error"
            else -> "request rejected"
        }
        val detail = message?.take(500)?.takeIf { it.isNotBlank() }
        return Verdict(
            "HTTP $status ($hint)" + (detail?.let { ": $it" } ?: ""),
            retryable = (status == 429 && !quota) || status >= 500,
            status = status,
            persistent = quota || status in setOf(401, 402, 403, 404),
        )
    }
}
