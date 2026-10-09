package pl.mos.backoffice.identity.adapter.`in`.rest

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.session.web.http.CookieSerializer
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import pl.mos.backoffice.identity.adapter.config.IdentityProperties
import pl.mos.backoffice.identity.adapter.config.IdentityWebValues
import pl.mos.backoffice.identity.adapter.config.LazyOidcClientRegistrationRepository
import pl.mos.backoffice.identity.adapter.`in`.web.SessionAttributes
import pl.mos.backoffice.identity.adapter.out.security.SecurityContextAuthorizationGate
import pl.mos.backoffice.identity.application.port.`in`.GetMe
import pl.mos.backoffice.identity.application.port.`in`.GetLoginOptions
import pl.mos.backoffice.identity.application.port.`in`.GetRegistrationMode
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.application.port.`in`.RecordAuthEvent
import pl.mos.backoffice.identity.application.port.`in`.UpdateMyPreferences
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import java.time.Instant

/**
 * Reads a session **without touching it**: Spring Session's `request.getSession(false)` sets
 * `lastAccessedTime` and saves it on commit, which would extend the idle timeout. The inspector resolves
 * the session id from the `BO_SESSION` cookie and loads it from the repository; `findById` does not save.
 */
class SessionInspector(
    private val cookies: CookieSerializer,
    private val sessions: ObjectProvider<FindByIndexNameSessionRepository<out Session>>,
) {
    fun peek(request: HttpServletRequest): Session? {
        val repository = sessions.getIfAvailable() ?: return null
        return cookies.readCookieValues(request).asSequence().mapNotNull { id -> runCatching { repository.findById(id) }.getOrNull() }
            .firstOrNull { !it.isExpired }
    }
}

