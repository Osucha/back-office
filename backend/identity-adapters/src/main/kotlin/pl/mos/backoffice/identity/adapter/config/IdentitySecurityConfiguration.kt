package pl.mos.backoffice.identity.adapter.config

import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.session.config.SessionRepositoryCustomizer
import pl.mos.backoffice.identity.adapter.`in`.rest.SessionInspector
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import pl.mos.backoffice.identity.adapter.`in`.web.CsrfCookieFilter
import pl.mos.backoffice.identity.adapter.`in`.web.LoginSessions
import pl.mos.backoffice.identity.adapter.`in`.web.LoginThrottleFilter
import pl.mos.backoffice.identity.adapter.`in`.web.LoginThrottle
import pl.mos.backoffice.identity.adapter.`in`.web.ThrottleAuditMemory
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcLoginThrottle
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcThrottleAuditMemory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.identity.adapter.`in`.web.OidcLoginFailureHandler
import pl.mos.backoffice.identity.adapter.`in`.web.OidcLoginSuccessHandler
import pl.mos.backoffice.identity.adapter.`in`.web.PrincipalFilter
import pl.mos.backoffice.identity.adapter.`in`.web.ProblemResponses
import pl.mos.backoffice.identity.adapter.`in`.web.ReturnToAuthorizationRequestResolver
import pl.mos.backoffice.identity.adapter.out.security.CurrentUserCache
import pl.mos.backoffice.identity.application.port.`in`.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.`in`.RecordAuthEvent
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.CiTokenVerifier
import tools.jackson.databind.json.JsonMapper

/**
 * The one and only `SecurityFilterChain` of Back Office (ADR 0016):
 * - OIDC login (`oauth2Login`, registration id `oidc`, PKCE) when `backoffice.auth.oidc.issuer-uri` is set;
 *   the API is the BFF, the browser only holds the `BO_SESSION` cookie (Spring Session JDBC);
 * - local e-mail + password login (`POST /api/v1/auth/login`, ADR 0021) creating the same session, with a
 *   per-IP throttle ([LoginThrottleFilter]) and restricted sessions for forced password change / TOTP enrolment;
 * - bearer personal tokens (`bo_pat_`) and project CI tokens (`bo_ci_`) through [PrincipalFilter];
 * - CSRF in SPA mode (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header), except bearer requests, runner callbacks
 *   and CI POSTs;
 * - API calls never redirect: 401/403 are problem details.
 * Authorisation is decided inside the use cases (`AuthorizationGate`); this chain only requires
 * authentication for `/api/`.
 */
@Configuration
@EnableWebSecurity
class IdentitySecurityConfiguration(private val properties: IdentityProperties) {

    private val log = LoggerFactory.getLogger(IdentitySecurityConfiguration::class.java)

    /** SPA-mode CSRF: `XSRF-TOKEN` cookie (readable by the SPA) + `X-XSRF-TOKEN` header. Shared with [LoginSessions] (rotation at login). */
    private val csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()

    @Bean
    fun problemResponses(json: JsonMapper) = ProblemResponses(json)

    @Bean
    fun loginSessions(clock: Clock) = LoginSessions(clock, csrfRepository)

    /** Per-source-IP throttle of the local login endpoints, counted in PostgreSQL across every replica (ADR 0023). */
    @Bean
    fun loginThrottle(jdbc: JdbcClient, tm: PlatformTransactionManager, clock: Clock): LoginThrottle =
        JdbcLoginThrottle(jdbc, TransactionTemplate(tm), properties.local.throttle.maxAttempts, properties.local.throttle.window, clock)

