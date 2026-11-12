package pl.mos.backoffice.projects.adapter.`in`.rest

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post as wirePost
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
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
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.shared.runner.ConnectorCatalog
import pl.mos.backoffice.shared.runner.RepositoryTokenSource
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as httpGet

/**
 * ADR 0064 through the REST layer and the database: the secret is write-only and sealed at rest, opens only for the
 * sealed release to a runner, platform connectors need `library.manage`, project ones `project.connections.manage`, every
 * change is audited and the test talks MCP to the server (WireMock) with the stored secret.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class ConnectorRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tokens: RepositoryTokenSource
    @Autowired lateinit var connectorCatalog: ConnectorCatalog

    private val secret = "tkn-mcp-secret-" + UUID.randomUUID().toString().take(8)

    private fun read(body: String): Map<*, *> = json.readValue(body, Map::class.java)

    private fun MockHttpServletRequestBuilder.asPlatform(vararg permissions: String) = header("X-Test-Platform-Permissions", permissions.joinToString(","))

    private fun MockHttpServletRequestBuilder.asMember(projectId: Any?, vararg permissions: String) =
        header("X-Test-Project-Permissions", "$projectId=${permissions.joinToString(",")}")

    private fun createProject(): Map<*, *> {
        val key = "CN" + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")
        val body = """{"key":"$key","name":"P $key","repository":{"url":"https://git.example.com/g/$key.git","defaultBranch":"main"},"defaults":{"modelProfileId":"${catalog.add()}"}}"""
        return read(mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString)
    }

    @Test
    fun `a platform MCP connector is write-only, sealed at rest, tested over MCP with its secret and released only to a runner`() {
        val name = "Docs " + UUID.randomUUID().toString().take(6)
        val input = """{"name":"$name","kind":"MCP_HTTP","scope":{"type":"GLOBAL"},"url":"${mcp.baseUrl()}/mcp","auth":"BEARER","secret":"$secret"}"""
        mvc.perform(post("/api/v1/connectors").asPlatform("library.view").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isForbidden)
        val created = mvc.perform(post("/api/v1/connectors").asPlatform("library.manage", "library.view").contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.scope.type").value("GLOBAL"))
            .andExpect(jsonPath("$.hasSecret").value(true))
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andExpect(jsonPath("$.host").value("localhost"))
            .andExpect(jsonPath("$.auth").value("BEARER"))
            .andExpect(jsonPath("$.updatedBy.displayName").value("Ada Admin"))
            .andReturn().response.contentAsString
        assertThat(created).doesNotContain(secret)
        val connector = read(created)
        val id = connector["id"].toString()
        val key = connector["key"].toString()
        assertThat(key).startsWith("docs-")

        val row = jdbc.queryForMap("select secret_key_id, secret_wrapped_key, secret_ciphertext from connector where id = ?::uuid", id)
        assertThat(row["secret_key_id"]).isEqualTo("test")
        assertThat(row.values.joinToString()).doesNotContain(secret)
        // the encryption round trip: only the release to a runner opens it, and only with the right key
        assertThat(tokens.openConnector(id, key)).isEqualTo(secret)
        assertThat(tokens.openConnector(id, "other")).isNull()
        assertThat(tokens.openConnector(UUID.randomUUID().toString(), key)).isNull()
        assertThat(tokens.openConnector("not-a-uuid", key)).isNull()
        assertThat(connectorCatalog.mcpConnectors(listOf(key), null).credentials.single().ref).isEqualTo(id)

        val listed = mvc.perform(httpGet("/api/v1/connectors").asPlatform("library.view")).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(listed).contains(id).doesNotContain(secret)
        mvc.perform(httpGet("/api/v1/connectors").asPlatform("integrations.view")).andExpect(status().isForbidden)

        val init = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"handbook"}}}"""
        mcp.stubFor(wirePost(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(init)))
        mcp.stubFor(wirePost(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("notifications/initialized"))).willReturn(aResponse().withStatus(202)))
        mcp.stubFor(wirePost(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search"}]}}""")))
        mvc.perform(post("/api/v1/connectors/$id/test").asPlatform("library.view")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/connectors/$id/test").asPlatform("library.manage"))
            .andExpect(status().isOk).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.tools[0]").value("search"))
        mcp.verify(postRequestedFor(urlPathEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer $secret")))
        mvc.perform(httpGet("/api/v1/connectors").asPlatform("library.view")).andExpect(jsonPath("$[?(@.id == '$id')].lastTest.ok").value(true))

        // update without a secret keeps it; the key cannot change
        mvc.perform(put("/api/v1/connectors/$id").asPlatform("library.manage").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"$name v2","kind":"MCP_HTTP","scope":{"type":"GLOBAL"},"url":"${mcp.baseUrl()}/mcp","auth":"BEARER"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.hasSecret").value(true)).andExpect(jsonPath("$.name").value("$name v2"))
        mvc.perform(put("/api/v1/connectors/$id").asPlatform("library.manage").contentType(MediaType.APPLICATION_JSON)
            .content("""{"key":"renamed","name":"x","kind":"MCP_HTTP","scope":{"type":"GLOBAL"},"url":"${mcp.baseUrl()}/mcp"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/connectors").asPlatform("library.manage").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Bad","kind":"MCP_HTTP","scope":{"type":"GLOBAL"},"url":"ftp://x"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/connectors").asPlatform("library.manage").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Bad","kind":"SHELL","scope":{"type":"GLOBAL"}}"""))
            .andExpect(status().isBadRequest)

        mvc.perform(delete("/api/v1/connectors/$id").asPlatform("library.manage")).andExpect(status().isNoContent)
        assertThat(tokens.openConnector(id, key)).isNull()
        val operations = audit.entries.filter { it.entityId == id }.map { it.operation }
        assertThat(operations).containsSubsequence("CONNECTOR_CREATE", "CONNECTOR_TEST", "CONNECTOR_UPDATE", "CONNECTOR_DELETE")
        assertThat(audit.entries.filter { it.entityId == id }.toString()).doesNotContain(secret)
    }

    @Test
    fun `project owners manage the project's GITLAB_READ connector, members only see it, the platform ones come along`() {
        val project = createProject()
        val pid = project["id"]
        val input = """{"name":"GitLab read","kind":"GITLAB_READ","scope":{"type":"PROJECT","projectId":"$pid"},"key":"gitlab-read","repository":""}"""
        mvc.perform(post("/api/v1/connectors").asMember(pid, "project.view", "project.manage").contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isForbidden)
        val created = read(
            mvc.perform(post("/api/v1/connectors").asMember(pid, "project.view", "project.connections.manage").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.key").value("gitlab-read"))
                .andExpect(jsonPath("$.kind").value("GITLAB_READ"))
                .andExpect(jsonPath("$.scope.projectId").value(pid.toString()))
                .andExpect(jsonPath("$.repository").doesNotExist())
                .andExpect(jsonPath("$.hasSecret").value(false))
                .andReturn().response.contentAsString,
        )
        mvc.perform(post("/api/v1/connectors").asMember(pid, "project.view", "project.connections.manage").contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isConflict)
        mvc.perform(httpGet("/api/v1/connectors").param("projectId", pid.toString()).asMember(pid, "project.view"))
            .andExpect(status().isOk).andExpect(jsonPath("$[?(@.key == 'gitlab-read')].kind").value("GITLAB_READ"))
        mvc.perform(post("/api/v1/connectors/${created["id"]}/test").asMember(pid, "project.view", "project.connections.manage"))
            .andExpect(status().isOk).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CI_READ")))
        mvc.perform(delete("/api/v1/connectors/${created["id"]}").asMember(pid, "project.view")).andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/connectors/${created["id"]}").asMember(pid, "project.view", "project.connections.manage")).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/connectors/${created["id"]}").asMember(pid, "project.view", "project.connections.manage")).andExpect(status().isNotFound)
    }

    companion object {
        val mcp: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stop() = mcp.stop()
    }
}
