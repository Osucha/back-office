package pl.mos.backoffice.projects.application.port.`in`

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ParameterBinding
import pl.mos.backoffice.shared.tasks.TaskOverrides

/** ADR 0062: step overrides read the legacy prompt keys, step values and session keys compatibly and write only the new form. */
class WorkflowJsonInstructionsTest {

    private fun step(overrides: TaskOverridesJson) =
        WorkflowJson.toDomain(WorkflowNodeJson(id = "s", kind = "TASK", name = "S", task = TaskSectionJson("llm-task", overrides))) as WorkflowNode.Task

    @Test
    fun `legacy appends and replace read as the instruction notes and replacement, legacy values as VALUE bindings`() {
        val overrides = step(
            TaskOverridesJson(
                systemPromptAppend = "Be careful.", promptAppend = " Then report. ", promptReplace = "Fix it.",
                parameters = mapOf("task" to "Summarise", "blank" to " ", "none" to null),
                config = mapOf("push" to false, "session" to "CONTINUE", "continueSession" to "a", "directory" to "SEPARATE", "sessionRole" to "dev"),
            ),
        ).task.overrides

        assertThat(overrides.instructionsAppend).isEqualTo("Be careful.\n\nThen report.")
        assertThat(overrides.instructionsReplace).isEqualTo("Fix it.")
        assertThat(overrides.parameterBindings).isEqualTo(mapOf("task" to ParameterBinding.value("Summarise")))
        assertThat(overrides.config).describedAs("session and directory are the agent's; sessionRole stays").isEqualTo(mapOf("push" to false, "sessionRole" to "dev"))
    }

    @Test
    fun `the new fields win over the legacy ones and bindings of every source read`() {
        val overrides = step(
            TaskOverridesJson(
                instructionsAppend = "notes", instructionsReplace = "new", promptReplace = "old", promptAppend = "more",
                parameterBindings = mapOf(
                    "task" to ParameterBindingJson("VALUE", "bound"), "input" to ParameterBindingJson("start"),
                    "spec" to ParameterBindingJson("ARTIFACT", artifact = " spec "),
                ),
                parameters = mapOf("task" to "legacy", "tone" to "dry"),
            ),
        ).task.overrides

        assertThat(overrides.instructionsReplace).isEqualTo("new")
        assertThat(overrides.instructionsAppend).isEqualTo("notes\n\nmore")
        assertThat(overrides.parameterBindings).isEqualTo(
            mapOf(
                "task" to ParameterBinding.value("bound"), "input" to ParameterBinding.START, "spec" to ParameterBinding.artifact("spec"),
                "tone" to ParameterBinding.value("dry"),
            ),
        )
        assertThatThrownBy { step(TaskOverridesJson(parameterBindings = mapOf("x" to ParameterBindingJson("LATER")))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("VALUE, START, ARTIFACT")
    }

    @Test
    fun `only the current fields are written and round trip`() {
        val overrides = TaskOverrides(
            agentKey = "analyst", instructionsAppend = "notes", instructionsReplace = "new",
            parameterBindings = mapOf("input" to ParameterBinding.START, "task" to ParameterBinding.value("x"), "spec" to ParameterBinding.artifact("spec")),
        )
        val json = WorkflowJson.toJson(overrides)

        assertThat(json.systemPromptAppend).isNull()
        assertThat(json.promptAppend).isNull()
        assertThat(json.promptReplace).isNull()
        assertThat(json.parameters).isNull()
        assertThat(json.parameterBindings!!.getValue("input")).isEqualTo(ParameterBindingJson("START"))
        assertThat(WorkflowJson.toDomain(json)).isEqualTo(overrides)
        assertThat(WorkflowJson.toJson(TaskOverrides.NONE).parameterBindings).describedAs("no empty map written").isNull()
    }

    @Test
    fun `a TASK node of a former comment template takes its message from the replacement, new or legacy`() {
        val legacy = WorkflowJson.toDomain(WorkflowNodeJson(id = "c", kind = "TASK", name = "C", task = TaskSectionJson("notify-jira", TaskOverridesJson(promptReplace = "Done: {{run.id}}"))))
        val current = WorkflowJson.toDomain(WorkflowNodeJson(id = "c", kind = "TASK", name = "C", task = TaskSectionJson("notify-jira", TaskOverridesJson(instructionsReplace = "Ready"))))

        assertThat((legacy as WorkflowNode.Action).action.config).containsValue("Done: {{run.id}}")
        assertThat((current as WorkflowNode.Action).action.config).containsValue("Ready")
    }
}
