package pl.mos.backoffice.identity.adapter.`in`.rest

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.TestIdentity
import pl.mos.backoffice.identity.adapter.`in`.web.LoginThrottle
import pl.mos.backoffice.identity.application.port.out.LocalCredentialRepository
import pl.mos.backoffice.identity.application.port.out.PasswordHasher
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.Base32
import pl.mos.backoffice.identity.domain.service.TotpVerifier
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.UserId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local accounts end to end (ADR 0021) over MockMvc and a real PostgreSQL: login creates the session,
 * failures are uniform (and both paths run the password encoder), lockout and unlock, per-IP throttling,
 * TOTP enrolment and login (code and recovery code), forced password change, reset links, CSRF.
 * Every test uses its own source address so the throttle does not interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class LocalAccountIntegrationTest {

    @Autowired lateinit var mvc: MockMvcTester
    @Autowired lateinit var identity: TestIdentity
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var credentials: LocalCredentialRepository
    @Autowired lateinit var hasher: PasswordHasher
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var throttle: LoginThrottle

    @MockitoSpyBean lateinit var encoder: PasswordEncoder

    private lateinit var ip: String

    @BeforeEach
    fun freshAddress() {
        ip = "10.1.${counter.incrementAndGet() / 250}.${counter.get() % 250 + 1}"
    }

    private fun localUser(email: String = "local-${UUID.randomUUID()}@example.com", password: String = PASSWORD, mustChange: Boolean = false, mode: AuthMode = AuthMode.LOCAL): User {
        val user = users.save(User(UserId.new(), null, null, email, "Local User", UserStatus.ACTIVE, createdAt = Instant.now(), createdBy = Actor.SYSTEM, authMode = mode))
        credentials.save(LocalCredential.empty(user.id).withPassword(hasher.hash(password), Instant.now(), mustChange))
        return user
    }

    private fun post(uri: String, body: String, session: Cookie? = null) = mvc.post().uri(uri)
        .with { it.remoteAddr = ip; it }
        .cookie(*listOfNotNull(session, TestIdentity.XSRF).toTypedArray())
        .header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value)
        .contentType(MediaType.APPLICATION_JSON).content(body)

    private fun login(email: String, password: String): MvcTestResult =
        post("/api/v1/auth/login", """{"email":"$email","password":"$password","returnTo":"/projects"}""").exchange()

    private fun sessionOf(result: MvcTestResult): Cookie = result.response.getCookie("BO_SESSION") ?: error("no session cookie in ${result.response.contentAsString}")

    private fun field(result: MvcTestResult, name: String): String = json.readTree(result.response.contentAsString).get(name).asString()

    private fun auditCount(operation: String, entityId: String? = null, reason: String? = null): Long =
        jdbc.sql(
            "SELECT count(*) FROM audit_log WHERE operation = :op AND (CAST(:id AS text) IS NULL OR entity_id = :id) AND (CAST(:reason AS text) IS NULL OR details->>'reason' = :reason)",
        ).param("op", operation).param("id", entityId).param("reason", reason).query(Long::class.javaObjectType).single()

    @Test
    fun `auth config advertises local login and the policy and hands out the XSRF cookie`() {
        val result = mvc.get().uri("/api/v1/auth/config").exchange()
        assertThat(result).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.methods.local").isEqualTo(true) },
            { assertThat(it).extractingPath("$.methods.oidc").isEqualTo(false) },
            { assertThat(it).extractingPath("$.passwordPolicy.minLength").isEqualTo(12) },
        )
        assertThat(result.response.getCookie("XSRF-TOKEN")).isNotNull()
    }

    @Test
    fun `a local login creates a session that authenticates me, audits the success and logs out without an IdP url`() {
        val user = localUser()
        val result = login(user.email, PASSWORD)
        assertThat(result).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.status").isEqualTo("AUTHENTICATED") },
            { assertThat(it).extractingPath("$.returnTo").isEqualTo("/projects") },
        )
        assertThat(result.response.getCookie("XSRF-TOKEN")).describedAs("CSRF token rotated at login").isNotNull()
        val session = sessionOf(result)
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk().bodyJson().extractingPath("$.user.email").isEqualTo(user.email)
        assertThat(mvc.get().uri("/api/v1/me/security").cookie(session)).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.authMode").isEqualTo("LOCAL") },
            { assertThat(it).extractingPath("$.lastLoginMethod").isEqualTo("LOCAL") },
            { assertThat(it).extractingPath("$.recentLogins[0].method").isEqualTo("LOCAL") },
        )
        assertThat(auditCount("LOGIN_SUCCESS", user.id.toString())).isEqualTo(1)
        assertThat(post("/api/v1/auth/logout", "{}", session)).hasStatusOk().bodyJson().doesNotHavePath("$.logoutUrl")
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `unknown user and wrong password get identical answers and both run the password encoder`() {
        val user = localUser()
        clearInvocations(encoder)
        val unknown = login("nobody-${UUID.randomUUID()}@example.com", "some-password-123")
        verify(encoder, times(1)).matches(eq("some-password-123"), anyString())
        val wrong = login(user.email, "some-password-123")
        verify(encoder, times(2)).matches(eq("some-password-123"), anyString())

        assertThat(unknown).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(wrong).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(unknown.response.contentAsString).isEqualTo(wrong.response.contentAsString)
        assertThat(field(wrong, "code")).isEqualTo("invalid-credentials")
        assertThat(unknown.response.getCookie("BO_SESSION")).isNull()
        assertThat(auditCount("LOGIN_FAILURE", user.id.toString(), "bad-password")).isEqualTo(1)

        // an OIDC-only account answers the same way
        val oidc = localUser(mode = AuthMode.OIDC)
        assertThat(login(oidc.email, PASSWORD).response.contentAsString).isEqualTo(wrong.response.contentAsString)
    }

    @Test
    fun `five failures lock the account, the correct password is refused until an administrator unlocks it`() {
        val user = localUser()
        repeat(5) { assertThat(login(user.email, "wrong-password-123")).hasStatus(HttpStatus.UNAUTHORIZED) }
        assertThat(auditCount("ACCOUNT_LOCKED", user.id.toString())).isEqualTo(1)
        assertThat(login(user.email, PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(auditCount("LOGIN_FAILURE", user.id.toString(), "locked")).isEqualTo(1)

        val admin = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
        assertThat(mvc.get().uri("/api/v1/admin/users/${user.id}").cookie(admin)).hasStatusOk().bodyJson().extractingPath("$.lockedUntil").isNotNull()
        assertThat(post("/api/v1/admin/users/${user.id}/unlock", "{}", admin)).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).doesNotHavePath("$.lockedUntil") },
            { assertThat(it).extractingPath("$.authMode").isEqualTo("LOCAL") },
            { assertThat(it).extractingPath("$.hasPassword").isEqualTo(true) },
        )
        assertThat(auditCount("ACCOUNT_UNLOCK", user.id.toString())).isEqualTo(1)
        assertThat(login(user.email, PASSWORD)).hasStatusOk()
    }

    @Test
    fun `more than 20 attempts from one address within 5 minutes get 429 with Retry-After`() {
        try {
            repeat(20) { assertThat(login("flood@example.com", "wrong-password-123")).hasStatus(HttpStatus.UNAUTHORIZED) }
            val throttled = login("flood@example.com", "wrong-password-123")
            assertThat(throttled).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
            assertThat(throttled.response.getHeader("Retry-After")!!.toLong()).isBetween(1L, 300L)
            assertThat(field(throttled, "code")).isEqualTo("too-many-attempts")
            assertThat(post("/api/v1/auth/login/mfa", """{"mfaToken":"x","code":"123456"}""").exchange()).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
            assertThat(auditCount("LOGIN_FAILURE", reason = "throttled")).isGreaterThanOrEqualTo(1)
            // another address is not affected
            ip = "10.200.0.1"
            assertThat(login("flood@example.com", "wrong-password-123")).hasStatus(HttpStatus.UNAUTHORIZED)
        } finally {
            throttle.reset()
        }
    }

    @Test
    fun `TOTP enrolment, then login with a generated code and with a single-use recovery code`() {
        val user = localUser()
        val session = sessionOf(login(user.email, PASSWORD))
        val enrolment = post("/api/v1/me/mfa/totp", "{}", session).exchange()
        assertThat(enrolment).hasStatusOk()
        assertThat(field(enrolment, "qrSvg")).startsWith("<svg")
        assertThat(field(enrolment, "otpauthUri")).startsWith("otpauth://totp/")
        val secret = Base32.decode(field(enrolment, "secret"))
        val step = TotpVerifier.stepOf(Instant.now())
        val confirmed = mvc.put().uri("/api/v1/me/mfa/totp").with { it.remoteAddr = ip; it }.cookie(session, TestIdentity.XSRF)
            .header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value).contentType(MediaType.APPLICATION_JSON)
            .content("""{"code":"${TotpVerifier.code(secret, step)}"}""").exchange()
        assertThat(confirmed).hasStatusOk()
        val codesNode = json.readTree(confirmed.response.contentAsString).get("codes")
        val codes = (0 until codesNode.size()).map { codesNode.get(it).asString() }
        assertThat(codes).hasSize(10)
        assertThat(auditCount("MFA_ENABLE", user.id.toString())).isEqualTo(1)

        val first = login(user.email, PASSWORD)
        assertThat(first).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("MFA_REQUIRED")
        assertThat(first.response.getCookie("BO_SESSION")).isNull()
        // the code of the enrolment step is spent (replay); the next step is within the window
        val replay = post("/api/v1/auth/login/mfa", """{"mfaToken":"${field(first, "mfaToken")}","code":"${TotpVerifier.code(secret, step)}"}""").exchange()
        assertThat(replay).hasStatus(HttpStatus.UNAUTHORIZED)

        val second = login(user.email, PASSWORD)
        val signedIn = post("/api/v1/auth/login/mfa", """{"mfaToken":"${field(second, "mfaToken")}","code":"${TotpVerifier.code(secret, step + 1)}"}""").exchange()
        assertThat(signedIn).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED")
        assertThat(mvc.get().uri("/api/v1/me").cookie(sessionOf(signedIn))).hasStatusOk()
        // the token was consumed
        assertThat(post("/api/v1/auth/login/mfa", """{"mfaToken":"${field(second, "mfaToken")}","code":"${TotpVerifier.code(secret, step + 1)}"}""").exchange())
            .hasStatus(HttpStatus.UNAUTHORIZED)

        val third = login(user.email, PASSWORD)
        assertThat(post("/api/v1/auth/login/mfa", """{"mfaToken":"${field(third, "mfaToken")}","recoveryCode":"${codes[0]}"}""").exchange())
            .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED")
        val fourth = login(user.email, PASSWORD)
        assertThat(post("/api/v1/auth/login/mfa", """{"mfaToken":"${field(fourth, "mfaToken")}","recoveryCode":"${codes[0]}"}""").exchange())
            .hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(auditCount("RECOVERY_CODE_USED", user.id.toString())).isEqualTo(1)

        val admin = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
        assertThat(mvc.delete().uri("/api/v1/admin/users/${user.id}/mfa").cookie(admin, TestIdentity.XSRF).header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value))
            .hasStatus(HttpStatus.NO_CONTENT)
        assertThat(login(user.email, PASSWORD)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED")
    }

    @Test
    fun `a forced password change restricts the session until the password is changed`() {
        val user = localUser(mustChange = true)
        val result = login(user.email, PASSWORD)
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("PASSWORD_CHANGE_REQUIRED")
        val session = sessionOf(result)
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk()
        val blocked = mvc.get().uri("/api/v1/tokens").cookie(session).exchange()
        assertThat(blocked).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(field(blocked, "code")).isEqualTo("password-change-required")

        assertThat(post("/api/v1/auth/password/change", """{"newPassword":"unbelievable"}""", session)).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/auth/password/change", """{"newPassword":"$NEW_PASSWORD"}""", session)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/tokens").cookie(session)).hasStatusOk()
        assertThat(auditCount("PASSWORD_CHANGE", user.id.toString())).isEqualTo(1)
        assertThat(login(user.email, PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(login(user.email, NEW_PASSWORD)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED")

        // a normal change needs the current password
        val normal = sessionOf(login(user.email, NEW_PASSWORD))
        assertThat(post("/api/v1/auth/password/change", """{"currentPassword":"wrong-password-123","newPassword":"yet-another-secret"}""", normal))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("invalid-credentials")
    }

    @Test
    fun `an invited local user sets the password with the link, an admin reset replaces it and ends sessions`() {
        val admin = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
        val email = "invitee-${UUID.randomUUID()}@example.com"
        val invited = post("/api/v1/admin/users", """{"email":"$email","authMode":"LOCAL"}""", admin).exchange()
        assertThat(invited).hasStatus(HttpStatus.CREATED)
        val url = field(invited, "setPasswordUrl")
        assertThat(url).startsWith("http://localhost:4200/set-password/")
        val token = url.substringAfterLast('/')
        val userId = field(invited, "id")

        assertThat(mvc.get().uri("/api/v1/auth/password/reset/$token")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.email").isEqualTo(email) },
            { assertThat(it).extractingPath("$.purpose").isEqualTo("SET_INITIAL") },
        )
        val set = post("/api/v1/auth/password/reset/$token", """{"newPassword":"$NEW_PASSWORD"}""").exchange()
        assertThat(set).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED")
        val userSession = sessionOf(set)
        assertThat(mvc.get().uri("/api/v1/me").cookie(userSession)).hasStatusOk().bodyJson().extractingPath("$.user.status").isEqualTo("ACTIVE")
        assertThat(mvc.get().uri("/api/v1/auth/password/reset/$token")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/auth/password/reset/$token", """{"newPassword":"another-new-secret"}""")).hasStatus(HttpStatus.NOT_FOUND)

        val reset = post("/api/v1/admin/users/$userId/password-reset", "{}", admin).exchange()
        assertThat(reset).hasStatusOk()
        assertThat(field(reset, "url")).contains("/set-password/")
        assertThat(mvc.get().uri("/api/v1/me").cookie(userSession)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(login(email, NEW_PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED)
        val resetToken = field(reset, "url").substringAfterLast('/')
        assertThat(mvc.get().uri("/api/v1/auth/password/reset/$resetToken")).hasStatusOk().bodyJson().extractingPath("$.purpose").isEqualTo("RESET")
        assertThat(post("/api/v1/auth/password/reset/$resetToken", """{"newPassword":"a-third-new-secret"}""")).hasStatusOk()
        assertThat(login(email, "a-third-new-secret")).hasStatusOk()
        assertThat(auditCount("PASSWORD_RESET_ISSUE", userId)).isEqualTo(2)
        assertThat(auditCount("PASSWORD_RESET_COMPLETE", userId)).isEqualTo(2)
    }

    @Test
    fun `an administrator switches an OIDC user to BOTH and gets a set-password link`() {
        val admin = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
        val user = identity.user()
        val result = mvc.put().uri("/api/v1/admin/users/${user.id}/auth").cookie(admin, TestIdentity.XSRF).header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value)
            .contentType(MediaType.APPLICATION_JSON).content("""{"authMode":"BOTH"}""").exchange()
        assertThat(result).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.authMode").isEqualTo("BOTH") },
            { assertThat(it).extractingPath("$.hasPassword").isEqualTo(false) },
            { assertThat(it).extractingPath("$.setPasswordUrl").asString().contains("/set-password/") },
        )
        assertThat(auditCount("AUTH_MODE_CHANGE", user.id.toString())).isEqualTo(1)
    }

    @Test
    fun `platform settings carry the local-account fields and validate them`() {
        val admin = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
        assertThat(mvc.get().uri("/api/v1/admin/settings").cookie(admin)).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.localLoginEnabled").isEqualTo(true) },
            { assertThat(it).extractingPath("$.lockout.maxFailures").isEqualTo(5) },
        )
        val put = { body: String ->
            mvc.put().uri("/api/v1/admin/settings").cookie(admin, TestIdentity.XSRF).header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange()
        }
        assertThat(put("""{"passwordPolicy":{"minLength":4}}""")).hasStatus(HttpStatus.BAD_REQUEST)
        // no identity provider in this context: switching local login off would lock everybody out
        assertThat(put("""{"localLoginEnabled":false}""")).hasStatus(HttpStatus.CONFLICT)
        assertThat(put("""{"passwordPolicy":{"minLength":14}}""")).hasStatusOk().bodyJson().extractingPath("$.passwordPolicy.minLength").isEqualTo(14)
        assertThat(put("""{"passwordPolicy":{"minLength":12}}""")).hasStatusOk()
    }

    @Test
    fun `local login needs the CSRF header like every unsafe call`() {
        val user = localUser()
        val noCsrf = mvc.post().uri("/api/v1/auth/login").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"${user.email}","password":"$PASSWORD"}""").exchange()
        assertThat(noCsrf).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(field(noCsrf, "code")).isEqualTo("csrf")
    }

    companion object {
        const val PASSWORD = "correct-horse-battery-staple"
        const val NEW_PASSWORD = "a-brand-new-secret-phrase"
        private val counter = AtomicInteger()
    }
}
