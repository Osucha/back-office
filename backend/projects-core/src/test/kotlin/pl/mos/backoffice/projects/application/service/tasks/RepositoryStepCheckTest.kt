package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.StepRepositories
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import java.util.UUID

/** ADR 0034: designer validation and run-start check of the repositories of the steps of a workflow. */
class RepositoryStepCheckTest {

    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? {
            val executor = when {
                key.startsWith("implement") -> TaskExecutor.IMPLEMENT
                key.startsWith("spec-kit") -> TaskExecutor.SPEC_KIT
                key == "fetch-issue" -> TaskExecutor.FETCH_ISSUE
                key == "transition-issue" -> TaskExecutor.TRANSITION_ISSUE
                else -> TaskExecutor.LLM
            }
            return ResolvedTask(
                key, key, executor, null, TaskPrompt(null, null), emptyList(), overrides.config, null, null,
                emptyList(), emptyList(), emptyList(), listOf(key), WorkflowNode.DEFAULT_TASK_PORTS,
            )
        }
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }
    private val check = RepositoryStepCheck(catalog)
    private val app = GitRepository("app", "https://git.example.com/acme/app.git", primary = true)
    private val docs = GitRepository("docs", "https://git.example.com/acme/docs.git", purpose = RepositoryPurpose.DOCS)

    private fun implement(config: Map<String, Any?>) =
        WorkflowNode.Task("impl", "Implement", task = TaskBinding("implement-opencode", TaskOverrides(config = config)))

    @Test
    fun `the seeded SDLC workflows are valid in a project with a repository and only the starter in one without`() {
        val project = Fixtures.project()
        DefaultWorkflows.all(project.id, Fixtures.now).forEach { wf ->
            assertThat(check.designIssues(project, wf.nodes).errors).describedAs(wf.name).isEmpty()
        }
        val repoLess = project.copy(repositories = emptyList())
        assertThat(check.designIssues(repoLess, DefaultWorkflows.llmTaskWithReview(project.id, Fixtures.now).nodes).errors).isEmpty()
        val sdlc = check.runBlockers(repoLess, DefaultWorkflows.issueToReviewedMr(project.id, Fixtures.now).nodes)
        assertThat(sdlc).hasSize(4).allSatisfy { assertThat(it).contains("has none") }
        assertThat(sdlc.first()).startsWith("step \"Specify\" (specify)")
    }

    @Test
    fun `explicit repositories are errors on unknown keys and pushes without push, warnings on DOCS plus SOURCE pushes`() {
        val project = Fixtures.project().copy(repositories = listOf(app, docs))
        val unknown = check.designIssues(project, listOf(implement(mapOf(StepRepositories.CONFIG_KEY to listOf(mapOf("key" to "web"))))))
        assertThat(unknown.errors.single().let { Triple(it.nodeId, it.field, it.message) })
            .isEqualTo(Triple("impl", StepRepositories.FIELD, unknown.errors.single().message))
        assertThat(unknown.errors.single().message).contains("web is not a repository")

        val noPush = check.designIssues(project, listOf(implement(mapOf("push" to false, StepRepositories.CONFIG_KEY to listOf(mapOf("key" to "app", "access" to "PUSH_MR"))))))
        assertThat(noPush.errors.single().message).contains("does not push")

        val both = check.designIssues(project, listOf(implement(mapOf(StepRepositories.CONFIG_KEY to listOf(mapOf("key" to "app", "access" to "PUSH_MR"), mapOf("key" to "docs", "access" to "PUSH_MR"))))))
        assertThat(both.errors).isEmpty()
        assertThat(both.warnings.single().field).isEqualTo(StepRepositories.FIELD)

        val llm = WorkflowNode.Task("ask", "Ask", task = TaskBinding("analyse-issue", TaskOverrides(config = mapOf(StepRepositories.CONFIG_KEY to listOf("app")))))
        assertThat(check.designIssues(project, listOf(llm)).errors.single().message).contains("only runner steps")
    }
}
