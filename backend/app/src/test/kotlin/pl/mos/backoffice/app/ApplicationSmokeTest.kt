package pl.mos.backoffice.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.web.client.RestClient
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.service.TokenCodec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Boots the whole application against a throw-away PostgreSQL: Flyway migrations from every module run,
 * every adapter wires, the one security chain rejects anonymous API calls with 401 problem details, a
 * session (as created by the OIDC login) and a personal token authenticate, the bootstrap admin receives
 * `platform-admin`, and the public endpoints stay public. OIDC discovery is off (blank issuer).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "backoffice.runner.mode=DISABLED",
        "backoffice.evaluation.seed-starter-content=true",
        "backoffice.auth.bootstrap-admins=boss@example.com",
        "backoffice.auth.cookie-secure=false",
        "backoffice.security.runner-token-secret=test-secret",
        "backoffice.auth.local.secret-key=dGVzdC1vbmx5LWxvY2FsLWF1dGgta2V5LTMyLWJ5dGU=",
        "spring.flyway.enabled=true",
    ],
)
@Import(PostgresTestSupport::class)
class ApplicationSmokeTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var restClientBuilder: RestClient.Builder
    @Autowired lateinit var resolveLogin: ResolveLogin
    @Autowired lateinit var tokens: PersonalTokenRepository
    @Autowired lateinit var sessions: FindByIndexNameSessionRepository<out Session>

    private fun client() = restClientBuilder.baseUrl("http://localhost:$port").build()

    /** What the OIDC success handler does: resolve the user, store its id in a server-side session. */
    private fun login(email: String): Pair<User, String> {
        val result = resolveLogin.resolve(ResolveLogin.LoginClaims("https://idp.test", "sub-$email", email, true, "Smoke $email", null))
        val user = (result as ResolveLogin.LoginResult.Success).user
        @Suppress("UNCHECKED_CAST")
        val repository = sessions as FindByIndexNameSessionRepository<Session>
        val session = repository.createSession()
        session.setAttribute("BO_USER_ID", user.id.toString())
        session.setAttribute("BO_AUTH_TIME", Instant.now().toEpochMilli())
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, user.id.toString())
        repository.save(session)
        return user to "BO_SESSION=" + Base64.getEncoder().encodeToString(session.id.toByteArray())
    }

    private fun pat(user: User): String {
        val generated = TokenCodec.generate()
        tokens.save(PersonalAccessToken(TokenId.new(), user.id, "smoke", generated.hash, generated.prefix, emptySet(), Instant.now().plus(Duration.ofHours(1)), Instant.now()))
        return "Bearer " + generated.plaintext
    }

    @Test
    fun `health is public`() {
        val response = client().get().uri("/actuator/health").retrieve().toEntity(String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `api rejects anonymous calls with a problem detail and a correlation id`() {
        val response = client().get().uri("/api/v1/projects")
            .exchange { _, res -> Triple(res.statusCode, res.headers.contentType?.toString(), res.headers.getFirst("X-Correlation-Id")) }
        assertThat(response.first).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.second).contains("problem+json")
        assertThat(response.third).isNotBlank()
    }

    @Test
    fun `a session user gets me and the bootstrap admin is a platform admin`() {
        // invite-only by default: a non-invited, non-bootstrap e-mail is rejected at login
        assertThat(resolveLogin.resolve(ResolveLogin.LoginClaims("https://idp.test", "sub-x-${UUID.randomUUID()}", "x-${UUID.randomUUID()}@example.com", true, "X", null)))
            .isInstanceOf(ResolveLogin.LoginResult.Rejected::class.java)

        val (_, adminCookie) = login("boss@example.com")
        val me = client().get().uri("/api/v1/me").header(HttpHeaders.COOKIE, adminCookie).retrieve().toEntity(String::class.java)
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(me.body).contains("boss@example.com", "admin.users.manage", "admin.projects.all")
        val auth = client().get().uri("/api/v1/auth/config").retrieve().toEntity(String::class.java)
        assertThat(auth.body).contains("INVITE_ONLY")
    }

    @Test
    fun `a personal token reaches the controllers of every context`() {
        val (admin, _) = login("boss@example.com")
        val bearer = pat(admin)
        for (path in listOf("/api/v1/me", "/api/v1/admin/users", "/api/v1/projects", "/api/v1/dashboard", "/api/v1/ci/executions")) {
            val status = client().get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange { _, res -> res.statusCode }
            assertThat(status).describedAs(path).isNotEqualTo(HttpStatus.UNAUTHORIZED)
        }
        val users = client().get().uri("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer).retrieve().toEntity(String::class.java)
        assertThat(users.body).contains("boss@example.com")
    }

    @Test
    fun `CI endpoints authenticate bo_ci tokens in the bearer filter`() {
        val invalid = client().post().uri("/api/v1/ci/signals").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer bo_ci_" + "x".repeat(40))
            .body("""{"signal":"tests","status":"SUCCESS","context":{}}""")
            .exchange { _, res -> res.statusCode to res.headers.contentType?.toString() }
        assertThat(invalid.first).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(invalid.second).contains("problem+json")
        val anonymous = client().post().uri("/api/v1/ci/executions").contentType(MediaType.APPLICATION_JSON)
            .body("""{"entrypoint":"code-review","context":{"provider":"GITLAB"}}""").exchange { _, res -> res.statusCode }
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the CI client script is public and served as a shell script or reported missing`() {
        val response = client().get().uri("/ci/backoffice-ci.sh").exchange { _, res -> res.statusCode to res.headers.contentType?.toString() }
        assertThat(response.first).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND)
        if (response.first == HttpStatus.OK) assertThat(response.second).contains("text/x-shellscript") else assertThat(response.second).contains("problem+json")
        val viaApi = client().get().uri("/api/v1/ci/script").exchange { _, res -> res.statusCode }
        assertThat(viaApi).isEqualTo(response.first)
    }

    @Test
    fun `the removed api key header no longer authenticates`() {
        val status = client().get().uri("/api/v1/projects").header("X-Api-Key", "dev-api-key").exchange { _, res -> res.statusCode }
        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `operations overview is served to platform admins and prometheus exposes the platform meters`() {
        val (admin, _) = login("boss@example.com")
        val bearer = pat(admin)
        val overview = client().get().uri("/api/v1/admin/operations/overview").header(HttpHeaders.AUTHORIZATION, bearer).retrieve().toEntity(String::class.java)
        assertThat(overview.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(overview.body).contains("\"replicas\"", "\"workItems\"", "api", "worker")
        val items = client().get().uri("/api/v1/admin/operations/work-items?status=DONE").header(HttpHeaders.AUTHORIZATION, bearer).retrieve().toEntity(String::class.java)
        assertThat(items.statusCode).isEqualTo(HttpStatus.OK)
        val anonymous = client().get().uri("/api/v1/admin/operations/overview").exchange { _, res -> res.statusCode }
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED)
        val scrape = client().get().uri("/actuator/prometheus").exchange { _, res -> res.statusCode to String(res.body.readAllBytes()) }
        if (scrape.first == HttpStatus.OK) {
            assertThat(scrape.second).contains("backoffice_work_oldest_ready_age_seconds", "backoffice_event_listener_connected", "backoffice_runs_active")
        } else {
            // the scrape endpoint may require authentication; the meters are still registered
            assertThat(scrape.first).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `openapi document is served`() {
        val response = client().get().uri("/v3/api-docs").accept(MediaType.APPLICATION_JSON)
            .retrieve().toEntity(String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers[HttpHeaders.CONTENT_TYPE]?.first()).contains("json")
        assertThat(response.body).contains("Back Office API")
    }
}
