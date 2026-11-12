package pl.mos.backoffice.projects.application.service.nodes

import pl.mos.backoffice.projects.application.port.out.ActionStep
import pl.mos.backoffice.projects.application.port.out.ActionStrategy
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeExecutor
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.RunnerJobId

/** Registry of action strategies by [ActionType] (ADR 0056). */
class ActionStrategies(strategies: List<ActionStrategy>) {
    private val byType: Map<ActionType, ActionStrategy> = strategies.associateBy { it.type }

    fun forType(type: ActionType): ActionStrategy =
        byType[type] ?: throw InvalidStateException("no strategy registered for action $type")
}

/**
 * `ACTION` (ADR 0056): runs a deterministic integration step with the node's config deep-merged over the action's
 * defaults. Write actions pause for confirmation first (ADR 0033) and [commit] performs exactly the approved write.
 */
class ActionNodeExecutor(private val strategies: ActionStrategies) : NodeExecutor {
    override val kind = NodeKind.ACTION

    override fun execute(context: NodeContext): NodeResult {
        val step = stepOf(context)
        context.log("running action ${step.type}")
        return consumingFeedback(context, strategies.forType(step.type).execute(context, step))
    }

    /** ADR 0063: a runner-backed action (Deliver changes) finished its job [jobId]. */
    override fun resume(context: NodeContext, jobId: RunnerJobId): NodeResult {
        val step = stepOf(context)
        return consumingFeedback(context, strategies.forType(step.type).resume(context, step, jobId))
    }

    override fun commit(context: NodeContext, write: PendingWrite): NodeResult {
        val step = stepOf(context)
        return consumingFeedback(context, strategies.forType(step.type).commit(context, step, write))
    }

    companion object {
        /** The action step of an ACTION node: its type, node and effective config. */
        fun stepOf(node: WorkflowNode.Action): ActionStep =
            ActionStep(node.action.type, node.id, node.name, ActionCatalog.effectiveConfig(node.action), ActionCatalog.runInputs(node.action))

        private fun stepOf(context: NodeContext): ActionStep {
            val node = context.node as? WorkflowNode.Action ?: throw InvalidStateException("node ${context.node.id} is not an ACTION node")
            return stepOf(node)
        }
    }
}
