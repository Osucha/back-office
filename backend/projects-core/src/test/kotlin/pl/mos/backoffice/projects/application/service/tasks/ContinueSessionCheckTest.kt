package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.SessionContinuation
import pl.mos.backoffice.projects.domain.service.StepWorkspace
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import java.util.UUID

/** ADR 0057 section 4: `continueSession` problems block the run and show in the designer. */
class ContinueSessionCheckTest {

    /** Template keys say who runs them: `oc-*` OpenCode, `cc-*` Claude Code, `prep-*` a prepare step, anything else a model answer. */
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask {
            val agent = when {
                key.startsWith("oc-") || key.startsWith("prep-") -> TestAgents.OPENCODE
                key.startsWith("cc-") -> TestAgents.CLAUDE_CODE
                else -> TestAgents.MODEL_ANSWER
            }
            val config = overrides.config + (if (key.startsWith("prep-")) mapOf(StepWorkspace.PREPARE_ONLY to true, "push" to false) else emptyMap())
            return ResolvedTask(
                key, key, agent, null, TaskPrompt(null, null), emptyList(), config, null, null,
                emptyList(), emptyList(), null, listOf(key), WorkflowNode.DEFAULT_TASK_PORTS,
            )
        }
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }
    private val check = RepositoryStepCheck(catalog)
    private val project = Fixtures.project()

    private fun step(id: String, template: String, continues: String? = null, config: Map<String, Any?> = emptyMap()) = WorkflowNode.Task(
        id, "Step $id", task = TaskBinding(template, TaskOverrides(config = config + (continues?.let { mapOf(SessionContinuation.KEY to it) } ?: emptyMap()))),
    )

    @Test
    fun `a coding agent step may continue an earlier step of the same harness`() {
        val nodes = listOf(step("implement", "oc-implement"), step("fix", "oc-fix", continues = "implement"))
        assertThat(check.designIssues(project, nodes).errors).isEmpty()
        assertThat(check.runBlockers(project, nodes)).isEmpty()
    }

    @Test
    fun `every kind of wrong continuation is a run blocker and a designer error on the continuing step`() {
        val nodes = listOf(
            step("implement", "oc-implement"),
            step("answer", "write-story"),
            step("prepare", "prep-workspace", config = mapOf(StepWorkspace.WORKSPACE to "run")),
            step("ghost", "oc-a", continues = "nowhere"),
            step("self", "oc-b", continues = "self"),
            step("llm", "write-other", continues = "implement"),
            step("to-llm", "oc-c", continues = "answer"),
            step("to-prepare", "oc-d", continues = "prepare"),
            step("harness", "cc-e", continues = "implement"),
            step("loop-a", "oc-f", continues = "loop-b"),
            step("loop-b", "oc-g", continues = "loop-a"),
        )
        val problems = check.runBlockers(project, nodes).filter { "continue" in it || "session" in it || "harness" in it }
        assertThat(problems).containsExactlyInAnyOrder(
            "step \"Step ghost\" (ghost): continueSession names step nowhere, which does not exist",
            "step \"Step self\" (self): a step cannot continue its own session",
            "step \"Step llm\" (llm): a model answer has no agent session to continue; remove continueSession",
            "step \"Step to-llm\" (to-llm): continueSession names step answer, which is not a coding agent step",
            "step \"Step to-prepare\" (to-prepare): continueSession names step prepare, a prepare step that starts no agent session",
            "step \"Step harness\" (harness): step implement uses OPENCODE and this step CLAUDE_CODE: a session cannot continue in another harness",
            "step \"Step loop-a\" (loop-a): continueSession forms a cycle",
            "step \"Step loop-b\" (loop-b): continueSession forms a cycle",
        )
        val errors = check.designIssues(project, nodes).errors.filter { it.field == SessionContinuation.FIELD }
        assertThat(errors.map { it.nodeId }).containsExactlyInAnyOrder("ghost", "self", "llm", "to-llm", "to-prepare", "harness", "loop-a", "loop-b")
    }
}
