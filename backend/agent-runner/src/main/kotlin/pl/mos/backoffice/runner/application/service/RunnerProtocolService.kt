package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.JobHeartbeatAck
import pl.mos.backoffice.runner.application.port.`in`.JobHeartbeatInput
import pl.mos.backoffice.runner.application.port.`in`.LeasedJob
import pl.mos.backoffice.runner.application.port.`in`.QueueDepth
import pl.mos.backoffice.runner.application.port.`in`.RunnerConfigView
import pl.mos.backoffice.runner.application.port.`in`.RunnerFleetProtocol
import pl.mos.backoffice.runner.application.port.`in`.RunnerRegistration
import pl.mos.backoffice.runner.application.port.`in`.RunnerRegistrationRequest
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.RegistrationTokenStore
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.runner.domain.JobCompletionReport
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.BudgetRefusals
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SystemOperation
import java.util.UUID

/** An unknown, used or expired registration token, or a revoked runner. Mapped to 401. */
class RunnerAuthenticationException(message: String) : RuntimeException(message)

/**
 * Runner protocol v2 (docs/architecture/10-agent-execution.md): register, config, lease, heartbeat,
 * complete. No person is behind these calls; the web adapter authenticates the runner token and every
 * operation is scoped to the runner's own pool and the jobs it leased.
 */
