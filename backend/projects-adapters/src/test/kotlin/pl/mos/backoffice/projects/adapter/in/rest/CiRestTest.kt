package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.FakeLlmGateway
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.out.langgraph.LangGraphWorkflowEngine
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import tools.jackson.databind.json.JsonMapper

/**
 * REST slice against the real wiring: project creation seeds workflows and the code-review entrypoint,
 * CI tokens authenticate the CI endpoints (through this context's CiTokenVerifier and the test
 * authentication filter standing in for the identity context), a CI execution runs the review workflow
 * end-to-end and the gate result reflects the END outcome; workflow validation, palette and the client script.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class CiRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var llm: FakeLlmGateway
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var engine: LangGraphWorkflowEngine

    private fun createProject(key: String): Map<*, *> {
        val profile = catalog.add()
        val body = """{"key":"$key","name":"Shop","repository":{"url":"https://git.example.com/g/shop.git","defaultBranch":"main"},"defaults":{"modelProfileId":"$profile"},"specKit":true}"""
        val response = mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.key").value(key))
            .andExpect(jsonPath("$.stats.workflows").value(3))
            .andExpect(jsonPath("$.createdBy.displayName").value("Olivia Owner"))
            .andExpect(jsonPath("$.createdBy.kind").value("USER"))
            .andExpect(jsonPath("$.ciEntrypoints[0].key").value("code-review"))
            .andExpect(jsonPath("$.ciEntrypoints[0].mode").value("BLOCKING"))
            .andExpect(jsonPath("$.ciEntrypoints[0].workflowName").value("CI code review"))
            .andReturn().response.contentAsString
        return json.readValue(response, Map::class.java)
    }

    private fun createToken(projectId: Any?): String {
        val response = mvc.perform(post("/api/v1/projects/$projectId/ci-tokens").contentType(MediaType.APPLICATION_JSON).content("""{"name":"gitlab"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.prefix").value(org.hamcrest.Matchers.startsWith("bo_ci_")))
            .andExpect(jsonPath("$.createdBy.displayName").value("Olivia Owner"))
            .andReturn().response.contentAsString
        return json.readValue(response, Map::class.java)["token"].toString()
    }

    private fun ciRequest(sha: String, mrIid: String, diff: String = "diff --git a/A.kt b/A.kt\n+val x = 1") = """
        {"entrypoint":"code-review","diff":${json.writeValueAsString(diff)},"changedFiles":["A.kt"],
         "context":{"provider":"GITLAB","projectPath":"g/shop","pipelineId":"1","jobId":"2","ref":"feature/x","commitSha":"$sha",
                    "defaultBranch":"main","mergeRequest":{"iid":"$mrIid","title":"x","sourceBranch":"feature/x","targetBranch":"main"}}}
    """.trimIndent()

    private fun awaitExecution(token: String, id: Any?): Map<*, *> {
        val deadline = System.currentTimeMillis() + 30_000
        var execution: Map<*, *> = emptyMap<String, Any>()
        while (System.currentTimeMillis() < deadline) {
            execution = json.readValue(
                mvc.perform(get("/api/v1/ci/executions/$id").header("Authorization", "Bearer $token")).andExpect(status().isOk).andReturn().response.contentAsString,
                Map::class.java,
            )
            if (execution["status"] == "SUCCEEDED" || execution["status"] == "FAILED") {
                execution["runId"]?.let { engine.awaitIdle(WorkflowRunId.of(it.toString())) }
                return execution
            }
            Thread.sleep(200)
        }
        throw AssertionError("execution $id did not finish: $execution")
    }

    @Test
    fun `a CI execution runs the code-review workflow and the gate result follows the review verdict`() {
        val project = createProject("CI1")
        val token = createToken(project["id"])

        // blocking mode, review requests changes -> FAIL
        llm.reset()
        llm.answer("review", structured = mapOf("summary" to "needs work", "findings" to listOf(mapOf("severity" to "BLOCKER", "file" to "A.kt", "line" to 1, "title" to "NPE", "detail" to "null")), "verdict" to "REQUEST_CHANGES", "score" to 60))
        val first = mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(ciRequest("sha1", "7")))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.gateResult").value("PENDING"))
            .andExpect(jsonPath("$.entrypoint").value("code-review"))
            .andExpect(jsonPath("$.diffArtifactId").isNotEmpty)
            .andReturn().response.contentAsString
        val executionId = json.readValue(first, Map::class.java)["id"]
        val finished = awaitExecution(token, executionId!!)
        assertThat(finished["status"]).isEqualTo("SUCCEEDED")
        assertThat(finished["gateResult"]).isEqualTo("FAIL")
        assertThat((finished["report"] as Map<*, *>)["verdict"]).isEqualTo("REQUEST_CHANGES")
        assertThat(finished["summary"]).isEqualTo("needs work")
        assertThat(llm.prompts.single().messages.last().content).contains("+val x = 1")

        // idempotent: same commit and MR returns the same execution; force starts a new one
        mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(ciRequest("sha1", "7")))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.id").value(executionId.toString()))

        // the run behind the execution is visible to the owner with the diff artefact and the review
        mvc.perform(get("/api/v1/runs/${finished["runId"]}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.trigger.type").value("CI"))
            .andExpect(jsonPath("$.trigger.ciExecutionId").value(executionId.toString()))
            .andExpect(jsonPath("$.outcome").value("FAILURE"))
            .andExpect(jsonPath("$.input.ci.mergeRequest.iid").value("7"))
            .andExpect(jsonPath("$.review.verdict").value("REQUEST_CHANGES"))
            .andExpect(jsonPath("$.nodes[?(@.nodeId == 'verdict')].port").value("changes"))
            .andExpect(jsonPath("$.startedBy.kind").value("CI_TOKEN"))
            .andExpect(jsonPath("$.startedBy.displayName").value(org.hamcrest.Matchers.containsString("gitlab")))
        mvc.perform(get("/api/v1/runs/${finished["runId"]}/artifacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == 'diff')].kind").value("DIFF"))
            .andExpect(jsonPath("$[?(@.name == 'review')].kind").value("REVIEW"))

        // a clean review passes
        llm.reset()
        llm.answer("review", structured = mapOf("summary" to "looks good", "findings" to emptyList<Any>(), "verdict" to "APPROVE", "score" to 100))
        val second = mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(ciRequest("sha2", "7")))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val passed = awaitExecution(token, json.readValue(second, Map::class.java)["id"]!!)
        assertThat(passed["gateResult"]).isEqualTo("PASS")
        assertThat((passed["report"] as Map<*, *>)["verdict"]).isEqualTo("APPROVE")

        // advisory mode never fails the job
        mvc.perform(
            put("/api/v1/projects/${project["id"]}/ci-entrypoints").contentType(MediaType.APPLICATION_JSON)
                .content("""[{"key":"code-review","workflowId":"${(project["ciEntrypoints"] as List<*>).let { (it[0] as Map<*, *>)["workflowId"] }}","mode":"ADVISORY"}]"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$[0].mode").value("ADVISORY"))
        llm.reset()
        llm.answer("review", structured = mapOf("summary" to "bad", "findings" to listOf(mapOf("severity" to "BLOCKER", "file" to "A.kt", "title" to "boom", "detail" to "x")), "verdict" to "REQUEST_CHANGES", "score" to 60))
        val third = mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(ciRequest("sha3", "7")))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val advisory = awaitExecution(token, json.readValue(third, Map::class.java)["id"]!!)
        assertThat(advisory["gateResult"]).isEqualTo("PASS")
        assertThat((advisory["report"] as Map<*, *>)["verdict"]).isEqualTo("REQUEST_CHANGES")

        // owner listing and a foreign entrypoint
        mvc.perform(get("/api/v1/ci/executions?projectId=${project["id"]}")).andExpect(status().isOk).andExpect(jsonPath("$.totalElements").value(3))
        mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content("""{"entrypoint":"nope","context":{}}"""))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the run and CI execution listings take the sort fields of the contract and answer 400 for others (ADR 0046)`() {
        for (sort in listOf("status.asc", "entrypoint.desc", "ref.asc", "project.desc", "cost.asc", "createdAt.desc")) {
            mvc.perform(get("/api/v1/ci/executions?sort=$sort")).andExpect(status().isOk)
        }
        for (sort in listOf("status.desc", "workflow.asc", "project.asc", "startedBy.desc", "cost.desc", "createdAt.asc")) {
            mvc.perform(get("/api/v1/runs?sort=$sort")).andExpect(status().isOk)
        }
        mvc.perform(get("/api/v1/ci/executions?sort=workflow.asc")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/runs?sort=ref.desc")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/runs?sort=cost.sideways")).andExpect(status().isBadRequest)
    }

    @Test
    fun `CI endpoints reject missing, unknown, revoked and foreign tokens`() {
        val project = createProject("CI2")
        val token = createToken(project["id"])
        val other = createProject("CI3")
        val otherToken = createToken(other["id"])
        val body = ciRequest("sha1", "1")

        mvc.perform(post("/api/v1/ci/executions").header("X-Test-Anonymous", "1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        // a signed-in person is not a CI token: ci.execute / ci.signal are granted only to tokens
        mvc.perform(post("/api/v1/ci/executions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/ci/signals").contentType(MediaType.APPLICATION_JSON).content("""{"signal":"tests","status":"SUCCESS","context":{}}"""))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer bo_ci_" + "x".repeat(40)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/ci/signals").header("Authorization", "Basic nope").contentType(MediaType.APPLICATION_JSON).content("""{"signal":"tests","status":"SUCCESS","context":{}}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/ci/executions/00000000-0000-0000-0000-000000000000").header("X-Test-Anonymous", "1")).andExpect(status().isUnauthorized)

        llm.reset()
        llm.answer("review", structured = mapOf("summary" to "ok", "findings" to emptyList<Any>(), "verdict" to "APPROVE", "score" to 100))
        val created = mvc.perform(post("/api/v1/ci/executions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val id = json.readValue(created, Map::class.java)["id"]
        mvc.perform(get("/api/v1/ci/executions/$id").header("Authorization", "Bearer $otherToken")).andExpect(status().isForbidden)
        // a CI token only sees its own project in listings (it holds no project.view)
        mvc.perform(get("/api/v1/ci/executions").header("Authorization", "Bearer $otherToken")).andExpect(status().isOk).andExpect(jsonPath("$.totalElements").value(0))
        mvc.perform(get("/api/v1/ci/executions/$id").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        awaitExecution(token, id!!)

        val tokens = json.readValue(mvc.perform(get("/api/v1/projects/${project["id"]}/ci-tokens")).andExpect(status().isOk).andReturn().response.contentAsString, List::class.java)
        val tokenId = (tokens.single() as Map<*, *>)["id"]
        mvc.perform(delete("/api/v1/projects/${project["id"]}/ci-tokens/$tokenId")).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/ci/executions/$id").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/ci/signals").header("Authorization", "Bearer $otherToken").contentType(MediaType.APPLICATION_JSON).content("""{"signal":"tests","status":"SUCCESS","context":{}}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.resumed").value(false))
    }

    @Test
    fun `workflows validate, the palette lists control nodes and tasks, and expressions are checked`() {
        val project = createProject("WF1")
        val projectId = project["id"]
        val broken = """
            {"name":"broken","nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"review","kind":"TASK","name":"Review","position":{"x":1,"y":0},"task":{"templateKey":"nope"}},
              {"id":"d","kind":"DECISION","name":"D","position":{"x":2,"y":0},"decision":{"branches":[{"id":"a","label":"a","expression":"confidence >= "}]}},
              {"id":"end","kind":"END","name":"End","position":{"x":3,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"review"},{"id":"e2","from":"review","fromPort":"success","to":"d"}]}
        """.trimIndent()
        mvc.perform(post("/api/v1/workflows/validate?projectId=$projectId").contentType(MediaType.APPLICATION_JSON).content(broken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[?(@.field == 'task.templateKey')]").isNotEmpty)
            .andExpect(jsonPath("$.errors[?(@.field == 'decision.branches.a.expression')]").isNotEmpty)
            .andExpect(jsonPath("$.errors[?(@.field == 'ports.a')]").isNotEmpty)
        mvc.perform(post("/api/v1/workflows/validate?projectId=$projectId").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x","nodes":[{"id":"a","kind":"BOGUS","name":"A","position":{"x":0,"y":0}}],"edges":[]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(false))
        // a workflow with errors is saved as a draft
        mvc.perform(post("/api/v1/projects/$projectId/workflows").contentType(MediaType.APPLICATION_JSON).content(broken))
            .andExpect(status().isCreated).andExpect(jsonPath("$.draft").value(true))

        mvc.perform(get("/api/v1/workflows/palette?projectId=$projectId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.controlNodes.length()").value(6))
            .andExpect(jsonPath("$.controlNodes[?(@.kind == 'WAIT_FOR_CI')].ports[0]").value("success"))
            .andExpect(jsonPath("$.tasks[?(@.key == 'code-review')].interfaces[0]").value("Reviewer"))
            .andExpect(jsonPath("$.stateFields[?(@.path == 'review.verdict')]").isNotEmpty)
        // ADR 0054: the config schema of every executor, for the library editor
        mvc.perform(get("/api/v1/workflows/task-executors"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(pl.mos.backoffice.shared.tasks.TaskExecutor.entries.size))
            .andExpect(jsonPath("$[?(@.executor == 'IMPLEMENT')].configSchema.properties.agent.enum[1]").value("CLAUDE_CODE"))
        mvc.perform(post("/api/v1/workflows/expressions/validate").contentType(MediaType.APPLICATION_JSON).content("""{"expression":"review.verdict != 'REQUEST_CHANGES'"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true))
        mvc.perform(post("/api/v1/workflows/expressions/validate").contentType(MediaType.APPLICATION_JSON).content("""{"expression":"review.verdict !="}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.message").isNotEmpty)

        // seeded workflows round-trip through the REST shape and can be duplicated
        val list = json.readValue(mvc.perform(get("/api/v1/projects/$projectId/workflows")).andExpect(status().isOk).andReturn().response.contentAsString, List::class.java)
        val issueToMr = list.map { it as Map<*, *> }.first { it["name"] == "Issue to reviewed MR" }
        assertThat((issueToMr["nodes"] as List<*>).size).isEqualTo(16)
        assertThat(list.map { (it as Map<*, *>)["name"] }).contains("Change request (delta)")
        val mergeGate = (issueToMr["nodes"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "merge-gate" }["gate"] as Map<*, *>
        assertThat((mergeGate["approvers"] as Map<*, *>)["accountable"]).isEqualTo("owner")
        assertThat((mergeGate["approvers"] as Map<*, *>)["forbidSelfApproval"]).isEqualTo(true)
        assertThat((mergeGate["checklist"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly("tests-green", "review-findings-addressed", "spec-updated")
        assertThat((issueToMr["updatedBy"] as Map<*, *>)["displayName"]).isEqualTo("Olivia Owner")
        mvc.perform(post("/api/v1/workflows/${issueToMr["id"]}/duplicate")).andExpect(status().isCreated).andExpect(jsonPath("$.name").value("Issue to reviewed MR (copy)"))
        mvc.perform(get("/api/v1/projects/$projectId/ci-snippet"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scriptUrl").value("http://backoffice.test:8080/ci/backoffice-ci.sh"))
            .andExpect(jsonPath("$.variables[1]").value("BACKOFFICE_CI_TOKEN"))
    }

    @Test
    fun `the client script is served as a shell script and settings report the CI script url`() {
        mvc.perform(get("/ci/backoffice-ci.sh"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/x-shellscript")))
            .andExpect(content().string(org.hamcrest.Matchers.startsWith("#!/bin/sh")))
        mvc.perform(get("/api/v1/ci/script")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/settings/integrations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jira.configured").value(false))
            .andExpect(jsonPath("$.runner.mode").value("DISABLED"))
            .andExpect(jsonPath("$.ci.scriptUrl").value("http://backoffice.test:8080/ci/backoffice-ci.sh"))
            .andExpect(jsonPath("$.providers[?(@.provider == 'ANTHROPIC')].configured").value(true))
        mvc.perform(post("/api/v1/settings/integrations/nope/test")).andExpect(status().isNotFound)
    }
}
