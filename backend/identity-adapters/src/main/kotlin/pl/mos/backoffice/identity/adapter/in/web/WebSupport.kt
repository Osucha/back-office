package pl.mos.backoffice.identity.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.identity.adapter.out.persistence.AuditOrigin
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Session attribute names written at login (ADR 0016). The session holds ids and times, never permissions. */
object SessionAttributes {
    const val USER_ID = "BO_USER_ID"
    /** Login time, epoch milliseconds; the absolute timeout is measured from it. */
    const val AUTH_TIME = "BO_AUTH_TIME"
    /** Raw ID token for the RP-initiated logout (`id_token_hint`). */
    const val ID_TOKEN = "BO_ID_TOKEN"
    /** Validated relative path to return to after the login. */
    const val RETURN_TO = "BO_RETURN_TO"
    /** `LOCAL` or `OIDC` (ADR 0021). */
    const val AUTH_METHOD = "BO_AUTH_METHOD"
    /** A `SessionRestriction` name while the user must change the password or enrol TOTP first. */
    const val RESTRICTION = "BO_RESTRICTION"
}

/**
 * Generates a correlation id for every request (never taken from the client), puts it in the MDC and a
 * request attribute, and returns it as `X-Correlation-Id`. Runs at the highest precedence and also on error
 * dispatches, reusing the id of the original request.
 */
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = request.getAttribute(AuditOrigin.MDC_KEY) as? String ?: UUID.randomUUID().toString().also { request.setAttribute(AuditOrigin.MDC_KEY, it) }
        val previous = MDC.get(AuditOrigin.MDC_KEY)
        MDC.put(AuditOrigin.MDC_KEY, id)
        if (!response.containsHeader(HEADER)) response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            if (previous == null) MDC.remove(AuditOrigin.MDC_KEY) else MDC.put(AuditOrigin.MDC_KEY, previous)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
    }
}

/** RFC 9457 bodies written by filters (before Spring MVC, so no `@ExceptionHandler` applies). API calls never redirect. */
class ProblemResponses(private val json: JsonMapper) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) =
        write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required: log in or send a valid bearer token", mapOf("code" to "unauthenticated"))

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        if (accessDeniedException is CsrfException) {
            write(response, HttpStatus.FORBIDDEN, "Forbidden", "CSRF token missing or invalid: send the X-XSRF-TOKEN header with the XSRF-TOKEN cookie value", mapOf("code" to "csrf"))
        } else {
            write(response, HttpStatus.FORBIDDEN, "Forbidden", "Access denied", mapOf("code" to "forbidden"))
        }
    }

    fun unauthorized(response: HttpServletResponse, detail: String, code: String = "unauthenticated") =
        write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", detail, mapOf("code" to code))

    fun write(response: HttpServletResponse, status: HttpStatus, title: String, detail: String, extra: Map<String, Any?> = emptyMap()) {
        if (response.isCommitted) return
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        val body = linkedMapOf<String, Any?>("type" to "about:blank", "title" to title, "status" to status.value(), "detail" to detail) + extra
        response.writer.write(json.writeValueAsString(body))
    }
}

/** Materialises the deferred CSRF token so the `XSRF-TOKEN` cookie is set on every response (SPA mode). */
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        chain.doFilter(request, response)
    }
}

/** Only same-origin relative paths are accepted as `returnTo` (no open redirects). */
object SafeRedirect {
    private val SAFE = Regex("^/(?![/\\\\])[A-Za-z0-9\\-._~!$&()*+,;=:@%/?#\\[\\]]*$")

    fun isSafe(path: String?): Boolean = path != null && path.length <= 2000 && SAFE.matches(path)
}
