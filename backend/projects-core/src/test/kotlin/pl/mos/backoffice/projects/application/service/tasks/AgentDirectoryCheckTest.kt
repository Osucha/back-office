package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import java.util.UUID

/** ADR 0062: the directory and the session are the agent's; the step check reports what still conflicts with a step. */
class AgentDirectoryCheckTest {

    /** Template keys say who runs them: `sep-*` an OpenCode agent with a SEPARATE directory, `oc-*` OpenCode, else a model answer. */
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask {
            val agent = when {
                key.startsWith("sep-") -> TestAgents.OPENCODE.copy(key = "loner", access = AgentAccess(directory = AgentDirectory.SEPARATE))
                key.startsWith("oc-") -> TestAgents.OPENCODE
                else -> TestAgents.MODEL_ANSWER
            }
            return ResolvedTask(
                key, key, agent, null, null, emptyList(), overrides.config + mapOf("push" to false), null, null,
                emptyList(), emptyList(), null, listOf(key), WorkflowNode.DEFAULT_TASK_PORTS,
            )
        }
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }
    private val check = RepositoryStepCheck(catalog)
    private val project = Fixtures.project()

    private fun step(id: String, template: String, config: Map<String, Any?> = emptyMap()) =
        WorkflowNode.Task(id, "Step $id", task = TaskBinding(template, TaskOverrides(config = config)))

    @Test
    fun `a prepare step whose agent works in a SEPARATE directory is a designer error and a run blocker`() {
        val nodes = listOf(step("prep", "sep-prepare", mapOf(RunDirectory.PREPARE_ONLY to true)))

        val issues = check.designIssues(project, emptyList(), nodes)

        assertThat(issues.errors.single().field).isEqualTo(RunDirectory.FIELD_PREPARE_ONLY)
        assertThat(issues.errors.single().message).contains("agent loner works in a SEPARATE directory")
        assertThat(check.runBlockers(project, emptyList(), nodes).single()).startsWith("step \"Step prep\" (prep): a prepare step")
    }

    @Test
    fun `a model answer step with prepareOnly is a warning and the former session keys raise nothing`() {
        val nodes = listOf(
            step("answer", "plain", mapOf(RunDirectory.PREPARE_ONLY to true)),
            step("code", "oc-code", mapOf("continueSession" to "answer", "session" to "CONTINUE", "directory" to "SEPARATE")),
            step("alone", "sep-review"),
        )

        val issues = check.designIssues(project, emptyList(), nodes)

        assertThat(issues.errors).isEmpty()
        assertThat(issues.warnings.single().nodeId).isEqualTo("answer")
        assertThat(issues.warnings.single().message).contains("only coding agent steps use prepareOnly")
        assertThat(check.runBlockers(project, emptyList(), nodes)).isEmpty()
    }
}
