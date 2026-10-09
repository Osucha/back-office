package pl.mos.backoffice.llm.proxy

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Provider-compatible endpoints for sandboxed agents (openapi tag `llm-proxy`), authenticated by
 * [JobTokenFilter]. Claude Code: `ANTHROPIC_BASE_URL=<api>/api/v1/llm/anthropic`; OpenCode:
 * `baseURL=<api>/api/v1/llm/anthropic/v1` and `<api>/api/v1/llm/openai/v1`.
 */
@RestController
@RequestMapping("/api/v1/llm")
class LlmProxyController(private val proxy: LlmProxy) {

    @PostMapping("/anthropic/v1/messages")
    fun messages(request: HttpServletRequest, response: HttpServletResponse, @RequestBody body: ByteArray) =
        proxy.anthropicMessages(JobTokenFilter.credential(request), request, response, body)

    @PostMapping("/anthropic/v1/messages/count_tokens")
    fun countTokens(request: HttpServletRequest, response: HttpServletResponse, @RequestBody body: ByteArray) =
        proxy.anthropicCountTokens(JobTokenFilter.credential(request), request, response, body)

    @PostMapping("/openai/v1/chat/completions")
    fun chatCompletions(request: HttpServletRequest, response: HttpServletResponse, @RequestBody body: ByteArray) =
        proxy.openAiChat(JobTokenFilter.credential(request), request, response, body)

    @GetMapping("/openai/v1/models")
    fun models(request: HttpServletRequest): Map<String, Any?> = proxy.openAiModels(JobTokenFilter.credential(request))
}
