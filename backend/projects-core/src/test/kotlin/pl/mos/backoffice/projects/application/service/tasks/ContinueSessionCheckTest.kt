package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import java.util.UUID

/** ADR 0057/0060: session continuation problems block the run and show in the designer. */
class ContinueSessionCheckTest {

    /**
     * Template keys say who runs them: `oc-*` OpenCode, `cc-*` Claude Code, `oc2-*` another agent on OpenCode, `prep-*` a
     * prepare step, anything else a model answer.
     */
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask {
            val agent = when {
                key.startsWith("oc2-") -> TestAgents.OPENCODE.copy(key = "reviewer-oc", name = "Reviewer")
                key.startsWith("oc-") || key.startsWith("prep-") -> TestAgents.OPENCODE
                key.startsWith("cc-") -> TestAgents.CLAUDE_CODE
                else -> TestAgents.MODEL_ANSWER
            }
            val config = overrides.config + (if (key.startsWith("prep-")) mapOf(RunDirectory.PREPARE_ONLY to true, "push" to false) else emptyMap())
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
        id, "Step $id", task = TaskBinding(template, TaskOverrides(config = config + (continues?.let { mapOf(RunDirectory.CONTINUE_SESSION to it) } ?: emptyMap()))),
    )

    @Test
    fun `a coding agent step may continue an earlier step of the same agent`() {
        val nodes = listOf(
            step("implement", "oc-implement"),
            step("fix", "oc-fix", continues = "implement"),
            step("polish", "oc-polish", config = mapOf(RunDirectory.SESSION to "CONTINUE", RunDirectory.CONTINUE_SESSION to "fix")),
        )
        assertThat(check.designIssues(project, nodes).errors).isEmpty()
        assertThat(check.runBlockers(project, nodes)).isEmpty()
    }

    @Test
    fun `every kind of wrong continuation is a run blocker and a designer error on the continuing step`() {
        val nodes = listOf(
            step("implement", "oc-implement"),
            step("answer", "write-story"),
            step("prepare", "prep-workspace"),
            step("fresh", "oc-fresh", config = mapOf(RunDirectory.SESSION to "NEW")),
            step("apart", "oc-apart", config = mapOf(RunDirectory.DIRECTORY to "SEPARATE")),
            step("ghost", "oc-a", continues = "nowhere"),
            step("self", "oc-b", continues = "self"),
            step("llm", "write-other", continues = "implement"),
            step("to-llm", "oc-c", continues = "answer"),
            step("to-prepare", "oc-d", continues = "prepare"),
            step("to-fresh", "oc-h", continues = "fresh"),
            step("to-apart", "oc-i", continues = "apart"),
            step("harness", "cc-e", continues = "implement"),
            step("other-agent", "oc2-e", continues = "implement"),
            step("loop-a", "oc-f", continues = "loop-b"),
            step("loop-b", "oc-g", continues = "loop-a"),
        )
        val problems = check.runBlockers(project, nodes).filter { "continue" in it || "session" in it || "agent" in it }
        assertThat(problems).containsExactlyInAnyOrder(
            "step \"Step ghost\" (ghost): continueSession names step nowhere, which does not exist",
            "step \"Step self\" (self): a step cannot continue its own session",
            "step \"Step llm\" (llm): a model answer has no agent session to continue; remove continueSession",
            "step \"Step to-llm\" (to-llm): continueSession names step answer, which is not a coding agent step",
            "step \"Step to-prepare\" (to-prepare): continueSession names step prepare, a prepare step that starts no agent session",
            "step \"Step to-fresh\" (to-fresh): continueSession names step fresh, which starts a new session every time (session NEW)",
            "step \"Step to-apart\" (to-apart): continueSession names step apart, which works in a SEPARATE directory: its session is thrown away",
            "step \"Step harness\" (harness): step implement runs agent opencode and this step claude-code: a session continues only with the same agent (its home holds the session)",
            "step \"Step other-agent\" (other-agent): step implement runs agent opencode and this step reviewer-oc: a session continues only with the same agent (its home holds the session)",
            "step \"Step loop-a\" (loop-a): continueSession forms a cycle",
            "step \"Step loop-b\" (loop-b): continueSession forms a cycle",
        )
        val errors = check.designIssues(project, nodes).errors.filter { it.field == RunDirectory.FIELD_CONTINUE }
        assertThat(errors.map { it.nodeId }).containsExactlyInAnyOrder(
            "ghost", "self", "llm", "to-llm", "to-prepare", "to-fresh", "to-apart", "harness", "other-agent", "loop-a", "loop-b",
        )
    }

    @Test
    fun `directory, session and question settings are checked, and a SEPARATE step only warns about its session`() {
        val nodes = listOf(
            step("apart", "oc-apart", config = mapOf(RunDirectory.DIRECTORY to "SEPARATE", RunDirectory.SESSION to "OWN")),
            step("bad-dir", "oc-a", config = mapOf(RunDirectory.DIRECTORY to "ELSEWHERE")),
            step("bad-session", "oc-b", config = mapOf(RunDirectory.SESSION to "SOMETIMES")),
            step("no-target", "oc-c", config = mapOf(RunDirectory.SESSION to "CONTINUE")),
            step("rounds", "oc-d", config = mapOf(RunDirectory.MAX_QUESTION_ROUNDS to 99)),
            step("llm", "write-story", config = mapOf(RunDirectory.DIRECTORY to "SEPARATE")),
        )
        val issues = check.designIssues(project, nodes)
        assertThat(issues.errors.map { it.nodeId to it.field }).containsExactlyInAnyOrder(
            "bad-dir" to RunDirectory.FIELD_DIRECTORY,
            "bad-session" to RunDirectory.FIELD_DIRECTORY,
            "no-target" to RunDirectory.FIELD_DIRECTORY,
            "rounds" to RunDirectory.FIELD_QUESTIONS,
        )
        assertThat(issues.warnings.single { it.nodeId == "apart" }.message).contains("always starts a new session")
        assertThat(issues.warnings.single { it.nodeId == "llm" }.message).contains("only coding agent steps use directory")
    }
}