@RestController
@RequestMapping("/api/v1")
class MeController(
    private val getMe: GetMe,
    private val updatePreferences: UpdateMyPreferences,
    private val properties: IdentityProperties,
) {
    @GetMapping("/me")
    fun me(request: HttpServletRequest): MeDto {
        val view = getMe.me()
        val authTime = request.getSession(false)?.getAttribute(SessionAttributes.AUTH_TIME) as? Long
        return MeDto(
            user = UserRefDto.of(view.user),
            kind = view.current.kind.name,
            platformPermissions = view.current.platformPermissions.sorted(),
            allProjects = view.current.allProjects,
            projects = view.projects.map(ProjectAccessDto::of),
            groups = view.groups.map(GroupRefDto::of),
            preferences = PreferencesDto.of(view.user.preferences),
            idleTimeoutSeconds = properties.session.idleTimeout.seconds,
            absoluteExpiresAt = authTime?.let { Instant.ofEpochMilli(it).plus(properties.session.absoluteTimeout) },
            authenticatedAt = authTime?.let(Instant::ofEpochMilli),
        )
    }

    @PutMapping("/me/preferences")
    fun preferences(@RequestBody body: PreferencesDto): PreferencesDto = PreferencesDto.of(updatePreferences.update(body.toDomain()))
}

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val registration: GetRegistrationMode,
    private val loginOptions: GetLoginOptions,
    private val authEvents: RecordAuthEvent,
    private val gate: SecurityContextAuthorizationGate,
    private val properties: IdentityProperties,
    private val web: IdentityWebValues,
    private val clients: ClientRegistrationRepository,
    private val inspector: SessionInspector,
) {
    /** Public. Also hands out the `XSRF-TOKEN` cookie the SPA needs for `POST /auth/login` (CsrfCookieFilter). */
    @GetMapping("/config")
    fun config(): AuthConfigDto {
        val options = loginOptions.loginOptions()
        return AuthConfigDto(
            loginUrl = if (properties.oidc.enabled) "/oauth2/authorization/${IdentityProperties.REGISTRATION_ID}" else null,
            providerName = properties.oidc.providerName,
            registration = registration.registrationMode(),
            accountUrl = properties.accountUrl(),
            methods = AuthMethodsDto(local = options.localLoginEnabled, oidc = properties.oidc.enabled),
            passwordPolicy = PasswordPolicyDto.of(options.passwordPolicy),
        )
    }

    /**
     * Invalidates the session and returns the provider's end-session URL (RP-initiated logout with
     * `id_token_hint` and `post_logout_redirect_uri = backoffice.public-url`). Works without a session too.
     */
    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): LogoutDto {
        val session = request.getSession(false)
        val idToken = session?.getAttribute(SessionAttributes.ID_TOKEN) as? String
        val local = session?.getAttribute(SessionAttributes.AUTH_METHOD) == LoginMethod.LOCAL.name
        gate.currentUserOrNull()?.let { user -> runCatching { authEvents.loggedOut(user) } }
        runCatching { session?.invalidate() }
        SecurityContextHolder.clearContext()
        // a local session has nothing to end at the identity provider
        if (session == null || local) return LogoutDto(null)
        val endSession = (clients as? LazyOidcClientRegistrationRepository)?.metadata("end_session_endpoint") ?: return LogoutDto(null)
        val url = UriComponentsBuilder.fromUriString(endSession)
            .queryParam("client_id", properties.oidc.clientId)
            .queryParam("post_logout_redirect_uri", web.publicUrl)
            .apply { if (idToken != null) queryParam("id_token_hint", idToken) }
            .encode()
            .build()
            .toUriString()
        return LogoutDto(url)
    }

    /** Remaining idle and absolute time; does not extend the session (see [SessionInspector]). */
    @GetMapping("/session")
    fun session(request: HttpServletRequest): SessionInfoDto {
        val session = inspector.peek(request)
        if (session?.getAttribute<String>(SessionAttributes.USER_ID) == null) throw AuthenticationCredentialsNotFoundException("no active session")
        val authTime = session.getAttribute<Long>(SessionAttributes.AUTH_TIME)
        return SessionInfoDto(
            idleExpiresAt = session.lastAccessedTime.plus(session.maxInactiveInterval),
            absoluteExpiresAt = authTime?.let { Instant.ofEpochMilli(it).plus(properties.session.absoluteTimeout) },
            idleTimeoutSeconds = session.maxInactiveInterval.seconds,
        )
    }
}

/**
 * Answers `GET /api/v1/auth/session` **before** Spring Session's `SessionRepositoryFilter` wraps the request,
 * so no component further down can call `request.getSession()` and thereby bump `lastAccessedTime`
 * (which would make the UI countdown extend the very session it is counting down).
 */
class SessionInfoFilter(
    private val inspector: SessionInspector,
    private val properties: IdentityProperties,
    private val problems: pl.mos.backoffice.identity.adapter.`in`.web.ProblemResponses,
    private val json: tools.jackson.databind.json.JsonMapper,
) : org.springframework.web.filter.OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "GET" || request.requestURI.removePrefix(request.contextPath) != PATH

    override fun doFilterInternal(request: HttpServletRequest, response: jakarta.servlet.http.HttpServletResponse, chain: jakarta.servlet.FilterChain) {
        val session = inspector.peek(request)
        if (session?.getAttribute<String>(SessionAttributes.USER_ID) == null) return problems.unauthorized(response, "No active session")
        val authTime = session.getAttribute<Long>(SessionAttributes.AUTH_TIME)
        val body = SessionInfoDto(
            idleExpiresAt = session.lastAccessedTime.plus(session.maxInactiveInterval),
            absoluteExpiresAt = authTime?.let { Instant.ofEpochMilli(it).plus(properties.session.absoluteTimeout) },
            idleTimeoutSeconds = session.maxInactiveInterval.seconds,
        )
        response.status = 200
        response.contentType = "application/json"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write(json.writeValueAsString(body))
    }

    companion object {
        const val PATH = "/api/v1/auth/session"
    }
}
