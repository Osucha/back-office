package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.LeaseScanOutcome
import pl.mos.backoffice.runner.application.port.`in`.MaintainAgentJobs
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * The lease scan (every 10 s on one replica at a time, `pg_try_advisory_xact_lock`):
 *
 * - LEASED and no heartbeat before the lease expired: the controller never picked the job up (crash, lost
 *   response). Back to the queue, up to `max-unacknowledged-leases` times, then FAILED.
 * - STARTING/RUNNING and the heartbeats stopped: LOST. Re-queued once when nothing can have been pushed
 *   (it was still starting, or its spec does not push), otherwise it ends LOST with evidence.
 * - QUEUED past the queue deadline: TIMED_OUT (`queue-timeout`).
 * - QUEUED without a pool: routed again (a pool may have been created or changed).
 */
class AgentJobMaintenanceService(
    private val jobs: AgentJobStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
) : MaintainAgentJobs {

    @SystemOperation("scheduled lease scan")
    override fun scan(): LeaseScanOutcome = tx.inTransaction {
        if (!jobs.tryAdvisoryLock(ADVISORY_LOCK_KEY)) return@inTransaction LeaseScanOutcome(ran = false)
        val now = lifecycle.now()
        var requeued = 0
        var lost = 0
        jobs.lockExpiredLeases(now, BATCH).forEach { job ->
            when (expired(job)) {
                Outcome.REQUEUED -> requeued++
                Outcome.LOST -> lost++
                Outcome.FAILED -> lost++
            }
        }
        val timedOut = jobs.lockQueuedPastDeadline(now, BATCH).onEach { job ->
            lifecycle.finish(job, AgentJobStatus.TIMED_OUT, "queue-timeout: no runner leased the job within ${lifecycle.settings.queueTimeout.toHours()} h" + (job.reason?.let { " ($it)" } ?: ""))
        }.size
        var routed = 0
        jobs.lockUnassigned(BATCH).forEach { job ->
            val routing = lifecycle.routeJob(job.spec)
            if (routing.pool != null) routed++
            if (routing.pool != null || routing.reason != job.reason) {
                jobs.update(lifecycle.applyRouting(job, routing))
                lifecycle.wake(routing.pool?.id)
            }
        }
        LeaseScanOutcome(ran = true, requeued = requeued, lost = lost, timedOut = timedOut, routed = routed)
    }

    private enum class Outcome { REQUEUED, LOST, FAILED }

    private fun expired(job: AgentJob): Outcome {
        if (job.status == AgentJobStatus.LEASED) {
            return if (job.leaseCount >= lifecycle.settings.maxUnacknowledgedLeases) {
                lifecycle.finish(job, AgentJobStatus.FAILED, "lease-not-acknowledged: leased ${job.leaseCount} times without a heartbeat")
                Outcome.FAILED
            } else {
                lifecycle.requeue(job, "re-queued: the lease expired before the first heartbeat", countAsLost = false)
                Outcome.REQUEUED
            }
        }
        val mayHavePushed = job.status == AgentJobStatus.RUNNING &&
            (job.spec.repository?.push == true || job.spec.repositories.any { it.access == GitAccess.PUSH_MR })
        return if (job.lostCount == 0 && !mayHavePushed && !job.cancelRequested) {
            lifecycle.requeue(job, "re-queued after lost heartbeats (runner ${job.runnerId})", countAsLost = true)
            Outcome.REQUEUED
        } else {
            val why = when {
                job.cancelRequested -> "heartbeat-timeout while cancelling"
                mayHavePushed -> "heartbeat-timeout: the job was running and may have pushed, so it is not retried"
                else -> "heartbeat-timeout: lost a second time"
            }
            lifecycle.finish(job, AgentJobStatus.LOST, why + " (runner ${job.runnerId}, sandbox ${job.sandboxName ?: "-"}, node ${job.node ?: "-"})")
            Outcome.LOST
        }
    }

    companion object {
        /** `pg_try_advisory_xact_lock` key of the scan ("agentjob" in ASCII). */
        const val ADVISORY_LOCK_KEY = 0x6167656e746a6f62L
        const val BATCH = 200
    }
}
