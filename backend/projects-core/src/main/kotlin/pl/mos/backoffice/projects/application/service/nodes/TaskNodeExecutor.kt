package pl.mos.backoffice.projects.application.service.nodes

import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeExecutor
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.TaskRunKind
import pl.mos.backoffice.projects.application.service.tasks.StepQuestions
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.StepAgentRecord
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog

/** Registry of task strategies by [TaskRunKind] (ADR 0056). */
class TaskExecutors(strategies: List<TaskExecutorStrategy>) {
    private val byKind: Map<TaskRunKind, TaskExecutorStrategy> = strategies.associateBy { it.runKind }

    fun forKind(kind: TaskRunKind): TaskExecutorStrategy =
        byKind[kind] ?: throw InvalidStateException("no strategy registered for task run kind $kind")

    /** The strategy for how [task] runs: its agent and its config decide (ADR 0056). */
    fun forTask(task: pl.mos.backoffice.shared.tasks.ResolvedTask): TaskExecutorStrategy = forKind(TaskRunKind.of(task))
}

/**
 * `TASK`: resolves the template through the Task Library (`TaskCatalog`, node overrides applied) and
 * dispatches on the resolved agent (ADR 0056: model answer or agent job). Missing inputs (`consumes`) are logged, unknown or abstract
 * templates fail the node.
 */
class TaskNodeExecutor(
    private val catalog: TaskCatalog,
    private val strategies: TaskExecutors,
) : NodeExecutor {
    override val kind = NodeKind.TASK

    override fun execute(context: NodeContext): NodeResult {
        val node = context.node as? WorkflowNode.Task ?: throw InvalidStateException("node ${context.node.id} is not a TASK node")
        val task = try {
            catalog.resolve(node.task.templateKey, context.project.id.value, node.task.overrides)
        } catch (e: ValidationException) {
            return NodeResult.failure("task template ${node.task.templateKey} cannot run: ${e.message}")
        } ?: return NodeResult.failure("unknown task template ${node.task.templateKey}")
        task.consumes.filterNot { name -> context.run.artifactNamed(name) != null || context.state[name] != null }.forEach { missing ->
            context.log("input $missing consumed by ${task.key} is not available yet")
        }
        context.log("running task ${task.key} with agent ${task.agent.key} (${TaskRunKind.of(task)})")
        return consumingFeedback(context, StepQuestions.limited(context, task, attributed(task, strategies.forTask(task).execute(context, task))))
    }

    override fun resume(context: NodeContext, jobId: RunnerJobId): NodeResult {
        val node = context.node as? WorkflowNode.Task ?: throw InvalidStateException("node ${context.node.id} is not a TASK node")
        val task = catalog.resolve(node.task.templateKey, context.project.id.value, node.task.overrides)
            ?: return NodeResult.failure("unknown task template ${node.task.templateKey}")
        return consumingFeedback(context, StepQuestions.limited(context, task, attributed(task, strategies.forTask(task).resume(context, task, jobId))))
    }

    /**
     * ADR 0059: every finished task step records its agent. The strategy reports the model it used; a result without a
     * record (a step that failed before choosing its model) gets the agent alone. A submitted job is not finished yet.
     */
    private fun attributed(task: ResolvedTask, result: NodeResult): NodeResult =
        if (result.agent != null || result.awaitingJob != null) result else result.copy(agent = StepAgentRecord.of(task.agent, null))
}

/**
 * A step that completed (not failed, not waiting for a runner job or a write confirmation) consumed the pending
 * revision feedback: it moves to this node's feedback history ([RunStateKeys.consumeFeedback]), so the next steps
 * do not receive it as `{{feedback}}`. A failed step keeps it for its retry. Task and action steps alike.
 */
internal fun consumingFeedback(context: NodeContext, result: NodeResult): NodeResult {
    if (result.failed || result.awaitingJob != null || result.pendingWrite != null || result.questions != null) return result
    val updates = RunStateKeys.consumeFeedback(context.state, context.node.id)
    if (updates.isEmpty()) return result
    return result.copy(stateUpdates = updates + result.stateUpdates)
}
