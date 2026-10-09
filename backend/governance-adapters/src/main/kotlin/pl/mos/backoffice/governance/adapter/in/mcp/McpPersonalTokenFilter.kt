package pl.mos.backoffice.governance.adapter.`in`.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `/mcp` accepts personal access tokens only (`Authorization: Bearer bo_pat_...`). Runs before the security
 * chain: requests with a session cookie, a CI token or nothing are answered with 401 here, so the endpoint (CSRF
 * exempt, like every bearer request) can never be driven by a browser session. The identity bearer filter then
 * authenticates the token and the tools check permissions through `AuthorizationGate`.
 */
class McpPersonalTokenFilter(private val endpoint: String) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return !(path == endpoint || path.startsWith("$endpoint/"))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return chain.doFilter(request, response)
        val authorization = request.getHeader("Authorization")
        val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (token == null || !token.startsWith(PAT_PREFIX)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.setHeader("WWW-Authenticate", "Bearer realm=\"back-office\", error=\"invalid_token\"")
            response.contentType = "application/problem+json"
            response.writer.write(
                """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"The MCP endpoint accepts personal access tokens only (Authorization: Bearer bo_pat_...)","code":"personal-token-required"}""",
            )
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val PAT_PREFIX = "bo_pat_"
    }
}
