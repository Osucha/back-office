package pl.mos.backoffice.projects.application.service.nodes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.out.ActionStep
import pl.mos.backoffice.projects.application.port.out.ActionStrategy
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.TaskRunKind
import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ExternalWriteAction
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.projects.domain.service.RunInputs
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.TestAgents
import java.util.UUID

/** ADR 0056: action nodes dispatch on their type with defaults applied; task nodes dispatch on the agent. */
class ActionNodeExecutorTest {

    private val project = Fixtures.project()
    private val seen = mutableListOf<ActionStep>()

    private fun strategy(type: ActionType) = object : ActionStrategy {
        override val type = type
        override fun execute(context: NodeContext, step: ActionStep): NodeResult = NodeResult.success(summary = "ran ${step.type}").also { seen += step }
        override fun commit(context: NodeContext, step: ActionStep, write: PendingWrite): NodeResult = NodeResult.success(summary = "wrote ${write.issueKey}")
    }

    private fun context(node: WorkflowNode, state: Map<String, Any?> = emptyMap()): NodeContext {
        val run = Fixtures.runningRun(project).nodeStarted(node, Fixtures.now)
        return NodeContext(run, project, node, run.nodes.last(), state)
    }

    @Test
    fun `an action runs its strategy with the node config over the defaults and consumes pending feedback`() {
        val executor = ActionNodeExecutor(ActionStrategies(listOf(strategy(ActionType.COMMENT_ISSUE), strategy(ActionType.TRANSITION_ISSUE))))
        val node = WorkflowNode.Action("move", "Move", action = ActionBinding(ActionType.TRANSITION_ISSUE, mapOf("toStatus" to "Done", "failIfMissing" to null)))
        val state = mapOf(RunStateKeys.PENDING_FEEDBACK to listOf("fix it"))

        val result = executor.execute(context(node, state))

        assertThat(result.summary).isEqualTo("ran TRANSITION_ISSUE")
        assertThat(seen.single().config).isEqualTo(mapOf("toStatus" to "Done"))
        assertThat(seen.single().nodeId).isEqualTo("move")
        assertThat(result.stateUpdates).describedAs("the step consumed the feedback").isNotEmpty()
        assertThat(executor.kind.name).isEqualTo("ACTION")

        val comment = WorkflowNode.Action("tell", "Tell", action = ActionBinding(ActionType.COMMENT_ISSUE))
        executor.execute(context(comment))
        assertThat(seen.last().config).containsEntry("messageTemplate", ActionCatalog.DEFAULT_COMMENT)
        assertThat(executor.commit(context(comment), PendingWrite(ExternalWriteAction.JIRA_COMMENT, "SHOP-1", comment = "x")).summary).isEqualTo("wrote SHOP-1")
    }

