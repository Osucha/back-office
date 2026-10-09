package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** ADR 0034 through the REST layer: optional, multiple project repositories, the deprecated field and step validation. */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class ProjectRepositoriesRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun create(body: String): Map<*, *> = json.readValue(
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString,
        Map::class.java,
    )

    private fun workflowNames(projectId: Any?): List<Any?> = json.readValue(
        mvc.perform(get("/api/v1/projects/$projectId/workflows")).andExpect(status().isOk).andReturn().response.contentAsString, List::class.java,
    ).map { (it as Map<*, *>)["name"] }

    @Test
    fun `a project is created with several repositories, the deprecated field is the primary one`() {
        val key = key("MRP")
        val project = create(
            """{"key":"$key","name":"Multi","specKit":true,"defaults":{"modelProfileId":"${catalog.add()}"},"repositories":[
                {"key":"app","url":"https://git.example.com/acme/app.git","purpose":"SOURCE","primary":true},
                {"key":"docs","url":"https://git.example.com/acme/docs.git","defaultBranch":"develop","purpose":"DOCS","description":"User guide"}]}""",
        )
        assertThat((project["repositories"] as List<*>).map { (it as Map<*, *>)["key"] }).containsExactly("app", "docs")
        assertThat((project["repository"] as Map<*, *>)["url"]).isEqualTo("https://git.example.com/acme/app.git")
        mvc.perform(get("/api/v1/projects/${project["id"]}"))
            .andExpect(jsonPath("$.repositories[1].defaultBranch").value("develop"))
            .andExpect(jsonPath("$.repositories[1].purpose").value("DOCS"))
            .andExpect(jsonPath("$.repositories[1].description").value("User guide"))
            .andExpect(jsonPath("$.repositories[1].primary").value(false))
        assertThat(workflowNames(project["id"])).contains("Issue to reviewed MR", "CI code review")

        // an entrypoint may be restricted to one repository, not to an unknown one
        val review = json.readValue(mvc.perform(get("/api/v1/projects/${project["id"]}/ci-entrypoints")).andReturn().response.contentAsString, List::class.java)
            .map { it as Map<*, *> }.single()
        mvc.perform(
            put("/api/v1/projects/${project["id"]}/ci-entrypoints").contentType(MediaType.APPLICATION_JSON)
                .content("""[{"key":"code-review","workflowId":"${review["workflowId"]}","repository":"docs"}]"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$[0].repository").value("docs"))
        mvc.perform(
            put("/api/v1/projects/${project["id"]}/ci-entrypoints").contentType(MediaType.APPLICATION_JSON)
                .content("""[{"key":"code-review","workflowId":"${review["workflowId"]}","repository":"web"}]"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `the deprecated single repository is still accepted and a project may have none`() {
        val legacy = create("""{"key":"${key("LEG")}","name":"Legacy","repository":{"url":"https://git.example.com/acme/legacy.git","defaultBranch":"trunk"}}""")
        assertThat((legacy["repositories"] as List<*>).single()).isEqualTo(
            mapOf("key" to "main", "url" to "https://git.example.com/acme/legacy.git", "defaultBranch" to "trunk", "purpose" to "SOURCE", "description" to null, "primary" to true),
        )

        val llmOnly = create("""{"key":"${key("LLM")}","name":"Just LLM work"}""")
        assertThat(llmOnly["repositories"] as List<*>).isEmpty()
        assertThat(llmOnly["repository"]).isNull()
        assertThat(llmOnly["ciEntrypoints"] as List<*>).isEmpty()
        assertThat(workflowNames(llmOnly["id"])).containsExactly("LLM task with review")
    }

    @Test
    fun `invalid repository lists are rejected`() {
        listOf(
            """[{"key":"app","url":"https://x/a.git"},{"key":"app","url":"https://x/b.git"}]""",
            """[{"key":"a","url":"https://x/a.git","primary":true},{"key":"b","url":"https://x/b.git","primary":true}]""",
            """[{"key":"App","url":"https://x/a.git"}]""",
            """[{"key":"app","url":""}]""",
            """[{"key":"app","url":"https://x/a.git","purpose":"CODE"}]""",
        ).forEach { repositories ->
            mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""{"key":"${key("BAD")}","name":"Bad","repositories":$repositories}"""))
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `a coding step in a project without repositories is a validation error, an LLM step is fine`() {
        tasks.register(
            ResolvedTask(
                "rest-implement", "Implement", TaskExecutor.IMPLEMENT, null, TaskPrompt(null, "x"), emptyList(), emptyMap(), null, null,
                emptyList(), emptyList(), emptyList(), listOf("rest-implement"), listOf("success", "failure"),
            ),
        )
        val project = create("""{"key":"${key("NRP")}","name":"No repo","defaults":{"modelProfileId":"${catalog.add()}"}}""")
        fun workflow(template: String) = """{"name":"wf","nodes":[
            {"id":"start","kind":"START","name":"Start"},
            {"id":"step","kind":"TASK","name":"Step","task":{"templateKey":"$template"}},
            {"id":"done","kind":"END","name":"Done","end":{"outcome":"SUCCESS"}}],
            "edges":[{"id":"e1","from":"start","fromPort":"out","to":"step"},{"id":"e2","from":"step","fromPort":"success","to":"done"}]}"""

        mvc.perform(post("/api/v1/workflows/validate").param("projectId", project["id"].toString()).contentType(MediaType.APPLICATION_JSON).content(workflow("rest-implement")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[0].nodeId").value("step"))
            .andExpect(jsonPath("$.errors[0].field").value("task.config.repositories"))
        mvc.perform(post("/api/v1/workflows/validate").param("projectId", project["id"].toString()).contentType(MediaType.APPLICATION_JSON).content(workflow("code-review")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
    }
}
