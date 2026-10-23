package pl.mos.backoffice.projects.application.service.nodes

import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeExecutor
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.DomainException
import pl.mos.backoffice.shared.InvalidStateException

/** `START`: nothing to do; leaves through `out`. */
class StartNodeExecutor : NodeExecutor {
    override val kind = NodeKind.START
    override fun execute(context: NodeContext): NodeResult = NodeResult(port = WorkflowNode.PORT_OUT, summary = "started")
}

/** `END`: records the run outcome; has no output port. */
class EndNodeExecutor : NodeExecutor {
    override val kind = NodeKind.END
    override fun execute(context: NodeContext): NodeResult {
        val node = context.node as? WorkflowNode.End ?: throw InvalidStateException("node ${context.node.id} is not an END node")
        return NodeResult(port = null, summary = "run finished with ${node.end.outcome}", outcome = node.end.outcome, stateUpdates = mapOf(RunStateKeys.RUN_OUTCOME to node.end.outcome.name))
    }
}

/**
 * `DECISION`: evaluates the branches in order against the [WorkflowStateView]; the first true branch is
 * the port taken, else `defaultBranch`, else the node fails (which fails the run).
 */
class DecisionNodeExecutor(private val evaluator: ExpressionEvaluator) : NodeExecutor {
    override val kind = NodeKind.DECISION

    override fun execute(context: NodeContext): NodeResult {
        val node = context.node as? WorkflowNode.Decision ?: throw InvalidStateException("node ${context.node.id} is not a DECISION node")
        val view = WorkflowStateView.of(context.run, context.state)
        for (branch in node.decision.branches) {
            val result = try {
                evaluator.evaluate(branch.expression, view)
            } catch (e: DomainException) {
                return NodeResult.failure("branch ${branch.id}: ${e.message}", port = WorkflowNode.PORT_FAILURE)
            } catch (e: RuntimeException) {
                return NodeResult.failure("branch ${branch.id}: ${e.message ?: e.javaClass.simpleName}", port = WorkflowNode.PORT_FAILURE)
            }
            context.log("branch ${branch.id} (${branch.expression}) = $result")
            if (result) return NodeResult(port = branch.id, summary = "${branch.label}: ${branch.expression}")
        }
        val default = node.decision.defaultBranch
            ?: return NodeResult.failure("no branch of decision ${node.id} matched and no default branch is set", port = WorkflowNode.PORT_FAILURE)
        return NodeResult(port = default, summary = "default branch $default")
    }
}

/**
 * `HUMAN_GATE`: runs after the owner decided (the engine interrupts before the node). Consumes the
 * pending decision from the state, records revision feedback and leaves through the option's port.
 *
 * The comment of an option that sends work back (`revise`, `retry` or any option other than `approve`, `reject` and a
 * timeout) goes to the run's feedback history ([RunStateKeys.FEEDBACK]) and to the pending feedback
 * ([RunStateKeys.PENDING_FEEDBACK]) that only the next task step receives as `{{feedback}}` ([TaskNodeExecutor]
 * consumes it), so a comment on the specification does not reappear in the plan, tasks and implementation prompts.
 * `approve` comments reach later prompts as `{{decision.comment}}` instead.
 */
class HumanGateNodeExecutor : NodeExecutor {
    override val kind = NodeKind.HUMAN_GATE

    @Suppress("UNCHECKED_CAST")
    override fun execute(context: NodeContext): NodeResult {
        val node = context.node as? WorkflowNode.HumanGate ?: throw InvalidStateException("node ${context.node.id} is not a HUMAN_GATE node")
        val decision = (context.state[RunStateKeys.DECISION] as? Map<String, Any?>)
            ?: return NodeResult.failure("gate ${node.id} resumed without a decision", port = WorkflowNode.PORT_FAILURE)
        val option = decision["option"]?.toString()?.takeIf { it.isNotBlank() } ?: GateSpec.APPROVE
        val port = if (option.equals(Decision.TIMEOUT, ignoreCase = true)) {
            WorkflowNode.PORT_TIMEOUT
        } else {
            node.gate.options.firstOrNull { it.equals(option, ignoreCase = true) } ?: option.lowercase()
        }
        val comment = decision["comment"]?.toString()?.takeIf { it.isNotBlank() }
        val updates = HashMap<String, Any?>()
        updates[RunStateKeys.LAST_DECISION] = decision
        updates[RunStateKeys.DECISION] = null
        if (sendsWorkBack(port)) {
            comment?.let {
                val entry = "[${node.name}] $it"
                updates[RunStateKeys.FEEDBACK] = listOf(entry)
                updates[RunStateKeys.PENDING_FEEDBACK] = RunStateKeys.pendingFeedback(context.state) + entry
            }
        }
        if (port.equals(GateSpec.REVISE, ignoreCase = true)) updates[RunStateKeys.CONFIDENCE] = -1.0
        return NodeResult(port = port, summary = "Decision: ${port.uppercase()}" + (comment?.let { " - $it" } ?: ""), stateUpdates = updates)
    }

    private fun sendsWorkBack(port: String): Boolean =
        listOf(GateSpec.APPROVE, GateSpec.REJECT, WorkflowNode.PORT_TIMEOUT).none { it.equals(port, ignoreCase = true) }
}

/**
 * `WAIT_FOR_CI`: runs after CI signalled (the engine interrupts before the node). Consumes the pending
 * signal and leaves through `success`, `fail` or `timeout`.
 */
class WaitForCiNodeExecutor : NodeExecutor {
    override val kind = NodeKind.WAIT_FOR_CI

    @Suppress("UNCHECKED_CAST")
    override fun execute(context: NodeContext): NodeResult {
        val node = context.node as? WorkflowNode.WaitForCi ?: throw InvalidStateException("node ${context.node.id} is not a WAIT_FOR_CI node")
        val signal = (context.state[RunStateKeys.CI] as? Map<String, Any?>)
            ?: return NodeResult.failure("node ${node.id} resumed without a CI signal", port = WorkflowNode.PORT_FAILURE)
        val status = signal["status"]?.toString()?.uppercase() ?: CiSignalStatus.FAIL.name
        val port = when (status) {
            CiSignalStatus.SUCCESS.name -> WorkflowNode.PORT_SUCCESS
            RunStateKeys.CI_STATUS_TIMEOUT -> WorkflowNode.PORT_TIMEOUT
            else -> WorkflowNode.PORT_FAIL
        }
        val message = signal["message"]?.toString()?.takeIf { it.isNotBlank() }
        val updates = mapOf(RunStateKeys.LAST_CI to signal, RunStateKeys.CI to null)
        return NodeResult(port = port, summary = "CI signal ${node.ci.signal}: $status" + (message?.let { " - $it" } ?: ""), stateUpdates = updates)
    }
}

/** Registry of node executors by kind. `NOTE` nodes are never executed. */
class NodeExecutors(executors: List<NodeExecutor>) {
    private val byKind: Map<NodeKind, NodeExecutor> = executors.associateBy { it.kind }

    fun forKind(kind: NodeKind): NodeExecutor = byKind[kind] ?: throw InvalidStateException("no executor registered for node kind $kind")

    fun supports(kind: NodeKind): Boolean = kind in byKind
}
