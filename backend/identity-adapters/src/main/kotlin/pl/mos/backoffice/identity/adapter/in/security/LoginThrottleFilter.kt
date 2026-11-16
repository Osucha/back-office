package pl.mos.backoffice.identity.adapter.`in`.security

import com.osucha.portpermissions.AccessGuard
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.identity.adapter.`in`.web.InMemoryThrottleAuditMemory
import pl.mos.backoffice.identity.adapter.`in`.web.LoginThrottle
import pl.mos.backoffice.identity.adapter.`in`.web.ProblemResponses
import pl.mos.backoffice.identity.adapter.`in`.web.ThrottleAuditMemory
import pl.mos.backoffice.identity.application.port.`in`.system.RecordAuthEvent

/**
 * Per-source-IP throttling of `POST /api/v1/auth/login`, `/auth/login/mfa` and `/auth/password/reset/{token}`
 * (ADR 0021): over the limit the request is answered `429 too-many-attempts` with `Retry-After` (seconds).
 * The first refused request of a burst is audited as `LOGIN_FAILURE` with reason `throttled`.
 */
class LoginThrottleFilter(
    private val throttle: LoginThrottle,
    private val problems: ProblemResponses,
    private val authEvents: RecordAuthEvent,
    private val guard: AccessGuard,
    private val audits: ThrottleAuditMemory = InMemoryThrottleAuditMemory(),
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(LoginThrottleFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return true
        val path = request.requestURI.removePrefix(request.contextPath)
        return !(path == LOGIN || path == LOGIN_MFA || path.startsWith(RESET_PREFIX))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val ip = request.remoteAddr ?: "unknown"
        val wait = throttle.tryAcquire(ip) ?: return chain.doFilter(request, response)
        val seconds = (wait.toMillis() + MILLIS - 1) / MILLIS
        val path = request.requestURI.removePrefix(request.contextPath)
        if (audits.firstOfBurst(ip, wait)) {
            runCatching { guard.asSystem("authentication: throttled login") { authEvents.loginThrottled(path.replace(Regex("/reset/.*"), "/reset/{token}")) } }
                .onFailure { log.warn("could not audit throttled login: {}", it.toString()) }
        }
        response.setHeader("Retry-After", seconds.toString())
        problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", "Too many sign-in attempts from this address; try again in $seconds seconds", mapOf("code" to "too-many-attempts"))
    }

    companion object {
        const val LOGIN = "/api/v1/auth/login"
        const val LOGIN_MFA = "/api/v1/auth/login/mfa"
        const val RESET_PREFIX = "/api/v1/auth/password/reset/"
        private const val MILLIS = 1000L
        private const val AUDIT_MEMORY = 10_000
    }
}