    @Test
    fun `an action without a strategy or a node of another kind fails`() {
        val executor = ActionNodeExecutor(ActionStrategies(emptyList()))
        assertThatThrownBy { executor.execute(context(WorkflowNode.Action("a", "A", action = ActionBinding(ActionType.FETCH_ISSUE)))) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("FETCH_ISSUE")
        assertThatThrownBy { executor.execute(context(WorkflowNode.Task("t", "T", task = TaskBinding("x")))) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `the catalogue describes every action with defaults, schema, run inputs and capabilities`() {
        assertThat(ActionCatalog.all.map { it.type }).containsExactlyElementsOf(ActionType.entries)
        val comment = ActionCatalog.of(ActionType.COMMENT_ISSUE)
        assertThat(comment.externalWrite).isTrue()
        assertThat(comment.requiredCapabilities).containsExactly(ConnectionCapability.ISSUE_COMMENT)
        assertThat(comment.ports).containsExactly("success", "failure")
        assertThat(ActionCatalog.of(ActionType.FETCH_ISSUE).externalWrite).isFalse()
        assertThat(ActionCatalog.of(ActionType.PROJECT_DIGEST).defaults).containsEntry("maxRuns", 20)
        assertThat(ActionCatalog.of(ActionType.START_WORKFLOW).produces).containsExactly("started-runs")
        // ADR 0063: delivery opens a merge request by default, writes to no issue tracker and lists repository keys
        val deliver = ActionCatalog.of(ActionType.DELIVER_CHANGES)
        assertThat(deliver.defaults).containsEntry("mergeRequest", true)
        assertThat(deliver.produces).containsExactly("mr", "diff")
        assertThat(deliver.externalWrite).isFalse()
        @Suppress("UNCHECKED_CAST")
        val properties = deliver.configSchema["properties"] as Map<String, Any?>
        assertThat(properties.keys).containsExactly("branchTemplate", "commitSubjectTemplate", "mergeRequest", "mergeRequestTitleTemplate", "mergeRequestDescriptionTemplate", "mergeRequestDraft", "repositories")
        assertThat(ActionCatalog.DELIVERY_KEYS).doesNotContain("repositories")
        assertThat(ActionCatalog.of(ActionType.TRANSITION_ISSUE).configSchema["required"]).isEqualTo(listOf("toStatus"))

        val fetch = ActionBinding(ActionType.FETCH_ISSUE)
        assertThat(ActionCatalog.runInputs(fetch).single().required).isTrue()
        assertThat(ActionCatalog.runInputs(fetch.copy(config = mapOf("issueKeyFrom" to "ticket"))).single().required).isFalse()
        assertThat(ActionCatalog.runInputs(ActionBinding(ActionType.PROJECT_DIGEST))).isEmpty()
    }

    @Test
    fun `the run start form includes the inputs of action steps`() {
        val catalog = object : TaskCatalog {
            override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = null
            override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
        }
        val nodes = listOf(WorkflowNode.Action("fetch", "Fetch", action = ActionBinding(ActionType.FETCH_ISSUE)), WorkflowNode.Start("start"))

        val fields = RunInputs.effective(nodes, project.id.value, catalog)

        assertThat(fields.single().type).isEqualTo(RunInputType.ISSUE_KEY)
    }

    private fun task(agent: pl.mos.backoffice.shared.tasks.AgentSnapshot, config: Map<String, Any?> = emptyMap()) = ResolvedTask(
        "t", "T", agent, null, ("x"), emptyList(), config, null, null, emptyList(), emptyList(), null, listOf("t"), listOf("success", "failure"),
    )

    @Test
    fun `a task runs as a model answer or an agent job depending on its agent (ADR 0063 - no Spec Kit job)`() {
        assertThat(TaskRunKind.of(task(TestAgents.MODEL_ANSWER, mapOf("phase" to "PLAN")))).isEqualTo(TaskRunKind.MODEL_ANSWER)
        assertThat(TaskRunKind.of(task(TestAgents.OPENCODE, mapOf("phase" to "PLAN")))).isEqualTo(TaskRunKind.AGENT_JOB)
        assertThat(TaskRunKind.of(task(TestAgents.CLAUDE_CODE, mapOf("phase" to " ")))).isEqualTo(TaskRunKind.AGENT_JOB)
        assertThat(TaskRunKind.MODEL_ANSWER.runner).isFalse()
        assertThat(TaskRunKind.AGENT_JOB.runner).isTrue()

        val ran = mutableListOf<TaskRunKind>()
        fun strategy(kind: TaskRunKind) = object : TaskExecutorStrategy {
            override val runKind = kind
            override fun execute(context: NodeContext, task: ResolvedTask): NodeResult = NodeResult.success().also { ran += kind }
        }
        var resolved = task(TestAgents.CLAUDE_CODE)
        val catalog = object : TaskCatalog {
            override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask = resolved
            override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
        }
        val executor = TaskNodeExecutor(catalog, TaskExecutors(TaskRunKind.entries.map(::strategy)))
        val node = WorkflowNode.Task("t", "T", task = TaskBinding("t"))
        executor.execute(context(node))
        resolved = task(TestAgents.MODEL_ANSWER)
        executor.execute(context(node))
        assertThat(ran).containsExactly(TaskRunKind.AGENT_JOB, TaskRunKind.MODEL_ANSWER)
        assertThat(executor.resume(context(node), RunnerJobId.new()).failed).describedAs("a model answer waits for no job").isTrue()
        assertThatThrownBy { TaskExecutors(emptyList()).forKind(TaskRunKind.AGENT_JOB) }.isInstanceOf(InvalidStateException::class.java)
    }
}
