package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.ResultArchive
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.JobTokenScope
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.JobTokenVerifier
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerResult
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/**
 * The [AgentRunner] port for the other contexts (ADR 0023/0024): `submit` only queues the job (routing and
 * policy resolution happen here, the lease later), the continuation is enqueued when the job is terminal,
 * and `result` reads what the sandbox posted. Also the [JobTokenVerifier] for the LLM proxy and callbacks.
 *
 * The deprecated blocking `start`/`await` remain for callers not yet on continuations: `await` polls the row.
 */
class AgentJobService(
    private val jobs: AgentJobStore,
    private val pools: RunnerPoolStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val archive: ResultArchive,
) : AgentRunner, JobTokenVerifier {

    override fun submit(spec: RunnerJobSpec, continuation: WorkRequest): RunnerJobId = enqueue(spec, continuation)

    private fun enqueue(spec: RunnerJobSpec, continuation: WorkRequest?): RunnerJobId = tx.inTransaction {
        val label = JobLifecycle.normaliseLabel(spec.jobLabel)
        jobs.findByLabel(label)?.let { return@inTransaction it.id }
        val now = lifecycle.now()
        val normalised = spec.copy(jobLabel = label)
        val routing = lifecycle.routeJob(normalised)
        val job = lifecycle.applyRouting(
            AgentJob(
                id = RunnerJobId.new(), label = label, poolId = null, runnerId = null, status = AgentJobStatus.QUEUED,
                priority = continuation?.priority ?: if (spec.usageContext.benchmarkId != null) 0 else 10,
                spec = normalised, requestedPolicy = spec.policy, policy = null, policySources = emptyMap(), poolSelector = emptyMap(),
                continuation = continuation, cancelRequested = false, leaseExpiresAt = null, leaseCount = 0, lostCount = 0,
                phase = null, sandboxName = null, node = null, queuedAt = now, leasedAt = null, startedAt = null, finishedAt = null,
                queueDeadline = now.plus(lifecycle.settings.queueTimeout), reason = null, completion = null, hasResult = false, costUsd = null,
            ),
            routing,
        )
        val saved = jobs.insertIfAbsent(job)
        if (saved.id == job.id) lifecycle.wake(job.poolId)
        saved.id
    }

    override fun result(jobId: RunnerJobId): RunnerResult? {
        val job = jobs.find(jobId) ?: return null
        if (!job.status.terminal) return null
        val stored = jobs.loadResult(jobId)
        // large results live in object storage; the inline copy is then trimmed (no diff / artefact contents)
        val posted = stored?.blobKey?.let { key -> archive.read(key) } ?: stored?.inline
        if (posted != null) {
            // the platform's view wins where it is stricter (e.g. cancelled or timed out after the agent reported)
            val status = job.status.toRunnerStatus()
            return if (status == posted.status || posted.status != RunnerJobStatus.SUCCEEDED) posted
            else posted.copy(status = status, error = posted.error ?: job.reason)
        }
        return RunnerResult(
            status = job.status.toRunnerStatus(), summary = null, branch = null, commitSha = null, diffStat = null, verification = null,
            artifacts = emptyList(), usage = TokenUsage.ZERO, reportedCostUsd = null,
            error = job.reason ?: "agent job ${job.status.name.lowercase()} without a result",
            finishedAt = job.finishedAt ?: lifecycle.now(),
        )
    }

    @Deprecated("Blocks a thread; use submit with a continuation", ReplaceWith("submit(spec, continuation)"))
    override fun start(spec: RunnerJobSpec): RunnerJobId = enqueue(spec, null)

    @Deprecated("Blocks a thread; use submit with a continuation and result(jobId)")
    override fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult {
        val deadline = lifecycle.now().plus(timeout)
        while (true) {
            result(jobId)?.let { return it }
            if (!lifecycle.now().isBefore(deadline)) {
                cancel(jobId)
                return result(jobId) ?: RunnerResult(
                    status = RunnerJobStatus.TIMED_OUT, summary = null, branch = null, commitSha = null, diffStat = null, verification = null,
                    artifacts = emptyList(), usage = TokenUsage.ZERO, reportedCostUsd = null,
                    error = "no result within ${timeout.toMinutes()} minutes", finishedAt = lifecycle.now(),
                )
            }
            LockSupport.parkNanos(AWAIT_POLL.toNanos())
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw pl.mos.backoffice.shared.IntegrationException("runner", "interrupted while waiting for agent job $jobId")
            }
        }
    }

    override fun status(jobId: RunnerJobId): RunnerJobStatus =
        JobLifecycle.existing(jobs.find(jobId), jobId).status.toRunnerStatus()

    /** Queued jobs end at once; leased ones are flagged and end when the controller reports. */
    override fun cancel(jobId: RunnerJobId) {
        tx.inTransaction {
            val job = jobs.lock(jobId) ?: return@inTransaction
            when {
                job.status == AgentJobStatus.QUEUED -> lifecycle.finish(job, AgentJobStatus.CANCELLED, "cancelled")
                job.status.active && !job.cancelRequested -> jobs.update(job.copy(cancelRequested = true))
                else -> Unit
            }
        }
    }

    override fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine> = jobs.events(jobId, after)

    /** Jobs always queue; the platform can run agents as soon as at least one pool exists. */
    override fun isAvailable(): Boolean = pools.findAll().isNotEmpty()

    /** ADR 0046: shared pools (every pool without an owner) and [userId]'s personal pool, with their online runners. */
    override fun executionTargets(userId: UUID?): List<ExecutionTarget> {
        val all = pools.findAll()
        val online = lifecycle.onlineRunnersByPool()
        fun target(kind: RunnerTarget, of: List<RunnerPool>) = ExecutionTarget(kind, of.size, of.sumOf { online[it.id] ?: 0 })
        return listOf(target(RunnerTarget.SHARED, all.filterNot { it.personal })) +
            (userId?.let { owner -> listOf(target(RunnerTarget.PERSONAL, all.filter { it.ownerUserId == owner })) } ?: emptyList())
    }

    /** ADR 0044: the run ended; its workspaces go at [keepFor] from now (a retry before that keeps using them). */
    override fun releaseWorkspaces(runId: UUID, keepFor: Duration) {
        val store = lifecycle.workspaces ?: return
        tx.inTransaction { store.scheduleRelease(runId, lifecycle.now().plus(keepFor)) }
    }

    override fun verify(token: String): JobCredential? {
        if (!token.startsWith(FleetTokens.JOB_PREFIX)) return null
        val hash = FleetTokens.hash(token)
        // the full job token (runner entrypoint) or the gateway-only agent token (coding agent), ADR 0029
        val (job, scope) = jobs.findJobByTokenHash(hash)?.let { it to JobTokenScope.FULL }
            ?: jobs.findJobByAgentTokenHash(hash)?.let { it to JobTokenScope.GATEWAY }
            ?: return null
        val expiresAt = job.leaseExpiresAt ?: return null
        if (!job.status.active || !expiresAt.isAfter(lifecycle.now())) return null
        return lifecycle.credentialFor(job, scope)
    }

    companion object {
        val AWAIT_POLL: Duration = Duration.ofSeconds(1)
    }
}
