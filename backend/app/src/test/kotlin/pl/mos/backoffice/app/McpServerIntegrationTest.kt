package pl.mos.backoffice.app

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.service.TokenCodec
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant

/**
 * The MCP server (ADR 0025) through the Spring AI / MCP Java SDK client over streamable HTTP: a personal access
 * token authenticates, tools run through the same use cases (and `AuthorizationGate`) as REST, anything but a
 * PAT is rejected with 401 before the transport.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "backoffice.runner.mode=DISABLED",
        // application pods do not migrate (ADR 0023); the test database is migrated by this context
        "spring.flyway.enabled=true",
        "backoffice.evaluation.seed-starter-content=false",
        "backoffice.auth.bootstrap-admins=mcp-admin@example.com",
        "backoffice.auth.cookie-secure=false",
        "backoffice.security.runner-token-secret=test-secret",
        "backoffice.auth.local.secret-key=dGVzdC1vbmx5LWxvY2FsLWF1dGgta2V5LTMyLWJ5dGU=",
        "spring.ai.mcp.client.enabled=false",
    ],
)
@Import(PostgresTestSupport::class)
class McpServerIntegrationTest {

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var resolveLogin: ResolveLogin
    @Autowired lateinit var tokens: PersonalTokenRepository
    @Autowired lateinit var restClientBuilder: RestClient.Builder
    @Autowired lateinit var json: JsonMapper

    private fun admin(): User {
        val result = resolveLogin.resolve(ResolveLogin.LoginClaims("https://idp.test", "sub-mcp-admin", "mcp-admin@example.com", true, "Mia MCP", null))
        return (result as ResolveLogin.LoginResult.Success).user
    }

    private fun pat(user: User, gateDecisions: Boolean = false, workflowEdits: Boolean = false): String {
        val generated = TokenCodec.generate()
        tokens.save(
            PersonalAccessToken(
                TokenId.new(), user.id, "mcp", generated.hash, generated.prefix, emptySet(), Instant.now().plus(Duration.ofHours(1)), Instant.now(),
                allowMcpGateDecisions = gateDecisions,
                allowMcpWorkflowEdits = workflowEdits,
            ),
        )
        return generated.plaintext
    }

    private fun client(token: String): McpSyncClient {
        val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$port")
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $token"))
            .build()
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build().also { it.initialize() }
    }

    private fun McpSyncClient.call(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val result = callTool(McpSchema.CallToolRequest(tool, arguments))
        val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("") { it.text() }
        assertThat(result.isError).describedAs("$tool: $text").isNotEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        return json.readValue(text, Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `a personal token lists the tools, starts a workflow and reads the run and the caller's turns`() {
        val bearer = pat(admin(), workflowEdits = true)
        val rest = restClientBuilder.baseUrl("http://localhost:$port").defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $bearer").build()
        val created = rest.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
            .body("""{"key":"MCP","name":"MCP demo","repository":{"url":"https://git.example.com/mcp.git","defaultBranch":"main"},"constitution":"Small, reviewed changes."}""")
            .retrieve().toEntity(String::class.java)
        assertThat(created.statusCode.is2xxSuccessful).isTrue()

        client(bearer).use { mcp ->
            val tools = mcp.listTools().tools().map { it.name() }
            assertThat(tools).contains(
                "list_my_turns", "get_run", "get_artifact", "start_workflow", "decide_gate", "search_task_library", "get_project_context", "get_delivery_insights",
            )

            val context = mcp.call("get_project_context", mapOf("projectKey" to "MCP"))
            assertThat(context["constitution"]).isEqualTo("Small, reviewed changes.")
            // ADR 0050: the seeded workflows need a model the test platform does not have, so the run uses a workflow
            // without task steps; the model plan of a seeded one says why it would not start
            val seeded = (context["workflows"] as List<*>).map { it as Map<*, *> }.first()["id"].toString()
            val plan = mcp.call("get_model_plan", mapOf("workflowId" to seeded))
            assertThat(plan["startable"]).isEqualTo(false)
            assertThat(((plan["steps"] as List<*>).first() as Map<*, *>)["problem"].toString()).contains("no model profile")
            val minimal = """{"name":"Minimal","nodes":[{"id":"start","kind":"START","name":"Start"},
                {"id":"done","kind":"END","name":"Done","end":{"outcome":"SUCCESS"}}],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"done"}]}"""
            val workflowId = mcp.call("create_workflow", mapOf("projectKey" to "MCP", "definition" to minimal))["id"].toString()

            val started = mcp.call("start_workflow", mapOf("workflowId" to workflowId, "issueKey" to "MCP-1", "parameters" to """{"title":"From MCP"}"""))
            val runId = started["runId"].toString()
            assertThat(started["issueKey"]).isEqualTo("MCP-1")

            val run = mcp.call("get_run", mapOf("runId" to runId))
            assertThat(run["runId"]).isEqualTo(runId)
            assertThat(run["projectKey"]).isEqualTo("MCP")
            assertThat(run).containsKeys("status", "nodes", "artifacts")

            val turns = mcp.call("list_my_turns", emptyMap())
            assertThat(turns).containsKeys("gates", "failedRuns")

            val insights = mcp.call("get_delivery_insights", mapOf("projectKey" to "MCP"))
            assertThat(insights).containsKeys("runs", "humanWait", "leadTime")

            // permissions apply: an unknown run is a tool error, not a transport failure
            val missing = mcp.callTool(McpSchema.CallToolRequest("get_run", mapOf("runId" to "00000000-0000-0000-0000-000000000000")))
            assertThat(missing.isError).isTrue()
        }
    }

    @Test
    fun `an agent reads a workflow, validates it and creates a new one, edits need the token option (ADR 0049)`() {
        val user = admin()
        val bearer = pat(user, workflowEdits = true)
        val rest = restClientBuilder.baseUrl("http://localhost:$port").defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $bearer").build()
        rest.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
            .body("""{"key":"MCPWF","name":"MCP workflows","repository":{"url":"https://git.example.com/mcpwf.git","defaultBranch":"main"}}""")
            .retrieve().toBodilessEntity()

        client(bearer).use { mcp ->
            val projects = (mcp.call("list_projects", emptyMap())["projects"] as List<*>).map { (it as Map<*, *>)["key"] }
            assertThat(projects).contains("MCPWF")
            val palette = mcp.call("get_workflow_palette", mapOf("projectKey" to "MCPWF"))
            assertThat((palette["controlNodes"] as List<*>).map { (it as Map<*, *>)["kind"] }).contains("HUMAN_GATE", "DECISION")
            assertThat(palette["tasks"] as List<*>).isNotEmpty()

            val workflowId = ((mcp.call("get_project_context", mapOf("projectKey" to "MCPWF"))["workflows"] as List<*>).first() as Map<*, *>)["id"].toString()
            val existing = mcp.call("get_workflow", mapOf("workflowId" to workflowId))
            @Suppress("UNCHECKED_CAST")
            val definition = (existing["definition"] as Map<String, Any?>).toMutableMap()
            assertThat(definition["nodes"] as List<*>).isNotEmpty()

            definition["name"] = "Copy through MCP"
            val text = json.writeValueAsString(definition)
            assertThat(mcp.call("validate_workflow", mapOf("projectKey" to "MCPWF", "definition" to text))["valid"]).isEqualTo(true)
            val created = mcp.call("create_workflow", mapOf("projectKey" to "MCPWF", "definition" to text))
            assertThat(created["created"]).isEqualTo(true)
            assertThat(created["version"]).isEqualTo(1)

            // an update based on an outdated version is refused, so a concurrent edit is not overwritten
            val stale = mcp.callTool(McpSchema.CallToolRequest("update_workflow", mapOf("workflowId" to created["id"], "definition" to text, "expectedVersion" to 7)))
            assertThat(stale.isError).isTrue()
            val updated = mcp.call("update_workflow", mapOf("workflowId" to created["id"], "definition" to text, "expectedVersion" to 1))
            assertThat(updated["version"]).isEqualTo(2)

            // an invalid graph is reported, not saved
            val broken = json.writeValueAsString(definition + ("edges" to emptyList<Any>()))
            val refusedGraph = mcp.call("create_workflow", mapOf("projectKey" to "MCPWF", "definition" to broken))
            assertThat(refusedGraph["created"]).isEqualTo(false)
            assertThat(refusedGraph["errors"] as List<*>).isNotEmpty()

            // run workspaces (ADR 0044) through MCP
            val repositoryKey = ((mcp.call("get_project_context", mapOf("projectKey" to "MCPWF"))["repositories"] as List<*>).first() as Map<*, *>)["key"]
            val workspace = mcp.call(
                "create_workspace",
                mapOf("projectKey" to "MCPWF", "key" to "main", "name" to "Main", "sources" to listOf(repositoryKey), "roles" to listOf("implementer")),
            )
            assertThat(workspace["sources"]).isEqualTo(listOf(repositoryKey))
            val changed = mcp.call("update_workspace", mapOf("workspaceId" to workspace["id"], "key" to "main", "sources" to listOf(repositoryKey), "setupCommand" to "npm ci"))
            assertThat(changed["setupCommand"]).isEqualTo("npm ci")
            val listed = (mcp.call("list_workspaces", mapOf("projectKey" to "MCPWF"))["workspaces"] as List<*>).map { (it as Map<*, *>)["key"] }
            assertThat(listed).containsExactly("main")
        }
        client(pat(user)).use { mcp ->
            val refused = mcp.callTool(McpSchema.CallToolRequest("create_workflow", mapOf("projectKey" to "MCPWF", "definition" to "{}")))
            assertThat(refused.isError).isTrue()
            assertThat(refused.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }).contains("Allow workflow edits via MCP")
        }
    }

    @Test
    fun `decide_gate is announced as destructive and needs a token created with the MCP gate option (ADR 0029)`() {
        val user = admin()
        val unknownApproval = "00000000-0000-0000-0000-0000000000aa"
        client(pat(user)).use { mcp ->
            val tool = mcp.listTools().tools().single { it.name() == "decide_gate" }
            assertThat(tool.annotations().destructiveHint()).isTrue()
            assertThat(tool.annotations().idempotentHint()).isFalse()
            assertThat(mcp.listTools().tools().single { it.name() == "get_artifact" }.annotations().readOnlyHint()).isTrue()

            val refused = mcp.callTool(McpSchema.CallToolRequest("decide_gate", mapOf("approvalId" to unknownApproval, "option" to "approve")))
            assertThat(refused.isError).isTrue()
            assertThat(refused.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }).contains("Allow gate decisions via MCP")
        }
        client(pat(user, gateDecisions = true)).use { mcp ->
            // with the option the call reaches the use case (here: the approval does not exist)
            val reached = mcp.callTool(McpSchema.CallToolRequest("decide_gate", mapOf("approvalId" to unknownApproval, "option" to "approve")))
            assertThat(reached.isError).isTrue()
            assertThat(reached.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }).doesNotContain("Allow gate decisions via MCP")
        }
    }

    @Test
    fun `only personal tokens reach the MCP endpoint`() {
        val http = restClientBuilder.baseUrl("http://localhost:$port").build()
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        val anonymous = http.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .body(body).exchange { _, res -> res.statusCode }
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED)
        val ci = http.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer bo_ci_" + "x".repeat(40))
            .body(body).exchange { _, res -> res.statusCode }
        assertThat(ci).isEqualTo(HttpStatus.UNAUTHORIZED)
        val invalidPat = http.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer bo_pat_" + "x".repeat(40))
            .body(body).exchange { _, res -> res.statusCode }
        assertThat(invalidPat).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the signing keys are public`() {
        val keys = restClientBuilder.baseUrl("http://localhost:$port").build().get().uri("/.well-known/back-office/keys").retrieve().toEntity(String::class.java)
        assertThat(keys.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(keys.body).contains("\"kty\":\"OKP\"", "\"crv\":\"Ed25519\"")
    }
}
