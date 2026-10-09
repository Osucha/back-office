package pl.mos.backoffice.projects.application.service.nodes

import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeExecutor
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor

/** Registry of task executor strategies by `TaskExecutor`. */
class TaskExecutors(strategies: List<TaskExecutorStrategy>) {
    private val byExecutor: Map<TaskExecutor, TaskExecutorStrategy> = strategies.associateBy { it.executor }

    fun forExecutor(executor: TaskExecutor): TaskExecutorStrategy =
        byExecutor[executor] ?: throw InvalidStateException("no strategy registered for task executor $executor")
}

/**
 * `TASK`: resolves the template through the Task Library (`TaskCatalog`, node overrides applied) and
 * dispatches on the resolved executor. Missing inputs (`consumes`) are logged, unknown or abstract
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
        context.log("running task ${task.key} (${task.executor})")
        return strategies.forExecutor(task.executor).execute(context, task)
    }

    override fun resume(context: NodeContext, jobId: RunnerJobId): NodeResult {
        val node = context.node as? WorkflowNode.Task ?: throw InvalidStateException("node ${context.node.id} is not a TASK node")
        val task = catalog.resolve(node.task.templateKey, context.project.id.value, node.task.overrides)
            ?: return NodeResult.failure("unknown task template ${node.task.templateKey}")
        return strategies.forExecutor(task.executor).resume(context, task, jobId)
    }

    override fun commit(context: NodeContext, write: PendingWrite): NodeResult {
        val node = context.node as? WorkflowNode.Task ?: throw InvalidStateException("node ${context.node.id} is not a TASK node")
        val task = catalog.resolve(node.task.templateKey, context.project.id.value, node.task.overrides)
            ?: return NodeResult.failure("unknown task template ${node.task.templateKey}")
        return strategies.forExecutor(task.executor).commit(context, task, write)
    }
}
