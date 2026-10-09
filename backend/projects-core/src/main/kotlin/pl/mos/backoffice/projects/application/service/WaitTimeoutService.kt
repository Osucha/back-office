package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ExpireWaits
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.SystemOperation
import java.time.Duration

/**
 * Implements [ExpireWaits]: every `WAITING` run whose node has a `timeoutSeconds` and whose wait started
 * longer ago than that is resumed through the node's `timeout` port (HUMAN_GATE approvals expire first).
 * Called by a scheduled sweep in the adapters, which runs it as the system principal.
 */
class WaitTimeoutService(
    private val runs: WorkflowRunRepository,
    private val approvals: ApprovalRequestRepository,
    private val engine: WorkflowEngine,
    private val clock: Clock,
) : ExpireWaits {

    @SystemOperation("scheduled wait-timeout sweep")
    override fun sweep(): Int {
        var resumed = 0
        runs.findWaiting(null).forEach { run ->
            if (expire(run)) resumed++
        }
        return resumed
    }

    /** Returns true when [run] was resumed by timeout. */
    @SystemOperation("scheduled wait-timeout sweep, one run")
    fun expire(run: WorkflowRun): Boolean {
        val waiting = run.waitingOn ?: return false
        // a write confirmation (ADR 0033) waits for a person; the node's timeoutSeconds limits its execution, not the wait
        if (waiting.kind == WaitKind.WRITE_CONFIRMATION) return false
        val node = run.definitionSnapshot.node(waiting.nodeId) ?: return false
        val timeout = node.waitTimeoutSeconds?.takeIf { it > 0 } ?: return false
        val since = run.nodes.lastOrNull { it.nodeId == waiting.nodeId && it.status == NodeExecutionStatus.WAITING }?.startedAt ?: return false
        val now = clock.now()
        if (now.isBefore(since.plus(Duration.ofSeconds(timeout)))) return false
        when (waiting.kind) {
            WaitKind.HUMAN_GATE -> {
                approvals.findOpenByRunId(run.id)?.let { approvals.save(it.expire()) }
                val saved = runs.save(run.resumedByTimeout(now))
                engine.resumeWithDecision(saved, Decision(Decision.TIMEOUT, "no decision within $timeout seconds", decidedAt = now))
            }
            WaitKind.WAIT_FOR_CI -> {
                val saved = runs.save(run.resumedByTimeout(now))
                engine.resumeWithSignal(saved, null)
            }
            WaitKind.WRITE_CONFIRMATION -> return false
        }
        return true
    }
}
