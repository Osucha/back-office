package pl.mos.backoffice.identity.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.identity.adapter.config.IdentityProperties
import pl.mos.backoffice.identity.adapter.out.security.AuthenticationSource
import pl.mos.backoffice.identity.adapter.out.security.CurrentUserAuthentication
import pl.mos.backoffice.identity.adapter.out.security.CurrentUserCache
import pl.mos.backoffice.identity.adapter.out.security.SpringSessionTerminator
import pl.mos.backoffice.identity.application.port.`in`.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.`in`.RecordAuthEvent
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.service.TokenCodec
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.CiTokenVerifier
import pl.mos.backoffice.shared.security.UserId
import java.net.URLEncoder

/**
 * Establishes the request's [CurrentUserAuthentication] (placed right after `SecurityContextHolderFilter`):
 * 1. `Authorization: Bearer bo_pat_...` -> personal access token; `bo_ci_...` -> the Projects context's
 *    [CiTokenVerifier]. An invalid, expired or revoked token is answered with 401 at once.
 * 2. Otherwise the server-side session's user id (set at login): the absolute timeout is enforced, and a user
 *    who is missing or no longer ACTIVE loses the session immediately (401 on the next API call).
 * 3. Anything else (no session, or an OAuth2 login in progress) leaves the request anonymous.
 * A session restricted after a local login (forced password change, required TOTP enrolment) may only call
 * the endpoints that resolve the restriction; anything else under `/api/` gets a 403 with the restriction code.
 * Runner callbacks (`/api/v1/internal/runner/...`) authenticate inside their controller and are skipped;
 * `/api/v1/auth/session` is skipped so that reading the remaining session time does not extend it.
 */
class PrincipalFilter(
    private val loader: LoadCurrentUser,
    private val cache: CurrentUserCache,
    private val ciVerifier: ObjectProvider<CiTokenVerifier>,
    private val authEvents: RecordAuthEvent,
    private val properties: IdentityProperties,
    private val clock: Clock,
    private val problems: ProblemResponses,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(PrincipalFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (path.startsWith(RUNNER_PATH)) return chain.doFilter(request, response)

        val bearer = request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (bearer != null && (bearer.startsWith(TokenCodec.PAT_PREFIX) || bearer.startsWith(TokenCodec.CI_PREFIX))) {
            val authentication = authenticateBearer(bearer)
                ?: return problems.unauthorized(response, "The bearer token is invalid, expired or revoked", "invalid-token")
            authenticate(authentication)
            return chain.doFilter(request, response)
        }

        if (path == SESSION_INFO_PATH) {
            anonymous()
            return chain.doFilter(request, response)
        }

        val session = request.getSession(false)
        val userId = session?.getAttribute(SessionAttributes.USER_ID) as? String
        if (session == null || userId == null) {
            anonymous()
            return chain.doFilter(request, response)
        }
        val id = UserId.of(userId)
        val authTime = session.getAttribute(SessionAttributes.AUTH_TIME) as? Long ?: 0L
        if (clock.now().toEpochMilli() - authTime > properties.session.absoluteTimeout.toMillis()) {
            invalidate(request)
            runCatching { authEvents.sessionExpired(id, "absolute-timeout") }.onFailure { log.warn("could not audit session expiry: {}", it.toString()) }
            anonymous()
            return chain.doFilter(request, response)
        }
        val user = cache.get(id)
        if (user == null) {
            // deactivated or deleted since login: the session is gone for good
            invalidate(request)
            anonymous()
            return chain.doFilter(request, response)
        }
        authenticate(CurrentUserAuthentication(user, AuthenticationSource.SESSION))
        val restriction = (session.getAttribute(SessionAttributes.RESTRICTION) as? String)?.let { runCatching { SessionRestriction.valueOf(it) }.getOrNull() }
        if (restriction != null && !RestrictedSession.allows(restriction, path)) {
            SecurityContextHolder.clearContext()
            return problems.write(response, HttpStatus.FORBIDDEN, "Forbidden", RestrictedSession.detail(restriction), mapOf("code" to RestrictedSession.code(restriction)))
        }
        chain.doFilter(request, response)
    }

    private fun authenticateBearer(token: String): CurrentUserAuthentication? {
        if (token.startsWith(TokenCodec.PAT_PREFIX)) {
            val pat = loader.verifyPersonalToken(token) ?: return null
            val owner = cache.get(pat.userId) ?: return null
            return CurrentUserAuthentication(loader.forPersonalToken(owner, pat), AuthenticationSource.PERSONAL_TOKEN)
        }
        val verifier = ciVerifier.getIfAvailable() ?: return null
        val identity = verifier.verify(token) ?: return null
        return CurrentUserAuthentication(loader.forCiToken(identity), AuthenticationSource.CI_TOKEN)
    }

    private fun authenticate(authentication: Authentication) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
    }

    private fun anonymous() = SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext())

    private fun invalidate(request: HttpServletRequest) {
        runCatching { request.getSession(false)?.invalidate() }
    }

    companion object {
        const val RUNNER_PATH = "/api/v1/internal/runner/"
        const val SESSION_INFO_PATH = "/api/v1/auth/session"
    }
}

