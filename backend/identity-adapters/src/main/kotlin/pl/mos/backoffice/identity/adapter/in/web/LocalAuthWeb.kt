package pl.mos.backoffice.identity.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.identity.adapter.out.security.SpringSessionTerminator
import pl.mos.backoffice.identity.application.port.`in`.RecordAuthEvent
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.shared.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates the server-side session of a local login exactly like the OIDC success handler does (ADR 0021):
 * a **new** session (the old one, if any, is invalidated: session fixation), the user id, the login time,
 * the method, the principal-name index and the client metadata; plus the restriction flag when the user
 * must first change the password or enrol TOTP. The CSRF token is rotated.
 */
class LoginSessions(private val clock: Clock, private val csrf: CsrfTokenRepository) {

    fun establish(request: HttpServletRequest, response: HttpServletResponse, user: User, method: LoginMethod, restriction: SessionRestriction?) {
        request.getSession(false)?.let { runCatching { it.invalidate() } }
        val session = request.getSession(true)
        session.setAttribute(SessionAttributes.USER_ID, user.id.toString())
        session.setAttribute(SessionAttributes.AUTH_TIME, clock.now().toEpochMilli())
        session.setAttribute(SessionAttributes.AUTH_METHOD, method.name)
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, user.id.toString())
        session.setAttribute(SpringSessionTerminator.SOURCE_IP_ATTRIBUTE, request.remoteAddr)
        session.setAttribute(SpringSessionTerminator.USER_AGENT_ATTRIBUTE, request.getHeader("User-Agent")?.take(USER_AGENT_MAX))
        if (restriction != null) session.setAttribute(SessionAttributes.RESTRICTION, restriction.name)
        SecurityContextHolder.clearContext()
        csrf.saveToken(csrf.generateToken(request), request, response)
    }

    fun restrictionOf(request: HttpServletRequest): SessionRestriction? =
        (request.getSession(false)?.getAttribute(SessionAttributes.RESTRICTION) as? String)?.let { runCatching { SessionRestriction.valueOf(it) }.getOrNull() }

    /** Replaces (or clears, with `null`) the restriction of the caller's session. */
    fun restrict(request: HttpServletRequest, restriction: SessionRestriction?) {
        val session = request.getSession(false) ?: return
        if (restriction == null) session.removeAttribute(SessionAttributes.RESTRICTION) else session.setAttribute(SessionAttributes.RESTRICTION, restriction.name)
    }

    private companion object {
        const val USER_AGENT_MAX = 512
    }
}

/**
 * What a restricted session may call (everything outside `/api/` stays reachable: the SPA itself). The
 * principal filter answers any other API call with `403 password-change-required` / `mfa-enrolment-required`.
 */
object RestrictedSession {
    private val ALWAYS = setOf("/api/v1/me", "/api/v1/me/security", "/api/v1/auth/logout", "/api/v1/auth/config", "/api/v1/auth/session")

    fun allows(restriction: SessionRestriction, path: String): Boolean {
        if (!path.startsWith("/api/")) return true
        if (path in ALWAYS) return true
        return when (restriction) {
            SessionRestriction.PASSWORD_CHANGE -> path == "/api/v1/auth/password/change"
            SessionRestriction.MFA_ENROLMENT -> path.startsWith("/api/v1/me/mfa/")
        }
    }

    fun code(restriction: SessionRestriction): String = when (restriction) {
        SessionRestriction.PASSWORD_CHANGE -> "password-change-required"
        SessionRestriction.MFA_ENROLMENT -> "mfa-enrolment-required"
    }

    fun detail(restriction: SessionRestriction): String = when (restriction) {
        SessionRestriction.PASSWORD_CHANGE -> "Change your password first (POST /api/v1/auth/password/change)"
        SessionRestriction.MFA_ENROLMENT -> "Set up two-factor authentication first (POST /api/v1/me/mfa/totp)"
    }
}

/** In-memory sliding window per key: at most [maxAttempts] within [window]. Thread-safe; per replica. */
class SlidingWindowThrottle(private val maxAttempts: Int, private val window: Duration, private val clock: Clock) : LoginThrottle {
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Records an attempt; returns `null` when allowed, otherwise how long to wait. */
    override fun tryAcquire(key: String): Duration? {
        val now = clock.now().toEpochMilli()
        val windowMillis = window.toMillis()
        if (attempts.size > CLEANUP_THRESHOLD) cleanup(now - windowMillis)
        val queue = attempts.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.peekFirst() <= now - windowMillis) queue.pollFirst()
            if (queue.size >= maxAttempts) {
                val waitMillis = queue.peekFirst() + windowMillis - now
                return Duration.ofMillis(waitMillis.coerceAtLeast(1))
            }
            queue.addLast(now)
            return null
        }
    }

    override fun reset() = attempts.clear()

    private fun cleanup(threshold: Long) {
        attempts.entries.removeIf { (_, queue) -> synchronized(queue) { queue.isEmpty() || queue.peekLast() <= threshold } }
    }

    private companion object {
        const val CLEANUP_THRESHOLD = 10_000
    }
}

/**
 * Per-source-IP throttling of `POST /api/v1/auth/login`, `/auth/login/mfa` and `/auth/password/reset/{token}`
 * (ADR 0021): over the limit the request is answered `429 too-many-attempts` with `Retry-After` (seconds).
 * The first refused request of a burst is audited as `LOGIN_FAILURE` with reason `throttled`.
 */
class LoginThrottleFilter(
    private val throttle: LoginThrottle,
    private val problems: ProblemResponses,
    private val authEvents: RecordAuthEvent,
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
            runCatching { authEvents.loginThrottled(path.replace(Regex("/reset/.*"), "/reset/{token}")) }
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
