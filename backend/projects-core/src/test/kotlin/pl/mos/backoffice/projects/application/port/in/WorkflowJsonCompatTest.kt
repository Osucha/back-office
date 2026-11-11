package pl.mos.backoffice.projects.application.port.`in`

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.ValidationException

/** ADR 0056: ACTION nodes round-trip, and stored TASK nodes of former action templates and config `agent` read in the new form. */
class WorkflowJsonCompatTest {

    private fun taskNode(templateKey: String, overrides: TaskOverridesJson = TaskOverridesJson()) =
        WorkflowNodeJson(id = "step", kind = "TASK", name = "Step", task = TaskSectionJson(templateKey, overrides))

    @Test
    fun `an ACTION node round-trips with its type and config`() {
        val node = WorkflowNode.Action("move", "Move", action = ActionBinding(ActionType.TRANSITION_ISSUE, mapOf("toStatus" to "Done")))
        val json = WorkflowJson.toJson(node)

        assertThat(json.kind).isEqualTo("ACTION")
        assertThat(json.action).isEqualTo(ActionSectionJson("TRANSITION_ISSUE", mapOf("toStatus" to "Done")))
        assertThat(json.task).isNull()
        assertThat(WorkflowJson.toDomain(json)).isEqualTo(node)
        assertThat(node.ports).containsExactly("success", "failure")
        assertThat(node.requiredPorts).containsExactly("success")
    }

    @Test
    fun `an ACTION node needs its section and a known type`() {
        assertThatThrownBy { WorkflowJson.toDomain(WorkflowNodeJson(id = "a", kind = "ACTION")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { WorkflowJson.toDomain(WorkflowNodeJson(id = "a", kind = "ACTION", action = ActionSectionJson("SEND_MAIL"))) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(WorkflowJson.toDomain(WorkflowNodeJson(id = "a", kind = "ACTION", action = ActionSectionJson("fetch_issue"))).name).isEqualTo("Action")
    }

    @Test
    fun `a TASK node of a former action template reads as that action with the step's config`() {
        val expected = mapOf(
            "fetch-issue" to ActionType.FETCH_ISSUE, "notify-jira" to ActionType.COMMENT_ISSUE, "transition-issue" to ActionType.TRANSITION_ISSUE,
            "start-workflow" to ActionType.START_WORKFLOW, "project-digest" to ActionType.PROJECT_DIGEST,
        )
        expected.forEach { (key, type) ->
            val node = WorkflowJson.toDomain(taskNode(key, TaskOverridesJson(config = mapOf("jiraConnection" to "SYSTEM"))))
            assertThat(node).describedAs(key).isInstanceOf(WorkflowNode.Action::class.java)
            node as WorkflowNode.Action
            assertThat(node.kind).isEqualTo(NodeKind.ACTION)
            assertThat(node.action).isEqualTo(ActionBinding(type, mapOf("jiraConnection" to "SYSTEM")))
            assertThat(node.id).isEqualTo("step")
            assertThat(WorkflowJson.toJson(node).kind).describedAs("the next save writes the new form").isEqualTo("ACTION")
        }
    }

    @Test
    fun `the promptReplace of a former notify step becomes the comment message unless the config has one`() {
        val fromPrompt = WorkflowJson.toDomain(taskNode("notify-jira", TaskOverridesJson(promptReplace = "Done: {{mr.url}}"))) as WorkflowNode.Action
        assertThat(fromPrompt.action.config).containsEntry("messageTemplate", "Done: {{mr.url}}")

        val kept = WorkflowJson.toDomain(
            taskNode("notify-jira", TaskOverridesJson(promptReplace = "ignored", config = mapOf("messageTemplate" to "mine"))),
        ) as WorkflowNode.Action
        assertThat(kept.action.config).containsEntry("messageTemplate", "mine")

        val transition = WorkflowJson.toDomain(taskNode("transition-issue", TaskOverridesJson(promptReplace = "x"))) as WorkflowNode.Action
        assertThat(transition.action.config).isEmpty()
    }

    @Test
    fun `the step config key agent reads as the agent key and an explicit agent key wins`() {
        val legacy = WorkflowJson.toDomain(taskNode("implement-opencode", TaskOverridesJson(config = mapOf("agent" to "CLAUDE_CODE", "push" to false)))) as WorkflowNode.Task
        assertThat(legacy.task.overrides.agentKey).isEqualTo("claude-code")
        assertThat(legacy.task.overrides.config).isEqualTo(mapOf("push" to false))
        assertThat(WorkflowJson.toJson(legacy).task!!.overrides.agentKey).isEqualTo("claude-code")

        val explicit = WorkflowJson.toDomain(taskNode("agent-task", TaskOverridesJson(agentKey = " cheap ", config = mapOf("agent" to "OPENCODE")))) as WorkflowNode.Task
        assertThat(explicit.task.overrides.agentKey).isEqualTo("cheap")
        assertThat(explicit.task.overrides.config).isEmpty()

        val unknown = WorkflowJson.toDomain(taskNode("agent-task", TaskOverridesJson(config = mapOf("agent" to "AIDER")))) as WorkflowNode.Task
        assertThat(unknown.task.overrides.agentKey).isNull()
        assertThat(unknown.task.overrides.config).containsEntry("agent", "AIDER")
    }

    @Test
    fun `ADR 0060 - a step's workspace is dropped, sessionRole kept for planning, addSkills and removeSkills ignored`() {
        val stored = TaskOverridesJson(
            addSkills = listOf("extra"), removeSkills = listOf("old"),
            config = mapOf("workspace" to "bo", "sessionRole" to "implementer", "prepareOnly" to true, "push" to false),
        )
        val step = WorkflowJson.toDomain(taskNode("agent-task", stored)) as WorkflowNode.Task
        assertThat(step.task.overrides.config).isEqualTo(mapOf("sessionRole" to "implementer", "prepareOnly" to true, "push" to false))
        val written = WorkflowJson.toJson(step).task!!.overrides
        assertThat(written.addSkills).describedAs("never written again").isNull()
        assertThat(written.removeSkills).isNull()
    }

    @Test
    fun `ADR 0060 - the setup command travels with the workflow document`() {
        val command = WorkflowJson.toCommand(WorkflowDocumentJson(name = "w", setupCommand = "  "))
        assertThat(command.setupCommand).describedAs("blank = none").isNull()
        assertThat(WorkflowJson.toCommand(WorkflowDocumentJson(name = "w", setupCommand = "npm ci")).setupCommand).isEqualTo("npm ci")
    }
}