    @Bean
    fun throttleAuditMemory(jdbc: JdbcClient, clock: Clock): ThrottleAuditMemory = JdbcThrottleAuditMemory(jdbc, clock)

    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository = LazyOidcClientRegistrationRepository(properties)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        problems: ProblemResponses,
        loader: LoadCurrentUser,
        cache: CurrentUserCache,
        ciVerifier: ObjectProvider<CiTokenVerifier>,
        authEvents: RecordAuthEvent,
        resolveLogin: ResolveLogin,
        clientRegistrations: ClientRegistrationRepository,
        clock: Clock,
        throttle: LoginThrottle,
        throttleAudits: ThrottleAuditMemory,
    ): SecurityFilterChain {
        val bearer = RequestMatcher { it.getHeader("Authorization")?.startsWith("Bearer ", ignoreCase = true) == true }
        http
            .cors(Customizer.withDefaults())
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.requestCache(NullRequestCache()) }
            .sessionManagement {
                // sessions are created on demand (IF_REQUIRED default); no SessionManagementFilter: it would save the per-request principal into the session and touch it
                it.requireExplicitAuthenticationStrategy(true)
            }
            .csrf {
                it.spa()
                it.csrfTokenRepository(csrfRepository)
                it.ignoringRequestMatchers(
                    bearer,
                    PathPatternRequestMatcher.pathPattern("/api/v1/internal/runner/**"),
                    // runner fleet v2 and the sandbox LLM proxy authenticate with bo_rt_ / bo_job_ tokens (ADR 0024)
                    PathPatternRequestMatcher.pathPattern("/api/v1/runner/v2/**"),
                    PathPatternRequestMatcher.pathPattern("/api/v1/llm/**"),
                    PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/ci/**"),
                    // MCP server (ADR 0025): personal access tokens only, enforced by governance's McpPersonalTokenFilter
                    PathPatternRequestMatcher.pathPattern("/mcp"),
                    PathPatternRequestMatcher.pathPattern("/mcp/**"),
                    // presigned links of the filesystem BlobStore: the HMAC signature in the URL is the authorisation
                    PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/blobs/**"),
                )
            }
            .addFilterAfter(PrincipalFilter(loader, cache, ciVerifier, authEvents, properties, clock, problems), SecurityContextHolderFilter::class.java)
            .addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
            // before CSRF: floods without a token are throttled too
            .addFilterBefore(LoginThrottleFilter(throttle, problems, authEvents, throttleAudits), CsrfFilter::class.java)
            .authorizeHttpRequests {
                // SSE completes on an ASYNC dispatch; the REQUEST dispatch was already authenticated
                it.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                it.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                it.requestMatchers("/api/v1/auth/config", "/api/v1/auth/session", "/api/v1/auth/logout").permitAll()
                // local login (ADR 0021): the use cases decide; CSRF still applies
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/login/mfa").permitAll()
                it.requestMatchers("/api/v1/auth/password/reset/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/ci/script", "/ci/backoffice-ci.sh").permitAll()
                // machine callers of the execution plane, authenticated by agent-runner's RunnerTokenFilter and llm-gateway's JobTokenFilter
                it.requestMatchers("/api/v1/internal/runner/**", "/api/v1/runner/v2/**", "/api/v1/llm/**").permitAll()
                it.requestMatchers("/oauth2/**", "/login/**", "/logout/**", "/error").permitAll()
                it.requestMatchers("/api/**", "/actuator/**").authenticated()
                // MCP server (ADR 0025): the bearer filter above authenticates bo_pat_ tokens on /mcp as on /api
                it.requestMatchers("/mcp", "/mcp/**").authenticated()
                it.anyRequest().permitAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(problems)
                it.accessDeniedHandler(problems)
            }
        if (properties.oidc.enabled) {
            val resolver = DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization").apply {
                setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
            }
            http.oauth2Login {
                it.loginPage("/login")
                it.authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(ReturnToAuthorizationRequestResolver(resolver)) }
                it.successHandler(OidcLoginSuccessHandler(resolveLogin, properties, clock))
                it.failureHandler(OidcLoginFailureHandler(authEvents))
            }
        } else {
            log.warn(
                "backoffice.auth.oidc.issuer-uri is blank: OIDC login is disabled; {}",
                if (properties.local.enabled) "local accounts and bearer tokens authenticate" else "only bearer tokens authenticate",
            )
        }
        return http.build()
    }

    /** `BO_SESSION`: HttpOnly, Secure (configurable), SameSite=Lax, path `/`. */
    @Bean
    fun cookieSerializer(): CookieSerializer = DefaultCookieSerializer().apply {
        setCookieName("BO_SESSION")
        setUseHttpOnlyCookie(true)
        setUseSecureCookie(properties.cookieSecure)
        setSameSite("Lax")
        setCookiePath("/")
    }

    @Bean
    fun sessionInspector(cookies: CookieSerializer, sessions: ObjectProvider<FindByIndexNameSessionRepository<out Session>>) = SessionInspector(cookies, sessions)

    /** Ordered before Spring Session's filter (`Integer.MIN_VALUE + 50`), after the correlation id filter. */
    @Bean
    fun sessionInfoFilter(inspector: SessionInspector, problems: ProblemResponses, json: JsonMapper) =
        org.springframework.boot.web.servlet.FilterRegistrationBean(pl.mos.backoffice.identity.adapter.`in`.rest.SessionInfoFilter(inspector, properties, problems, json)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }

    /** Idle timeout of server-side sessions (`backoffice.auth.session.idle-timeout`). Runs after Boot's customizer. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun idleTimeoutCustomizer(): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer { it.setDefaultMaxInactiveInterval(properties.session.idleTimeout) }
}

/**
 * The `oidc` client registration, discovered from the issuer on first use (not at startup, so the API starts
 * even while the identity provider is still booting); a failed discovery is retried on the next login.
 */
class LazyOidcClientRegistrationRepository(private val properties: IdentityProperties) : ClientRegistrationRepository, Iterable<ClientRegistration> {

    @Volatile private var registration: ClientRegistration? = null

    override fun findByRegistrationId(registrationId: String): ClientRegistration? {
        if (registrationId != IdentityProperties.REGISTRATION_ID || !properties.oidc.enabled) return null
        return registration ?: synchronized(this) {
            registration ?: ClientRegistrations.fromIssuerLocation(properties.oidc.issuerUri)
                .registrationId(IdentityProperties.REGISTRATION_ID)
                .clientId(properties.oidc.clientId)
                .clientSecret(properties.oidc.clientSecret)
                .scope(properties.oidc.scopes)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .clientName(properties.oidc.providerName)
                .build()
                .also { registration = it }
        }
    }

    /** Provider metadata, e.g. `end_session_endpoint` for the RP-initiated logout; `null` if not discovered. */
    fun metadata(key: String): String? =
        runCatching { findByRegistrationId(IdentityProperties.REGISTRATION_ID)?.providerDetails?.configurationMetadata?.get(key)?.toString() }.getOrNull()

    override fun iterator(): Iterator<ClientRegistration> =
        listOfNotNull(runCatching { findByRegistrationId(IdentityProperties.REGISTRATION_ID) }.getOrNull()).iterator()
}

/** Values the REST layer needs about the deployment. */
@org.springframework.stereotype.Component
class IdentityWebValues(@Value("\${backoffice.public-url:http://localhost:8080}") val publicUrl: String)