/**
 * Stores a validated `returnTo` parameter of `/oauth2/authorization/oidc?returnTo=/projects` in the session
 * and adds PKCE to the authorization request (ADR 0016).
 */
class ReturnToAuthorizationRequestResolver(private val delegate: DefaultOAuth2AuthorizationRequestResolver) : OAuth2AuthorizationRequestResolver {

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? = delegate.resolve(request)?.also { remember(request) }

    override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? =
        delegate.resolve(request, clientRegistrationId)?.also { remember(request) }

    private fun remember(request: HttpServletRequest) {
        val returnTo = request.getParameter("returnTo")
        val session = request.getSession(true)
        if (SafeRedirect.isSafe(returnTo)) session.setAttribute(SessionAttributes.RETURN_TO, returnTo) else session.removeAttribute(SessionAttributes.RETURN_TO)
    }
}

/**
 * After the code exchange: resolves the Back Office user (JIT provisioning, invitations, deactivation) and
 * stores only the user id, the login time and the ID token in the session. A rejected login invalidates the
 * session and redirects to `/login?error=<code>`.
 */
class OidcLoginSuccessHandler(
    private val resolveLogin: ResolveLogin,
    private val properties: IdentityProperties,
    private val clock: Clock,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val oidc = (authentication as? OAuth2AuthenticationToken)?.principal as? OidcUser
        if (oidc == null) return reject(request, response, "unsupported-login")
        val claims = ResolveLogin.LoginClaims(
            issuer = oidc.issuer?.toString() ?: oidc.getClaimAsString("iss") ?: "",
            subject = oidc.subject ?: oidc.name,
            email = oidc.email,
            emailVerified = oidc.getClaim<Any>("email_verified")?.let { it as? Boolean ?: it.toString().toBooleanStrictOrNull() },
            name = oidc.fullName,
            preferredUsername = oidc.preferredUsername,
            groups = groupsOf(oidc.getClaim<Any>(properties.groupsClaim)),
        )
        when (val result = resolveLogin.resolve(claims)) {
            is ResolveLogin.LoginResult.Rejected -> reject(request, response, result.code)
            is ResolveLogin.LoginResult.Success -> {
                val session = request.getSession(true)
                val returnTo = session.getAttribute(SessionAttributes.RETURN_TO) as? String
                session.removeAttribute(SessionAttributes.RETURN_TO)
                // the session keeps ids and times only; permissions are resolved per request
                session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                session.setAttribute(SessionAttributes.USER_ID, result.user.id.toString())
                session.setAttribute(SessionAttributes.AUTH_TIME, clock.now().toEpochMilli())
                session.setAttribute(SessionAttributes.AUTH_METHOD, LoginMethod.OIDC.name)
                session.removeAttribute(SessionAttributes.RESTRICTION)
                session.setAttribute(SessionAttributes.ID_TOKEN, oidc.idToken.tokenValue)
                session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, result.user.id.toString())
                session.setAttribute(SpringSessionTerminator.SOURCE_IP_ATTRIBUTE, request.remoteAddr)
                session.setAttribute(SpringSessionTerminator.USER_AGENT_ATTRIBUTE, request.getHeader("User-Agent")?.take(512))
                SecurityContextHolder.clearContext()
                response.sendRedirect(request.contextPath + (returnTo?.takeIf(SafeRedirect::isSafe) ?: "/"))
            }
        }
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, code: String) {
        runCatching { request.getSession(false)?.invalidate() }
        SecurityContextHolder.clearContext()
        response.sendRedirect(request.contextPath + "/login?error=" + URLEncoder.encode(code, Charsets.UTF_8))
    }

    private fun groupsOf(claim: Any?): List<String> = when (claim) {
        null -> emptyList()
        is Collection<*> -> claim.mapNotNull { it?.toString() }
        is Array<*> -> claim.mapNotNull { it?.toString() }
        is String -> claim.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

/** Provider or code-exchange errors: audited as LOGIN_FAILURE and redirected to `/login?error=<code>`. */
class OidcLoginFailureHandler(private val authEvents: RecordAuthEvent) : AuthenticationFailureHandler {

    private val log = LoggerFactory.getLogger(OidcLoginFailureHandler::class.java)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: org.springframework.security.core.AuthenticationException,
    ) {
        val code = ((exception as? OAuth2AuthenticationException)?.error?.errorCode ?: "login-failed")
            .lowercase().replace('_', '-').filter { it.isLetterOrDigit() || it == '-' }.take(40).ifEmpty { "login-failed" }
        log.warn("OIDC login failed code={} message={}", code, exception.message)
        runCatching { authEvents.loginFailed(code, exception.message) }
        runCatching { request.getSession(false)?.invalidate() }
        response.sendRedirect(request.contextPath + "/login?error=" + code)
    }
}
