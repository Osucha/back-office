package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** ADR 0037 through the REST layer and PostgreSQL: publish, list, adopt, local change, run stamping, update, detach, delete. */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class WorkflowLibraryRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort
    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var workflows: WorkflowDefinitionRepository
    @Autowired lateinit var runs: WorkflowRunRepository
    @Autowired lateinit var jdbc: JdbcClient

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun MockHttpServletRequestBuilder.asLibrarian() = header("X-Test-Extra-Platform-Permissions", "library.view,library.manage")

    private fun send(request: MockHttpServletRequestBuilder, body: String? = null): ResultActions =
        mvc.perform(if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).content(body))

    private fun body(result: ResultActions): Map<*, *> = json.readValue(result.andReturn().response.contentAsString, Map::class.java)

    private fun project(): Pair<String, String> {
        val p = body(send(post("/api/v1/projects"), """{"key":"${key("LIB")}","name":"Library user"}""").andExpect(status().isCreated))
        val workflow = json.readValue(send(get("/api/v1/projects/${p["id"]}/workflows")).andReturn().response.contentAsString, List::class.java)
            .map { it as Map<*, *> }.single()
        return p["id"] as String to workflow["id"] as String
    }

    @Test
    fun `a workflow is published, adopted elsewhere, measured per version and curated`() {
        tasks.llm("llm-task", "{{input}}")
        val (shop, shopWorkflow) = project()
        val (blog, _) = project()
        val libKey = "llm-review-" + UUID.randomUUID().toString().take(6)

        send(post("/api/v1/workflows/$shopWorkflow/publish"), """{"key":"$libKey"}""").andExpect(status().isForbidden)
        val published = body(
            send(post("/api/v1/workflows/$shopWorkflow/publish").asLibrarian(), """{"key":"$libKey","tags":["Review"],"changeNote":"first"}""")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.versions[0].changeNote").value("first"))
                .andExpect(jsonPath("$.adoptions[0].projectId").value(shop)),
        )
        val id = published["id"] as String

        send(get("/api/v1/library/workflows").asLibrarian()).andExpect(status().isOk)
        val v1 = body(send(get("/api/v1/library/workflows/$id/versions/1").asLibrarian()).andExpect(status().isOk).andExpect(jsonPath("$.key").value(libKey)))
        assertThat((v1["nodes"] as List<*>).size).isGreaterThanOrEqualTo(published["nodeCount"] as Int)
        send(get("/api/v1/workflows/$shopWorkflow")).andExpect(jsonPath("$.library.key").value(libKey)).andExpect(jsonPath("$.library.modified").value(false))

        val copy = body(
            send(post("/api/v1/projects/$blog/workflows/from-library").asLibrarian(), """{"libraryWorkflowId":"$id"}""")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.library.version").value(1))
                .andExpect(jsonPath("$.library.latestVersion").value(1)),
        )
        val copyId = copy["id"] as String
        send(get("/api/v1/projects/$blog/workflows")).andExpect(jsonPath("$[?(@.id == '$copyId')].library.name").value("LLM task with review"))

        // a run of the linked copy records the library version it ran
        val definition = workflows.findById(WorkflowDefinitionId.of(copyId))!!
        val run = runs.save(WorkflowRun.create(projects.findById(ProjectId.of(blog))!!, definition, RunTrigger(TriggerSource.MANUAL), RunInput(), Instant.now()))
        val stamped = jdbc.sql("SELECT library_workflow_id, library_version FROM workflow_run WHERE id = :id").param("id", run.id.value)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }.single()
        assertThat(stamped).isEqualTo(UUID.fromString(id) to 1)
        assertThat(runs.findById(run.id)!!.definitionSnapshot.library?.key).isEqualTo(libKey)

        // a new version, taken by the copy
        val shopDefinition = body(send(get("/api/v1/workflows/$shopWorkflow")))
        val changed = shopDefinition.toMutableMap().apply { this["description"] = "v2"; this["nodes"] = (this["nodes"] as List<*>).map { n -> renamed(n as Map<*, *>) } }
        send(put("/api/v1/workflows/$shopWorkflow"), json.writeValueAsString(changed)).andExpect(status().isOk).andExpect(jsonPath("$.library.modified").value(true))
        send(post("/api/v1/workflows/$shopWorkflow/publish").asLibrarian(), """{"libraryWorkflowId":"$id","changeNote":"renamed"}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.latestVersion").value(2))
        send(get("/api/v1/workflows/$copyId")).andExpect(jsonPath("$.library.latestVersion").value(2))
        send(post("/api/v1/workflows/$copyId/library/update"), "{}").andExpect(status().isOk).andExpect(jsonPath("$.library.version").value(2))

        // curation
        send(put("/api/v1/library/workflows/$id").asLibrarian(), """{"name":"LLM review","deprecated":true}""").andExpect(status().isOk).andExpect(jsonPath("$.deprecated").value(true))
        send(post("/api/v1/projects/$blog/workflows/from-library").asLibrarian(), """{"libraryWorkflowId":"$id"}""").andExpect(status().isConflict)
        send(delete("/api/v1/library/workflows/$id").asLibrarian()).andExpect(status().isConflict)
        send(post("/api/v1/workflows/$copyId/library/detach")).andExpect(status().isOk).andExpect(jsonPath("$.library").doesNotExist())
        send(post("/api/v1/workflows/$shopWorkflow/library/detach")).andExpect(status().isOk)
        send(delete("/api/v1/library/workflows/$id").asLibrarian()).andExpect(status().isNoContent)
        send(get("/api/v1/library/workflows/$id").asLibrarian()).andExpect(status().isNotFound)

        assertThat(audit.operations()).contains(
            "LIBRARY_WORKFLOW_PUBLISH", "WORKFLOW_ADOPT", "WORKFLOW_LIBRARY_UPDATE", "LIBRARY_WORKFLOW_UPDATE", "WORKFLOW_LIBRARY_DETACH", "LIBRARY_WORKFLOW_DELETE",
        )
    }

    /** The node with " (v2)" appended to its name: a graph change that makes a new library version. */
    private fun renamed(node: Map<*, *>): Map<*, *> =
        if (node["kind"] == "TASK") node.toMutableMap().apply { this["name"] = "${node["name"]} (v2)" } else node
}
