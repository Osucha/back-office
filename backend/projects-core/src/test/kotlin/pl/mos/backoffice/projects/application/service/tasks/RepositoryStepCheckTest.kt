package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.WorkflowSources
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.TestAgents
import java.util.UUID

/** ADR 0063: designer validation and run-start check of the workflow's sources, its coding steps and its deliveries. */
class RepositoryStepCheckTest {

    private val separate = TestAgents.OPENCODE.copy(key = "loner", access = AgentAccess(directory = AgentDirectory.SEPARATE))

    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? {
            val agent = when {
                overrides.agentKey == "loner" -> separate
                key.startsWith("implement") || key.startsWith("write-") || key == "agent-task" -> TestAgents.OPENCODE
                else -> TestAgents.MODEL_ANSWER
            }
            val config = if (key == "agent-task") overrides.config + ("repositoryOptional" to true) else overrides.config
            return ResolvedTask(
                key, key, agent, null, null, emptyList(), config, null, null,
                emptyList(), emptyList(), null, listOf(key), WorkflowNode.DEFAULT_TASK_PORTS,
            )
        }
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }
    private val check = RepositoryStepCheck(catalog)
    private val app = GitRepository("app", "https://git.example.com/acme/app.git", primary = true)
    private val docs = GitRepository("docs", "https://git.example.com/acme/docs.git", purpose = RepositoryPurpose.DOCS)

    private fun implement(agentKey: String? = null) =
        WorkflowNode.Task("impl", "Implement", task = TaskBinding("implement-opencode", TaskOverrides(agentKey = agentKey)))

    private fun deliver(config: Map<String, Any?> = emptyMap()) =
        WorkflowNode.Action("deliver", "Deliver changes", action = ActionBinding(ActionType.DELIVER_CHANGES, config))

    @Test
    fun `the seeded SDLC workflows are valid in a project with a repository and only the starter in one without`() {
        val project = Fixtures.project()
        DefaultWorkflows.all(project.id, Fixtures.now).forEach { wf ->
            assertThat(check.designIssues(project, wf.sources, wf.nodes).errors).describedAs(wf.name).isEmpty()
        }
        val repoLess = project.copy(repositories = emptyList())
        assertThat(check.designIssues(repoLess, emptyList(), DefaultWorkflows.llmTaskWithReview(project.id, Fixtures.now).nodes).errors).isEmpty()
        val sdlc = check.runBlockers(repoLess, emptyList(), DefaultWorkflows.issueToReviewedMr(project.id, Fixtures.now).nodes)
        // the four coding steps need a repository, the delivery has nothing to deliver
        assertThat(sdlc).hasSize(5)
        assertThat(sdlc.take(4)).allSatisfy { assertThat(it).contains("has none") }
        assertThat(sdlc.first()).startsWith("step \"Specify\" (specify)")
        assertThat(sdlc.last()).startsWith("step \"Deliver changes\" (implement-deliver)").contains("nothing to deliver")
    }

    @Test
    fun `unknown sources are errors of the workflow once a step works in them`() {
        val project = Fixtures.project().copy(repositories = listOf(app, docs))
        val unknown = check.designIssues(project, listOf(WorkflowSource("web")), listOf(implement()))
        assertThat(unknown.errors.single().let { it.field to it.nodeId }).isEqualTo(WorkflowSources.FIELD to null)
        assertThat(unknown.errors.single().message).contains("web is not a repository")
        // a workflow without coding steps or deliveries does not care
        val llm = WorkflowNode.Task("ask", "Ask", task = TaskBinding("analyse-issue"))
        assertThat(check.designIssues(project, listOf(WorkflowSource("web")), listOf(llm)).errors).isEmpty()
        assertThat(check.runBlockers(project, listOf(WorkflowSource("web")), listOf(implement())).single()).startsWith("sources of the workflow:")
    }

    @Test
    fun `a delivery names project repositories among the sources and needs an agent working in the run directory`() {
        val project = Fixtures.project().copy(repositories = listOf(app, docs))
        val sources = listOf(WorkflowSource("app"), WorkflowSource("docs"))
        assertThat(check.designIssues(project, sources, listOf(implement(), deliver(mapOf("repositories" to listOf("docs"))))).errors).isEmpty()
        val wrong = check.designIssues(project, emptyList(), listOf(implement(), deliver(mapOf("repositories" to listOf("docs")))))
        assertThat(wrong.errors.single().let { it.nodeId to it.field }).isEqualTo("deliver" to RepositoryStepCheck.FIELD_DELIVERY)
        assertThat(wrong.errors.single().message).contains("docs is not a project repository among the workflow's sources")

        val lonely = check.designIssues(project, emptyList(), listOf(implement("loner"), deliver()))
        assertThat(lonely.errors.single().message).contains("SEPARATE directory")
        // one agent in the run directory is enough
        assertThat(check.designIssues(project, emptyList(), listOf(implement("loner"), implement().copy(id = "impl2"), deliver())).errors).isEmpty()

        val repoLess = project.copy(repositories = emptyList())
        val agentTask = WorkflowNode.Task("agent", "Agent", task = TaskBinding("agent-task"))
        assertThat(check.runBlockers(repoLess, emptyList(), listOf(agentTask, deliver())).single()).contains("nothing to deliver")
        assertThat(check.runBlockers(repoLess, emptyList(), listOf(agentTask))).isEmpty()
    }
}
