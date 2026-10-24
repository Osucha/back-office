package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import java.util.UUID

class RunInputsTest {

    private val project = Fixtures.project()
    private val input = RunInputField("input", "Input material", RunInputType.MULTILINE)
    private val issue = RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true)

    /** Templates by key; a node whose overrides set `config.extra` adds that field, so the test sees overrides reach the catalog. */
    private val catalog = object : TaskCatalog {
        val inputs = mutableMapOf(
            "agent" to listOf(input),
            "fetch" to listOf(issue),
            "notes" to listOf(RunInputField("input", "Notes", RunInputType.TEXT, required = true, help = "other label")),
            "plain" to emptyList(),
        )
        val seenOverrides = mutableListOf<TaskOverrides>()

        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? {
            seenOverrides += overrides
            if (key == "broken") throw ValidationException("template broken is abstract", "abstract")
            val declared = inputs[key] ?: return null
            val extra = (overrides.config["extra"] as? String)?.let { listOf(RunInputField(it, it.uppercase())) }.orEmpty()
            return ResolvedTask(
                key, key, TaskExecutor.LLM, null, TaskPrompt(null, null), emptyList(), overrides.config, null, null, emptyList(), emptyList(),
                emptyList(), listOf(key), listOf("success", "failure"), runInputs = declared + extra,
            )
        }

        override fun palette(projectId: UUID?) = emptyList<TaskSummary>()
    }

    private fun task(id: String, key: String, overrides: TaskOverrides = TaskOverrides.NONE) = WorkflowNode.Task(id, id, task = TaskBinding(key, overrides))

    private fun workflow(vararg tasks: WorkflowNode) =
        Fixtures.issueWorkflow(project).copy(nodes = listOf(WorkflowNode.Start("start")) + tasks, edges = emptyList())

    @Test
    fun `the effective inputs are the union of the task inputs in definition order`() {
        val fields = RunInputs.effective(workflow(task("a", "agent"), task("f", "fetch"), task("b", "agent")), catalog)

        assertThat(fields).containsExactly(input, issue)
    }

    @Test
    fun `a field is required when any task requires it, and the first label, type and help win`() {
        val fields = RunInputs.effective(workflow(task("a", "agent"), task("n", "notes")), catalog)

        assertThat(fields).containsExactly(input.copy(required = true))
    }

    @Test
    fun `a workflow without tasks has no run inputs and START contributes nothing`() {
        assertThat(RunInputs.effective(workflow(), catalog)).isEmpty()
        assertThat(RunInputs.effective(workflow(task("p", "plain")), catalog)).isEmpty()
    }

    @Test
    fun `tasks are resolved with their node overrides, and unknown or broken templates contribute nothing`() {
        val overrides = TaskOverrides(config = mapOf("extra" to "audience"))

        val fields = RunInputs.effective(workflow(task("a", "agent", overrides), task("x", "ghost"), task("y", "broken")), catalog)

        assertThat(fields).containsExactly(input, RunInputField("audience", "AUDIENCE"))
        assertThat(catalog.seenOverrides).contains(overrides)
    }

    @Test
    fun `the fields of one task node are read the same way`() {
        val node = task("f", "fetch")

        assertThat(RunInputs.ofTask(node, project.id.value, catalog)).containsExactly(issue)
        assertThat(RunInputs.ofTask(task("x", "ghost"), project.id.value, catalog)).isEmpty()
    }

    @Test
    fun `a step that reads the issue key from another parameter does not make the issue key required`() {
        val elsewhere = TaskOverrides(config = mapOf("issueKeyFrom" to "ticket"))
        val fromInput = TaskOverrides(config = mapOf("issueKeyFrom" to "input"))

        assertThat(RunInputs.effective(workflow(task("f", "fetch", elsewhere)), catalog)).containsExactly(issue.copy(required = false))
        assertThat(RunInputs.effective(workflow(task("f", "fetch", fromInput)), catalog)).containsExactly(issue)
        // another step that reads the run's key still requires it
        assertThat(RunInputs.effective(workflow(task("f", "fetch", elsewhere), task("g", "fetch")), catalog)).containsExactly(issue)
    }
}
