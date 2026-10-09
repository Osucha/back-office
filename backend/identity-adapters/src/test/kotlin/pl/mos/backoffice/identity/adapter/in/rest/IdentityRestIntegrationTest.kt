package pl.mos.backoffice.identity.adapter.`in`.rest

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.TestIdentity
import pl.mos.backoffice.identity.adapter.out.security.SecurityContextAuthorizationGate
import pl.mos.backoffice.identity.application.port.`in`.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.`in`.ManageGroups
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class IdentityRestIntegrationTest {

    @Autowired lateinit var mvc: MockMvcTester
    @Autowired lateinit var identity: TestIdentity
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var provisioner: ProjectAccessProvisioner
    @Autowired lateinit var groups: ManageGroups
    @Autowired lateinit var gate: SecurityContextAuthorizationGate
    @Autowired lateinit var loader: LoadCurrentUser
    @Autowired lateinit var tm: PlatformTransactionManager

    private fun admin(): Cookie = identity.login(identity.user("admin-${UUID.randomUUID()}@example.com", "platform-admin")).first
    private fun member(): Pair<Cookie, pl.mos.backoffice.identity.domain.model.User> = identity.user().let { identity.login(it).first to it }

    private fun post(uri: String, body: String, session: Cookie) = mvc.post().uri(uri).cookie(session, TestIdentity.XSRF)
        .header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun put(uri: String, body: String, session: Cookie) = mvc.put().uri(uri).cookie(session, TestIdentity.XSRF)
        .header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun delete(uri: String, session: Cookie) = mvc.delete().uri(uri).cookie(session, TestIdentity.XSRF).header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value)

    private fun idOf(body: String): String = json.readTree(body).get("id").asString()

    private fun auditCount(operation: String, entityId: String? = null): Long =
        jdbc.sql("SELECT count(*) FROM audit_log WHERE operation = :op AND (CAST(:id AS text) IS NULL OR entity_id = :id)")
            .param("op", operation).param("id", entityId).query(Long::class.javaObjectType).single()

    @Test
    fun `anonymous API calls get a 401 problem detail and every response carries a correlation id`() {
        val result = mvc.get().uri("/api/v1/me").exchange()
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(result.response.contentType).contains("problem+json")
        assertThat(result.response.getHeader("X-Correlation-Id")).isNotBlank()
        assertThat(mvc.get().uri("/api/v1/anything")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(mvc.get().uri("/api/v1/auth/config")).hasStatusOk().bodyJson().extractingPath("$.registration").isEqualTo("INVITE_ONLY")
    }

    @Test
    fun `a session user gets me with the SYSTEM member permissions`() {
        val (session, user) = member()
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.user.email").isEqualTo(user.email) },
            { assertThat(it).extractingPath("$.platformPermissions").asArray().contains("library.view", "tokens.personal").doesNotContain("admin.users.view", "projects.create") },
            { assertThat(it).extractingPath("$.idleTimeoutSeconds").isEqualTo(1800) },
            { assertThat(it).extractingPath("$.absoluteExpiresAt").isNotNull() },
        )
    }

    @Test
    fun `unsafe requests with a session need the CSRF header, bearer requests do not`() {
        val (session, user) = member()
        val noCsrf = mvc.put().uri("/api/v1/me/preferences").cookie(session).contentType(MediaType.APPLICATION_JSON).content("""{"theme":"DARK"}""").exchange()
        assertThat(noCsrf).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(noCsrf).bodyJson().extractingPath("$.code").isEqualTo("csrf")
        assertThat(put("/api/v1/me/preferences", """{"theme":"DARK","pageSize":50}""", session)).hasStatusOk().bodyJson().extractingPath("$.theme").isEqualTo("DARK")

        val token = identity.pat(user)
        assertThat(mvc.put().uri("/api/v1/me/preferences").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content("""{"theme":"LIGHT"}"""))
            .hasStatusOk()
        // the CSRF cookie is handed out for the SPA
        assertThat(mvc.get().uri("/api/v1/auth/config").exchange().response.getCookie("XSRF-TOKEN")).isNotNull()
    }

    @Test
    fun `personal tokens authenticate as bearer, are narrowed to their subset and fail when invalid or revoked`() {
        val (session, user) = member()
        val created = post("/api/v1/tokens", """{"name":"cli","expiresInDays":7,"permissions":["library.view"]}""", session).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val plaintext = json.readTree(created.response.contentAsString).get("token").asString()
        assertThat(plaintext).startsWith("bo_pat_")
        assertThat(mvc.get().uri("/api/v1/tokens").cookie(session).exchange().response.contentAsString).contains("bo_pat_").doesNotContain(plaintext)

        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer $plaintext")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.kind").isEqualTo("PERSONAL_TOKEN") },
            { assertThat(it).extractingPath("$.platformPermissions").asArray().containsExactly("library.view") },
        )
        // a token cannot mint tokens
        assertThat(mvc.post().uri("/api/v1/tokens").header("Authorization", "Bearer $plaintext").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x","expiresInDays":1}"""))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer bo_pat_" + "x".repeat(40))).hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("invalid-token")

        assertThat(delete("/api/v1/tokens/${idOf(created.response.contentAsString)}", session)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer $plaintext")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(auditCount("PAT_REVOKE")).isGreaterThanOrEqualTo(1)
        assertThat(user.status).isEqualTo(UserStatus.ACTIVE)
    }

    @Test
    fun `CI tokens authenticate through the CiTokenVerifier and hold only ci permissions on their project`() {
        val forbidden = mvc.get().uri("/api/v1/projects/${TestIdentity.CI_PROJECT}/members").header("Authorization", "Bearer ${TestIdentity.CI_TOKEN}").exchange()
        assertThat(forbidden).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(forbidden).bodyJson().extractingPath("$.permission").isEqualTo("project.view")
        assertThat(forbidden).bodyJson().extractingPath("$.projectId").isEqualTo(TestIdentity.CI_PROJECT.toString())
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer bo_ci_wrong")).hasStatus(HttpStatus.UNAUTHORIZED)
        // CI POSTs are exempt from CSRF
        assertThat(mvc.post().uri("/api/v1/ci/executions").header("Authorization", "Bearer ${TestIdentity.CI_TOKEN}").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `access denied is a 403 with the permission and an ACCESS_DENIED audit row`() {
        val (session, user) = member()
        val result = mvc.get().uri("/api/v1/admin/users").cookie(session).exchange()
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(result).bodyJson().extractingPath("$.permission").isEqualTo("admin.users.view")
        val row = jdbc.sql("SELECT actor_id, result, correlation_id FROM audit_log WHERE operation = 'ACCESS_DENIED' AND actor_id = :u")
            .param("u", user.id.value).query { rs, _ -> Triple(rs.getObject("actor_id"), rs.getString("result"), rs.getString("correlation_id")) }.single()
        assertThat(row.second).isEqualTo("DENIED")
        assertThat(row.third).isEqualTo(result.response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `an administrator invites, updates, deactivates and reactivates users`() {
        val session = admin()
        val email = "invitee-${UUID.randomUUID()}@example.com"
        val invited = post("/api/v1/admin/users", """{"email":"$email","displayName":"Invitee","platformRoles":["evaluator"]}""", session).exchange()
        assertThat(invited).hasStatus(HttpStatus.CREATED)
        assertThat(invited).bodyJson().extractingPath("$.status").isEqualTo("INVITED")
        assertThat(invited).bodyJson().extractingPath("$.signInUrl").isEqualTo("http://localhost:4200/login")
        val id = idOf(invited.response.contentAsString)
        assertThat(post("/api/v1/admin/users", """{"email":"${email.uppercase()}"}""", session)).hasStatus(HttpStatus.CONFLICT)

        assertThat(put("/api/v1/admin/users/$id", """{"displayName":"Renamed"}""", session)).hasStatusOk().bodyJson().extractingPath("$.displayName").isEqualTo("Renamed")
        assertThat(mvc.get().uri("/api/v1/admin/users?q=${email.take(10)}").cookie(session)).hasStatusOk().bodyJson().extractingPath("$.totalElements").isEqualTo(1)
        assertThat(mvc.get().uri("/api/v1/admin/users/$id/effective-permissions").cookie(session)).hasStatusOk().bodyJson()
            .extractingPath("$.rows[?(@.permission=='evaluation.run')].source").asArray().containsExactly("USER")
        assertThat(post("/api/v1/admin/users/$id/deactivate", "", session)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("DEACTIVATED")
        assertThat(post("/api/v1/admin/users/$id/activate", "", session)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("INVITED")
        assertThat(auditCount("USER_INVITE", id)).isEqualTo(1)
        assertThat(auditCount("USER_UPDATE", id)).isEqualTo(1)
        assertThat(auditCount("USER_DEACTIVATE", id)).isEqualTo(1)
    }

    @Test
    fun `a deactivated user loses the session on the next request`() {
        val (session, user) = member()
        val (_, sessionId) = identity.login(user)
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk()
        assertThat(post("/api/v1/admin/users/${user.id}/deactivate", "", admin())).hasStatusOk()
        assertThat(identity.sessionExists(sessionId)).isFalse()
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `sessions past the absolute timeout are rejected`() {
        val user = identity.user()
        val (session, id) = identity.login(user, authTime = Instant.now().minus(Duration.ofHours(13)))
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(identity.sessionExists(id)).isFalse()
        assertThat(auditCount("SESSION_EXPIRED", user.id.toString())).isEqualTo(1)
    }

    @Test
    fun `reading the session info does not extend the session`() {
        val (session, id) = identity.login(identity.user())
        fun lastAccess() = jdbc.sql("SELECT last_access_time FROM spring_session WHERE session_id = :id").param("id", id).query(Long::class.javaObjectType).single()
        val before = lastAccess()
        Thread.sleep(50)
        assertThat(mvc.get().uri("/api/v1/auth/session").cookie(session)).hasStatusOk().bodyJson().extractingPath("$.idleTimeoutSeconds").isEqualTo(1800)
        assertThat(lastAccess()).isEqualTo(before)
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk()
        assertThat(lastAccess()).isGreaterThan(before)
        assertThat(mvc.get().uri("/api/v1/auth/session")).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `logout invalidates the session and is audited`() {
        val user = identity.user()
        val (session, id) = identity.login(user)
        val result = post("/api/v1/auth/logout", "", session).exchange()
        assertThat(result).hasStatusOk()
        assertThat(result.response.contentAsString).doesNotContain("logoutUrl") // null (no OIDC provider in this test)
        assertThat(identity.sessionExists(id)).isFalse()
        assertThat(auditCount("LOGOUT", user.id.toString())).isEqualTo(1)
    }

    @Test
    fun `groups, roles, grants, settings and the catalogue are managed by administrators`() {
        val session = admin()
        val user = identity.user()
        val group = post("/api/v1/admin/groups", """{"name":"Team ${UUID.randomUUID()}","memberIds":["${user.id}"],"platformRoles":["library-curator"]}""", session).exchange()
        assertThat(group).hasStatus(HttpStatus.CREATED)
        assertThat(group).bodyJson().extractingPath("$.memberCount").isEqualTo(1)
        assertThat(mvc.get().uri("/api/v1/admin/groups/${idOf(group.response.contentAsString)}").cookie(session)).hasStatusOk()

        assertThat(post("/api/v1/admin/roles", """{"key":"release-${UUID.randomUUID().toString().take(8)}","name":"Release","scope":"PROJECT","permissions":["run.approve"]}""", session))
            .hasStatus(HttpStatus.CREATED)
        assertThat(post("/api/v1/admin/roles", """{"key":"bad-role","name":"Bad","scope":"PLATFORM","permissions":["no.such"]}""", session)).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(delete("/api/v1/admin/roles/owner", session)).hasStatus(HttpStatus.CONFLICT)
        assertThat(mvc.get().uri("/api/v1/admin/roles?scope=PROJECT").cookie(member().first)).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().contains("owner")

        assertThat(put("/api/v1/admin/grants", """{"level":"USER","targetId":"${user.id}","grants":[{"permission":"usage.view","effect":"ALLOW"}]}""", session)).hasStatusOk()
        assertThat(mvc.get().uri("/api/v1/admin/grants?level=USER&targetId=${user.id}").cookie(session)).hasStatusOk().bodyJson()
            .extractingPath("$[0].grants[0].permission").isEqualTo("usage.view")
        assertThat(auditCount("GRANT_CHANGE", user.id.toString())).isEqualTo(1)
        // the change takes effect on the next request
        assertThat(mvc.get().uri("/api/v1/me").cookie(identity.login(user).first)).bodyJson().extractingPath("$.platformPermissions").asArray().contains("usage.view", "library.manage")

        assertThat(put("/api/v1/admin/settings", """{"registration":"INVITE_ONLY","allowedEmailDomains":["example.org"]}""", session)).hasStatusOk()
            .bodyJson().extractingPath("$.allowedEmailDomains").asArray().containsExactly("example.org")
        assertThat(mvc.get().uri("/api/v1/admin/permissions").cookie(session)).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().contains("admin.users.manage", "run.start")
    }

    @Test
    fun `project members are managed with project members manage and the last owner stays`() {
        val owner = identity.user()
        val project = UUID.randomUUID()
        gate.asSystem("test") { provisioner.grantCreator(project, "PRJ${project.toString().take(4).uppercase()}", Actor(PrincipalKind.USER, owner.id.value, "Owner")) }
        val ownerSession = identity.login(owner).first
        val dev = identity.user()
        val added = post("/api/v1/projects/$project/members", """{"principalType":"USER","principalId":"${dev.id}","role":"developer"}""", ownerSession).exchange()
        assertThat(added).hasStatus(HttpStatus.CREATED)
        assertThat(mvc.get().uri("/api/v1/projects/$project/members").cookie(identity.login(dev).first)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(2)
        assertThat(mvc.get().uri("/api/v1/users/lookup?q=${dev.email.take(8)}").cookie(ownerSession)).hasStatusOk()
        assertThat(mvc.get().uri("/api/v1/users/lookup?q=xx").cookie(identity.login(dev).first)).hasStatus(HttpStatus.FORBIDDEN)

        val ownerMembership = jdbc.sql("SELECT id FROM identity_project_membership WHERE project_id = :p AND role_key = 'owner'").param("p", project).query(UUID::class.java).single()
        assertThat(delete("/api/v1/projects/$project/members/$ownerMembership", ownerSession)).hasStatus(HttpStatus.CONFLICT)
        assertThat(put("/api/v1/projects/$project/members/$ownerMembership", """{"role":"viewer"}""", ownerSession)).hasStatus(HttpStatus.CONFLICT)
        assertThat(mvc.get().uri("/api/v1/me").cookie(identity.login(dev).first)).bodyJson().extractingPath("$.projects[0].roles").asArray().containsExactly("developer")
        assertThat(delete("/api/v1/projects/$project/members/${idOf(added.response.contentAsString)}", identity.login(dev).first)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the audit log is searchable with a keyset cursor and the search is audited`() {
        val session = admin()
        repeat(3) { post("/api/v1/admin/groups", """{"name":"Audit ${UUID.randomUUID()}"}""", session).exchange() }
        val first = mvc.get().uri("/api/v1/admin/audit?operation=GROUP_CREATE&size=2").cookie(session).exchange()
        assertThat(first).hasStatusOk()
        val cursor = json.readTree(first.response.contentAsString).get("nextCursor").asString()
        assertThat(first).bodyJson().extractingPath("$.content.length()").isEqualTo(2)
        val second = mvc.get().uri("/api/v1/admin/audit?operation=GROUP_CREATE&size=2&cursor=$cursor").cookie(session).exchange()
        assertThat(second).hasStatusOk().bodyJson().extractingPath("$.content.length()").asNumber().isNotEqualTo(0)
        assertThat(mvc.get().uri("/api/v1/admin/audit?cursor=garbage").cookie(session)).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(auditCount("AUDIT_VIEW")).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `an audit row is rolled back together with the business change`() {
        val admin = identity.user("rollback-${UUID.randomUUID()}@example.com", "platform-admin")
        val name = "Rolled back ${UUID.randomUUID()}"
        runCatching {
            gate.runAs(loader.forUser(admin.id)!!) {
                TransactionTemplate(tm).executeWithoutResult {
                    groups.create(ManageGroups.GroupCommand(name))
                    error("boom after the change")
                }
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM identity_group WHERE name = :n").param("n", name).query(Long::class.javaObjectType).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE operation = 'GROUP_CREATE' AND details ->> 'name' = :n").param("n", name).query(Long::class.javaObjectType).single()).isZero()

        gate.runAs(loader.forUser(admin.id)!!) { groups.create(ManageGroups.GroupCommand(name)) }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE operation = 'GROUP_CREATE' AND details ->> 'name' = :n AND actor_id = :a")
            .param("n", name).param("a", admin.id.value).query(Long::class.javaObjectType).single()).isEqualTo(1)
    }
}
