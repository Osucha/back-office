package pl.mos.backoffice.projects.adapter.`in`.rest

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as httpGet

/** ADR 0033 / ADR 0030 follow-ups through REST: the Jira intake watcher endpoints and "Test before saving". */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class JiraIntakeRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort

    private val token = "ATATT3x-draft-secret"

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun read(body: String): Map<*, *> = json.readValue(body, Map::class.java)

    private fun MockHttpServletRequestBuilder.asMember(projectId: Any?, vararg permissions: String) =
        header("X-Test-Project-Permissions", "$projectId=${permissions.joinToString(",")}")

    private fun project(key: String): Map<*, *> {
        val body = """{"key":"$key","name":"P $key","repository":{"url":"https://git.example.com/g/$key.git","defaultBranch":"main"},
            "issueTracker":{"jiraProjectKey":"$key","jql":"labels = ai"},"defaults":{"modelProfileId":"${catalog.add()}"}}"""
        return read(mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString)
    }

    private fun workflow(pid: Any?, name: String, trigger: String): Map<*, *> {
        val body = """
            {"name":"$name","triggers":[$trigger],"nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"gate","kind":"HUMAN_GATE","name":"Go","position":{"x":1,"y":0},"gate":{"question":"Go?"}},
              {"id":"done","kind":"END","name":"Done","position":{"x":2,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"gate"},{"id":"e2","from":"gate","fromPort":"approve","to":"done"}]}
        """.trimIndent()
        return read(mvc.perform(post("/api/v1/projects/$pid/workflows").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString)
    }

    @Test
    fun `the watcher is configured by holders of project manage and shows its status, JQL and the reader it would use`() {
        val p = project(key("WAT"))
        val pid = p["id"]
        val manual = workflow(pid, "manual", """{"type":"MANUAL"}""")
        val jiraTriggered = workflow(pid, "from jira", """{"type":"JIRA_TRANSITION","status":"Ready for AI"}""")

        mvc.perform(httpGet("/api/v1/projects/$pid/jira-intake").asMember(pid, "project.view"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.pollIntervalMinutes").value(5))
            .andExpect(jsonPath("$.connection.status").value("NONE"))
            .andExpect(jsonPath("$.canManage").value(false))

        val enable = """{"enabled":true,"pollIntervalMinutes":2,"workflowId":"${jiraTriggered["id"]}"}"""
        mvc.perform(put("/api/v1/projects/$pid/jira-intake").asMember(pid, "project.view").contentType(MediaType.APPLICATION_JSON).content(enable))
            .andExpect(status().isForbidden)
        mvc.perform(
            put("/api/v1/projects/$pid/jira-intake").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true,"pollIntervalMinutes":2,"workflowId":"${manual["id"]}"}"""),
        ).andExpect(status().isBadRequest)
        mvc.perform(
            put("/api/v1/projects/$pid/jira-intake").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true,"pollIntervalMinutes":0,"workflowId":"${jiraTriggered["id"]}"}"""),
        ).andExpect(status().isBadRequest)

        mvc.perform(put("/api/v1/projects/$pid/jira-intake").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON).content(enable))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.workflowName").value("from jira"))
            .andExpect(jsonPath("$.mode").value("TRANSITION"))
            .andExpect(jsonPath("$.triggerStatus").value("Ready for AI"))
            .andExpect(jsonPath("$.jql").value(Matchers.containsString("AND (labels = ai) AND status CHANGED TO \"Ready for AI\" AFTER -4m")))
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("refuses to poll")))
            .andExpect(jsonPath("$.canManage").value(true))

        mvc.perform(post("/api/v1/projects/$pid/jira-intake/poll").asMember(pid, "project.view")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/projects/$pid/jira-intake/poll").asMember(pid, "project.view", "project.manage"))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.status.nextPollAt").exists())
        assertThat(audit.operations()).contains("JIRA_INTAKE_UPDATE", "JIRA_INTAKE_POLL_NOW")
    }

    @Test
    fun `a draft connection is tested without being stored and the secret never comes back`() {
        val p = project(key("DRF"))
        val pid = p["id"]
        val jiraKey = p["key"].toString()
        jira.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"accountId":"a","displayName":"Draft Bot"}""")))
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/mypermissions")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody("""{"permissions":{"BROWSE_PROJECTS":{"havePermission":true},"ADD_COMMENTS":{"havePermission":true}}}"""),
            ),
        )
        val draft = """{"connection":{"type":"JIRA","name":"Draft reader","siteUrl":"${jira.baseUrl()}","accountEmail":"reader@acme.com","secret":"$token",
            "capabilities":["ISSUE_READ"],"allowedProjectKeys":["$jiraKey"],"acknowledgeClassicToken":true}}"""

        mvc.perform(post("/api/v1/projects/$pid/connections/test").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON).content(draft))
            .andExpect(status().isForbidden)
        val result = mvc.perform(
            post("/api/v1/projects/$pid/connections/test").asMember(pid, "project.view", "project.connections.manage").contentType(MediaType.APPLICATION_JSON).content(draft),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("WARNING"))
            .andExpect(jsonPath("$.account.displayName").value("Draft Bot"))
            .andExpect(jsonPath("$.excess[0]").value("ADD_COMMENTS"))
            .andReturn().response.contentAsString
        assertThat(result).doesNotContain(token)
        mvc.perform(httpGet("/api/v1/projects/$pid/connections").asMember(pid, "project.view")).andExpect(jsonPath("$.length()").value(0))
        assertThat(audit.entries.filter { it.operation == "CONNECTION_TEST" }.joinToString { it.details.toString() }).doesNotContain(token).contains("Draft reader")
    }

    companion object {
        val jira: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stop() = jira.stop()
    }
}