class RunnerProtocolService(
    private val pools: RunnerPoolStore,
    private val nodes: RunnerNodeStore,
    private val registrations: RegistrationTokenStore,
    private val jobs: AgentJobStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val audit: AuditPort,
    /** Resolves `signingKeys` for the config call; empty when no signer is configured. */
    private val signingKeys: () -> List<Map<String, Any?>>,
    /** Hard budget stops the LLM gateway applied (ADR 0025): a failed job they explain is tagged `budget-exceeded`. */
    private val budgetRefusals: BudgetRefusals = BudgetRefusals.NONE,
) : RunnerFleetProtocol {

    @SystemOperation("runner-controller registration, authenticated by a single-use bo_rrt_ token")
    override fun register(request: RunnerRegistrationRequest): RunnerRegistration {
        if (!request.registrationToken.startsWith(FleetTokens.REGISTRATION_PREFIX)) throw RunnerAuthenticationException("invalid registration token")
        if (request.name.isBlank() || request.version.isBlank()) throw ValidationException("name and version are required")
        return tx.inTransaction {
            val runnerId = UUID.randomUUID()
            val now = lifecycle.now()
            val token = registrations.consume(FleetTokens.hash(request.registrationToken), now, runnerId)
                ?: throw RunnerAuthenticationException("registration token is unknown, used or expired")
            val pool = pools.find(token.poolId) ?: throw RunnerAuthenticationException("registration token belongs to a deleted pool")
            val runnerToken = FleetTokens.generate(FleetTokens.RUNNER_PREFIX)
            val node = RunnerNode(
                id = runnerId, poolId = pool.id, name = request.name.trim().take(200), version = request.version.trim().take(100),
                cluster = request.cluster?.trim()?.take(200), backends = request.backends, runtimeClasses = request.runtimeClasses,
                maxConcurrent = (request.maxConcurrent ?: DEFAULT_MAX_CONCURRENT).coerceIn(1, MAX_CONCURRENT_CAP),
                registeredAt = now, lastSeenAt = now, revokedAt = null,
            )
            nodes.insert(node, runnerToken.hash)
            audit.record(
                AuditEntry(
                    "RUNNER_REGISTER", "Runner", runnerId.toString(),
                    details = mapOf("poolId" to pool.id, "name" to node.name, "version" to node.version, "cluster" to node.cluster),
                    actor = Actor(PrincipalKind.SYSTEM, null, "runner:${node.name}"),
                ),
            )
            RunnerRegistration(runnerId, runnerToken.plaintext, pool.id)
        }
    }

    @SystemOperation("token lookup for the runner-fleet endpoints")
    override fun authenticate(runnerToken: String): RunnerNode? {
        if (!runnerToken.startsWith(FleetTokens.RUNNER_PREFIX)) return null
        val node = nodes.findRunnerByTokenHash(FleetTokens.hash(runnerToken)) ?: return null
        if (node.revokedAt != null) return null
        val now = lifecycle.now()
        nodes.touch(node.id, now)
        return node.copy(lastSeenAt = now)
    }

    @SystemOperation("pool configuration for the authenticated runner-controller")
    override fun config(runner: RunnerNode): RunnerConfigView {
        val pool = pools.find(runner.poolId) ?: throw NotFoundException("RunnerPool", runner.poolId)
        val ceiling = lifecycle.resolver.resolve(listOf(lifecycle.platformLayer(), lifecycle.poolLayer(pool)), mandatoryHosts = lifecycle.mandatoryHosts(null) + lifecycle.localLoginHosts(pool)).policy
        return RunnerConfigView(pool.id, ceiling, pool.allowedImages, pool.warmPoolSize, lifecycle.settings.gateway, signingKeys())
    }

    @SystemOperation("lease of queued jobs of the runner's own pool")
    override fun lease(runner: RunnerNode, maxJobs: Int, freeSlots: Int?): List<LeasedJob> = tx.inTransaction {
        val pool = pools.find(runner.poolId) ?: return@inTransaction emptyList()
        val capacity = minOf(
            maxJobs.coerceIn(1, MAX_JOBS_PER_LEASE),
            freeSlots ?: Int.MAX_VALUE,
            pool.maxConcurrentJobs - jobs.countActive(pool.id),
            runner.maxConcurrent - jobs.countActiveByRunner(runner.id),
        )
        if (capacity <= 0) return@inTransaction emptyList()
        val now = lifecycle.now()
        val leaseExpiresAt = now.plus(lifecycle.settings.leaseDuration)
        jobs.lockQueued(pool.id, capacity).mapNotNull { job ->
            // the pool may have changed since submit: resolve again against the current ceiling
            val resolved = lifecycle.resolveFor(pool, job.spec, lifecycle.projectPolicy(job.spec.usageContext.projectId))
            if (!resolved.ok) {
                lifecycle.finish(job, AgentJobStatus.FAILED, "policy-violation: ${resolved.errors.joinToString("; ")}")
                return@mapNotNull null
            }
            val token = FleetTokens.generate(FleetTokens.JOB_PREFIX)
            val leased = job.copy(
                status = AgentJobStatus.LEASED, runnerId = runner.id, leaseExpiresAt = leaseExpiresAt, leasedAt = now,
                leaseCount = job.leaseCount + 1, policy = resolved.policy, policySources = resolved.sources, reason = null,
            )
            jobs.update(leased)
            jobs.setTokenHash(job.id, token.hash)
            LeasedJob(job.id, leaseExpiresAt, token.plaintext, job.spec.copy(policy = resolved.policy), resolved.policy, lifecycle.settings.gateway, pool.llmCredentials)
        }
    }

    @SystemOperation("heartbeat of a job leased by this runner")
    override fun heartbeat(runner: RunnerNode, jobId: RunnerJobId, heartbeat: JobHeartbeatInput): JobHeartbeatAck = tx.inTransaction {
        val job = ownedActiveJob(runner, jobId)
        val now = lifecycle.now()
        val status = when {
            heartbeat.phase == AgentJobStatus.RUNNING -> AgentJobStatus.RUNNING
            job.status == AgentJobStatus.RUNNING -> AgentJobStatus.RUNNING
            else -> AgentJobStatus.STARTING
        }
        val updated = job.copy(
            status = status,
            phase = heartbeat.phase.name,
            leaseExpiresAt = now.plus(lifecycle.settings.leaseDuration),
            startedAt = job.startedAt ?: now.takeIf { status == AgentJobStatus.RUNNING },
            sandboxName = heartbeat.sandboxName ?: job.sandboxName,
            node = heartbeat.node ?: job.node,
        )
        jobs.update(updated)
        JobHeartbeatAck(updated.leaseExpiresAt!!, updated.cancelRequested)
    }

    @SystemOperation("terminal report of a job leased by this runner")
    override fun complete(runner: RunnerNode, jobId: RunnerJobId, completion: JobCompletionReport) {
        if (!completion.status.terminal) throw ValidationException("status must be terminal (SUCCEEDED, FAILED, TIMED_OUT, CANCELLED)", "status")
        tx.inTransaction {
            val job = jobs.lock(jobId) ?: throw NotFoundException("AgentJob", jobId)
            if (job.runnerId != runner.id) throw InvalidStateException("agent job $jobId is not leased by this runner")
            if (job.status.terminal) {
                if (job.completion == null) jobs.update(job.copy(completion = completion))
                return@inTransaction
            }
            // the inline copy always carries the status (large results keep a trimmed copy inline, see SandboxCallbackService)
            val posted = jobs.loadResult(jobId)?.inline
            val (status, reason) = finalStatus(job, completion, posted?.status, posted != null)
            lifecycle.finish(job, status, budgetTagged(status, reason, budgetRefusals.refusalOf(jobId.value)), completion)
        }
    }

    @SystemOperation("queue depth of the runner's own pool (autoscaling)")
    override fun queueDepth(runner: RunnerNode, poolId: String): QueueDepth {
        if (poolId != runner.poolId) throw InvalidStateException("runner belongs to pool ${runner.poolId}")
        val counts = jobs.countsByPool()[poolId]
        return QueueDepth(counts?.queued ?: 0, counts?.active ?: 0)
    }

    private fun ownedActiveJob(runner: RunnerNode, jobId: RunnerJobId): AgentJob {
        val job = jobs.lock(jobId) ?: throw NotFoundException("AgentJob", jobId)
        if (job.runnerId != runner.id) throw InvalidStateException("agent job $jobId is not leased by this runner")
        if (!job.status.active) throw InvalidStateException("agent job $jobId is ${job.status}; stop its sandbox")
        return job
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 4
        const val MAX_CONCURRENT_CAP = 1000
        const val MAX_JOBS_PER_LEASE = 20

        /** A job that did not succeed after the gateway refused it for budget reads `budget-exceeded: <why>`. */
        fun budgetTagged(status: AgentJobStatus, reason: String?, refusal: String?): String? =
            if (refusal == null || status == AgentJobStatus.SUCCEEDED || status == AgentJobStatus.CANCELLED) reason
            else "${BudgetRefusals.REASON_PREFIX}: $refusal"

        /**
         * The job's end as the platform sees it: the controller's status, except that a sandbox which exited
         * "successfully" must also have posted a successful result.
         */
        fun finalStatus(job: AgentJob, completion: JobCompletionReport, resultStatus: RunnerJobStatus?, hasResult: Boolean): Pair<AgentJobStatus, String?> {
            val reason = completion.reason?.takeIf { it.isNotBlank() }
            return when (completion.status) {
                AgentJobStatus.SUCCEEDED -> when {
                    !hasResult -> AgentJobStatus.FAILED to (reason ?: "sandbox exited without posting a result")
                    resultStatus == RunnerJobStatus.SUCCEEDED -> AgentJobStatus.SUCCEEDED to reason
                    resultStatus == RunnerJobStatus.TIMED_OUT -> AgentJobStatus.TIMED_OUT to (reason ?: "agent reported a timeout")
                    else -> AgentJobStatus.FAILED to (reason ?: "agent reported a failure")
                }
                AgentJobStatus.CANCELLED -> AgentJobStatus.CANCELLED to (reason ?: if (job.cancelRequested) "cancelled" else "cancelled by the runner")
                AgentJobStatus.LOST -> AgentJobStatus.FAILED to (reason ?: "lost")
                else -> completion.status to reason
            }
        }
    }
}
