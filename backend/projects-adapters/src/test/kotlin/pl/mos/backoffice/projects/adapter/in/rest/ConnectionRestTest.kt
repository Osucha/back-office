package pl.mos.backoffice.projects.adapter.`in`.rest

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as httpGet

/**
 * ADR 0030 through the REST layer and the database: the secret is write-only (never in a response, never in plain text
 * in the table), project connections need `project.connections.manage`, platform ones `admin.connections.manage`, every
 * change is audited, verification runs on save against Jira (WireMock), and a workflow step uses the least-privileged
 * connection and says so in the run log.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class ConnectionRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort
    @Autowired lateinit var jdbc: JdbcTemplate

    private val token = "ATATT3xFfGF0-secret-reader-token"

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun read(body: String): Map<*, *> = json.readValue(body, Map::class.java)

    private fun createProject(key: String): Map<*, *> {
        val body = """{"key":"$key","name":"P $key","repository":{"url":"https://git.example.com/g/$key.git","defaultBranch":"main"},"defaults":{"modelProfileId":"${catalog.add()}"}}"""
        return read(mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString)
    }

    private fun connectionJson(name: String, capabilities: String, allowed: String, secret: String? = token) =
        """{"type":"JIRA","name":"$name","siteUrl":"${jira.baseUrl()}","accountEmail":"reader@acme.com",""" +
            (secret?.let { """"secret":"$it",""" } ?: "") +
            """"capabilities":[$capabilities],"allowedProjectKeys":[$allowed],"secretExpiresAt":"2027-09-30","acknowledgeClassicToken":true}"""

    private fun MockHttpServletRequestBuilder.asMember(projectId: Any?, vararg permissions: String) =
        header("X-Test-Project-Permissions", "$projectId=${permissions.joinToString(",")}")

    private fun MockHttpServletRequestBuilder.asPlatform(vararg permissions: String) = header("X-Test-Platform-Permissions", permissions.joinToString(","))

    private fun stubJira(projectKey: String, vararg granted: JiraPermission) {
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/myself")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody("""{"accountId":"acc-1","displayName":"Back Office Reader","emailAddress":"reader@acme.com"}"""),
            ),
        )
        val body = JiraPermission.entries.joinToString(prefix = """{"permissions":{""", postfix = "}}") { p -> """"${p.name}":{"havePermission":${p in granted}}""" }
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/mypermissions")).withQueryParam("projectKey", equalTo(projectKey))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)),
        )
    }

    @Test
    fun `a project connection is write-only, encrypted at rest, verified on save and audited`() {
        val project = createProject(key("CON"))
        val pid = project["id"]
        val jiraKey = project["key"].toString()
        stubJira(jiraKey, JiraPermission.BROWSE_PROJECTS, JiraPermission.DELETE_ISSUES)

        // a maintainer-like member (project.manage, no project.connections.manage) cannot create one
        mvc.perform(post("/api/v1/projects/$pid/connections").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON)
            .content(connectionJson("Jira reader", "\"ISSUE_READ\"", "\"$jiraKey\"")))
            .andExpect(status().isForbidden)

        val created = mvc.perform(
            post("/api/v1/projects/$pid/connections").asMember(pid, "project.view", "project.connections.manage").contentType(MediaType.APPLICATION_JSON)
                .content(connectionJson("Jira reader", "\"ISSUE_READ\"", "\"$jiraKey\"")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.scope").value("PROJECT"))
            .andExpect(jsonPath("$.secretSet").value(true))
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andExpect(jsonPath("$.capabilities[0]").value("ISSUE_READ"))
            .andExpect(jsonPath("$.verification.status").value("WARNING"))
            .andExpect(jsonPath("$.verification.account.displayName").value("Back Office Reader"))
            .andExpect(jsonPath("$.verification.excess[0]").value("DELETE_ISSUES"))
            .andExpect(jsonPath("$.verification.projects[0].granted.length()").value(2))
            .andExpect(jsonPath("$.createdBy.displayName").value("Mia Member"))
            .andReturn().response.contentAsString
        assertThat(created).doesNotContain(token)
        val id = read(created)["id"].toString()

        // at rest: only sealed values
        val row = jdbc.queryForMap("select secret_key_id, secret_wrapped_key, secret_ciphertext from connection where id = ?::uuid", id)
        assertThat(row["secret_key_id"]).isEqualTo("test")
        assertThat(row.values.joinToString()).doesNotContain(token)

        // reads by a viewer never show the secret either
        val listed = mvc.perform(httpGet("/api/v1/projects/$pid/connections").asMember(pid, "project.view"))
            .andExpect(status().isOk).andExpect(jsonPath("$[0].name").value("Jira reader")).andReturn().response.contentAsString
        assertThat(listed).doesNotContain(token)
        mvc.perform(post("/api/v1/projects/$pid/connections/$id/verify").asMember(pid, "project.view")).andExpect(status().isForbidden)

        // update without a secret keeps it, rotation replaces it
        mvc.perform(put("/api/v1/projects/$pid/connections/$id").contentType(MediaType.APPLICATION_JSON).content(connectionJson("Jira reader 2", "\"ISSUE_READ\"", "\"$jiraKey\"", secret = null)))
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("Jira reader 2"))
        val before = jdbc.queryForObject("select secret_ciphertext from connection where id = ?::uuid", String::class.java, id)
        mvc.perform(put("/api/v1/projects/$pid/connections/$id/secret").contentType(MediaType.APPLICATION_JSON).content("""{"secret":"rotated-token","secretExpiresAt":"2027-10-31"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.secretExpiresAt").value("2027-10-31"))
        assertThat(jdbc.queryForObject("select secret_ciphertext from connection where id = ?::uuid", String::class.java, id)).isNotEqualTo(before)

        val operations = audit.entries.filter { it.entityId == id }.map { it.operation }
        assertThat(operations).containsSubsequence("CONNECTION_CREATE", "CONNECTION_VERIFY", "CONNECTION_UPDATE", "CONNECTION_ROTATE")
        assertThat(audit.entries.filter { it.entityId == id }.joinToString { it.details.toString() }).doesNotContain(token).doesNotContain("rotated-token")

        mvc.perform(delete("/api/v1/projects/$pid/connections/$id")).andExpect(status().isNoContent)
        assertThat(audit.entries.last { it.entityId == id }.operation).isEqualTo("CONNECTION_DELETE")
        mvc.perform(httpGet("/api/v1/projects/$pid/connections/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `platform connections need admin connections manage and are listed for integrations view`() {
        val body = connectionJson("Platform reader ${UUID.randomUUID().toString().take(6)}", "\"ISSUE_READ\"", "")
        mvc.perform(post("/api/v1/admin/connections").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/connections").asPlatform("integrations.view").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        val created = read(
            mvc.perform(post("/api/v1/admin/connections").asPlatform("admin.connections.manage").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.scope").value("PLATFORM"))
                .andExpect(jsonPath("$.verification.messages[0].code").value(Matchers.oneOf("NO_PROJECT_TO_CHECK", "AUTHENTICATION_FAILED", "HTTP_ERROR")))
                .andReturn().response.contentAsString,
        )
        mvc.perform(httpGet("/api/v1/admin/connections").asPlatform("integrations.view"))
            .andExpect(status().isOk).andExpect(jsonPath("$[?(@.id == '${created["id"]}')].secretSet").value(true))
        mvc.perform(httpGet("/api/v1/admin/connections").asMember(UUID.randomUUID(), "project.view")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/connections").asPlatform("admin.connections.manage").contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("\"ISSUE_READ\"", "\"ISSUE_DELETE\"")))
            .andExpect(status().isBadRequest)
        mvc.perform(delete("/api/v1/admin/connections/${created["id"]}").asPlatform("admin.connections.manage")).andExpect(status().isNoContent)
    }

    @Test
    fun `a Jira step uses the least-privileged connection, and the designer reports missing ones`() {
        tasks.register(
            ResolvedTask(
                "jira-fetch-test", "Fetch issue", TaskExecutor.FETCH_ISSUE, null, TaskPrompt(null, null), emptyList(), emptyMap(), null, null,
                listOf("issue"), emptyList(), emptyList(), listOf("jira-fetch-test"), listOf("success", "failure"),
            ),
        )
        val project = createProject(key("RUN"))
        val pid = project["id"]
        val jiraKey = project["key"].toString()
        val workflow = """
            {"name":"jira fetch","nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"fetch","kind":"TASK","name":"Fetch","position":{"x":1,"y":0},"task":{"templateKey":"jira-fetch-test"}},
              {"id":"done","kind":"END","name":"Done","position":{"x":2,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"fetch"},{"id":"e2","from":"fetch","fromPort":"success","to":"done"}]}
        """.trimIndent()

        // without a connection: a warning on the node (the workflow saves), but a run refuses to start (ADR 0030 amendment)
        mvc.perform(post("/api/v1/workflows/validate?projectId=$pid").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.warnings[?(@.nodeId == 'fetch')].field").value(Matchers.hasItem("task.config.jiraConnection")))
        mvc.perform(httpGet("/api/v1/projects/$pid/connections/resolve?capabilities=ISSUE_READ")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("NONE"))
        val early = read(mvc.perform(post("/api/v1/projects/$pid/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow)).andExpect(status().isCreated).andReturn().response.contentAsString)
        mvc.perform(post("/api/v1/workflows/${early["id"]}/runs").contentType(MediaType.APPLICATION_JSON).content("""{"issueKey":"$jiraKey-1"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value(Matchers.containsString("integrations-jira#jira-connections")))
        mvc.perform(delete("/api/v1/workflows/${early["id"]}")).andExpect(status().isNoContent)

        // a writer first (over-privileged for a fetch), then a reader (exact)
        stubJira(jiraKey, JiraPermission.BROWSE_PROJECTS)
        mvc.perform(post("/api/v1/projects/$pid/connections").contentType(MediaType.APPLICATION_JSON)
            .content(connectionJson("Jira writer", "\"ISSUE_READ\",\"ISSUE_COMMENT\",\"ISSUE_TRANSITION\"", "\"$jiraKey\"")))
            .andExpect(status().isCreated)
        mvc.perform(post("/api/v1/workflows/validate?projectId=$pid").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.warnings[?(@.nodeId == 'fetch')].message").value(Matchers.hasItem(Matchers.containsString("more rights than needed"))))
        val reader = read(
            mvc.perform(post("/api/v1/projects/$pid/connections").contentType(MediaType.APPLICATION_JSON).content(connectionJson("Jira reader", "\"ISSUE_READ\"", "\"$jiraKey\"")))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )
        mvc.perform(httpGet("/api/v1/projects/$pid/connections/resolve?capabilities=ISSUE_READ"))
            .andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.connection.id").value(reader["id"].toString()))
        mvc.perform(httpGet("/api/v1/projects/$pid/connections/usable")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(2)))
        mvc.perform(httpGet("/api/v1/workflows/palette?projectId=$pid"))
            .andExpect(jsonPath("$.tasks[?(@.key == 'jira-fetch-test')].requiredCapabilities[0]").value("ISSUE_READ"))

        val created = read(mvc.perform(post("/api/v1/projects/$pid/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow)).andExpect(status().isCreated).andReturn().response.contentAsString)
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/issue/$jiraKey-1")).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                    .withBody("""{"key":"$jiraKey-1","fields":{"summary":"Add a dark mode","status":{"name":"To Do"}}}"""),
            ),
        )
        val run = read(mvc.perform(post("/api/v1/workflows/${created["id"]}/runs").contentType(MediaType.APPLICATION_JSON).content("""{"issueKey":"$jiraKey-1"}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)
        val runId = run["id"].toString()
        awaitStatus(runId, "SUCCEEDED")

        val logs = mvc.perform(httpGet("/api/v1/runs/$runId/logs")).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(logs).contains("Jira connection: Jira reader (project)").doesNotContain("more rights than needed")
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("reader@acme.com:$token".toByteArray())
        jira.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/issue/$jiraKey-1")).withHeader("Authorization", equalTo(basic)))
    }

    @Test
    fun `personal connections are visible only to their owner, administrators see metadata, projects cannot choose them`() {
        stubJira("SHOP", JiraPermission.BROWSE_PROJECTS)
        val anna = "00000000-0000-0000-0000-00000000a771"
        val bob = "00000000-0000-0000-0000-00000000b0b0"
        fun MockHttpServletRequestBuilder.asUser(id: String) = asPlatform("connections.personal").header("X-Test-User-Id", id)

        val mine = read(
            mvc.perform(post("/api/v1/me/connections").asUser(anna).contentType(MediaType.APPLICATION_JSON).content(connectionJson("Anna's Jira", "\"ISSUE_READ\"", "\"SHOP\"")))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.scope").value("USER"))
                .andExpect(jsonPath("$.ownerUserId").value(anna))
                .andExpect(jsonPath("$.tokenKind").value("CLASSIC"))
                .andReturn().response.contentAsString,
        )
        val id = mine["id"]
        mvc.perform(httpGet("/api/v1/me/connections").asUser(anna)).andExpect(status().isOk).andExpect(jsonPath("$[0].id").value(id))
        mvc.perform(httpGet("/api/v1/me/connections").asUser(bob)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        mvc.perform(httpGet("/api/v1/me/connections/$id").asUser(bob)).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/me/connections/$id").asUser(bob)).andExpect(status().isNotFound)
        mvc.perform(httpGet("/api/v1/admin/connections/$id").asPlatform("integrations.view", "admin.connections.manage")).andExpect(status().isNotFound)
        mvc.perform(httpGet("/api/v1/me/connections").asPlatform("integrations.view")).andExpect(status().isForbidden)

        val summary = mvc.perform(httpGet("/api/v1/admin/connections/personal").asPlatform("admin.connections.manage"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(summary).contains("Anna's Jira").doesNotContain("reader@acme.com").doesNotContain(token)

        // a project cannot choose a personal connection as system connection
        val project = createProject(key("PER"))
        val pid = project["id"]
        mvc.perform(
            put("/api/v1/projects/$pid/jira-connections").asMember(pid, "project.view", "project.connections.manage").contentType(MediaType.APPLICATION_JSON)
                .content("""{"systemConnectionId":"$id","additionalConnectionIds":[]}"""),
        ).andExpect(status().isBadRequest)
        mvc.perform(httpGet("/api/v1/projects/$pid/jira-connections").asMember(pid, "project.view"))
            .andExpect(status().isOk).andExpect(jsonPath("$.systemConnectionId").doesNotExist())
        mvc.perform(httpGet("/api/v1/connections/jira-scopes").asPlatform()).andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities.ISSUE_COMMENT[1].required", Matchers.hasItem("write:comment:jira")))
    }

    @Test
    fun `a project has Spec Kit off by default and can add the Spec Kit workflows after turning it on`() {
        val project = createProject(key("SPK"))
        val pid = project["id"]
        assertThat(project["specKit"]).isEqualTo(false)
        mvc.perform(httpGet("/api/v1/projects/$pid/spec-kit")).andExpect(status().isOk)
            .andExpect(jsonPath("$.effective").value(false)).andExpect(jsonPath("$.missingWorkflows[0]").value("Issue to reviewed MR"))
        mvc.perform(post("/api/v1/projects/$pid/spec-kit/workflows")).andExpect(status().isConflict)

        val update = """{"key":"${project["key"]}","name":"P","repository":{"url":"https://git.example.com/g/x.git","defaultBranch":"main"},"specKit":true}"""
        mvc.perform(put("/api/v1/projects/$pid").contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk).andExpect(jsonPath("$.specKit").value(true))
        mvc.perform(post("/api/v1/projects/$pid/spec-kit/workflows")).andExpect(status().isOk).andExpect(jsonPath("$[0].name").value("Issue to reviewed MR"))
        mvc.perform(httpGet("/api/v1/projects/$pid/spec-kit")).andExpect(jsonPath("$.missingWorkflows.length()").value(0))
    }

    private fun awaitStatus(runId: String, expected: String) {
        val deadline = System.currentTimeMillis() + 30_000
        var last: Any? = null
        while (System.currentTimeMillis() < deadline) {
            last = read(mvc.perform(httpGet("/api/v1/runs/$runId")).andExpect(status().isOk).andReturn().response.contentAsString)["status"]
            if (last == expected) return
            if (last == "FAILED") break
            Thread.sleep(100)
        }
        val logs = mvc.perform(httpGet("/api/v1/runs/$runId/logs")).andReturn().response.contentAsString
        throw AssertionError("run $runId is $last, expected $expected; logs: $logs")
    }

    companion object {
        val jira: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stop() = jira.stop()
    }
}
