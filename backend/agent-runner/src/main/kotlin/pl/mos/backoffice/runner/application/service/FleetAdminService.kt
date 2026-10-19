package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.AgentJobFilter
import pl.mos.backoffice.runner.application.port.`in`.AgentJobView
import pl.mos.backoffice.runner.application.port.`in`.IssuedRegistrationToken
import pl.mos.backoffice.runner.application.port.`in`.ManageAgentJobs
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.ManageRunners
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolView
import pl.mos.backoffice.runner.application.port.`in`.RunnerView
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.RegistrationTokenStore
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.runner.domain.RegistrationToken
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.usage.PageOf
import java.util.UUID

/** Runner pools, registration tokens, runners and agent jobs for platform operators (`fleet.view` / `fleet.manage`). */
class FleetAdminService(
    private val pools: RunnerPoolStore,
    private val nodes: RunnerNodeStore,
    private val registrations: RegistrationTokenStore,
    private val jobs: AgentJobStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageRunnerPools, ManageRunners, ManageAgentJobs {

    // ---------------------------------------------------------------- pools

    override fun list(): List<RunnerPoolView> {
        gate.require(RunnerPermissions.FLEET_VIEW)
        return poolViews(pools.findAll())
    }

    override fun get(poolId: String): RunnerPoolView {
        gate.require(RunnerPermissions.FLEET_VIEW)
        return poolViews(listOf(pool(poolId))).single()
    }

    override fun create(input: RunnerPoolInput): RunnerPoolView {
        gate.require(RunnerPermissions.FLEET_MANAGE)
        validate(input)
        val created = tx.inTransaction {
            if (pools.find(input.id) != null) throw InvalidStateException("runner pool ${input.id} exists already")
            val now = lifecycle.now()
            val pool = RunnerPool(
                input.id, input.name.trim(), input.description?.trim()?.ifEmpty { null }, input.labels, input.ceiling, input.allowedImages.map(String::trim),
                input.warmPoolSize, input.maxConcurrentJobs, input.projectIds, now, now,
            )
            pools.insert(pool)
            audit.record(AuditEntry("RUNNER_POOL_CREATE", "RunnerPool", pool.id, details = mapOf("name" to pool.name, "labels" to pool.labels)))
            pool
        }
        return poolViews(listOf(created)).single()
    }

    override fun update(poolId: String, input: RunnerPoolInput): RunnerPoolView {
        gate.require(RunnerPermissions.FLEET_MANAGE)
        if (input.id != poolId) throw ValidationException("the pool id cannot be changed", "id")
        validate(input)
        val updated = tx.inTransaction {
            val existing = pool(poolId)
            val pool = existing.copy(
                name = input.name.trim(), description = input.description?.trim()?.ifEmpty { null }, labels = input.labels, ceiling = input.ceiling,
                allowedImages = input.allowedImages.map(String::trim), warmPoolSize = input.warmPoolSize, maxConcurrentJobs = input.maxConcurrentJobs,
                projectIds = input.projectIds, updatedAt = lifecycle.now(),
            )
            pools.update(pool)
            audit.record(
                AuditEntry(
                    "RUNNER_POOL_UPDATE", "RunnerPool", pool.id,
                    details = AuditableChanges.changed(
                        "name" to (existing.name to pool.name),
                        "labels" to (existing.labels to pool.labels),
                        "ceiling" to (existing.ceiling.toString() to pool.ceiling.toString()),
                        "allowedImages" to (existing.allowedImages to pool.allowedImages),
                        "maxConcurrentJobs" to (existing.maxConcurrentJobs to pool.maxConcurrentJobs),
                        "projectIds" to (existing.projectIds.map(UUID::toString) to pool.projectIds.map(UUID::toString)),
                    ),
                ),
            )
            pool
        }
        return poolViews(listOf(updated)).single()
    }

    override fun delete(poolId: String) {
        gate.require(RunnerPermissions.FLEET_MANAGE)
        tx.inTransaction {
            pool(poolId)
            if (jobs.hasLiveJobs(poolId)) throw InvalidStateException("runner pool $poolId still has queued or running agent jobs; cancel them or wait")
            pools.delete(poolId)
            audit.record(AuditEntry("RUNNER_POOL_DELETE", "RunnerPool", poolId))
        }
    }

    /**
     * ADR 0046, laptop stacks only: the shared pool [poolId] (created when missing) and the configured registration token,
     * usable again after every start, so the stack's bundled runner-controller registers without a manual step.
     */
    @SystemOperation("startup: local stack fleet from configuration (runner.local-fleet); never exposed through a controller")
    fun bootstrapLocalFleet(poolId: String, registrationToken: String, allowedImages: List<String>, maxConcurrentJobs: Int) {
        tx.inTransaction {
            val now = lifecycle.now()
            if (pools.find(poolId) == null) {
                pools.insert(
                    RunnerPool(
                        poolId, "Local runners", "The runner-controller of this docker compose stack (ADR 0046)", emptyMap(), SandboxPolicy.DEFAULT,
                        allowedImages, 0, maxConcurrentJobs, emptySet(), now, now,
                    ),
                )
                audit.record(AuditEntry("RUNNER_POOL_CREATE", "RunnerPool", poolId, details = mapOf("name" to "Local runners", "reason" to "local-fleet")))
            }
            registrations.rearm(UUID.randomUUID(), poolId, FleetTokens.hash(registrationToken), now, now.plus(LOCAL_TOKEN_VALIDITY))
        }
    }

    override fun issueRegistrationToken(poolId: String): IssuedRegistrationToken {
        val user = gate.require(RunnerPermissions.FLEET_MANAGE)
        return tx.inTransaction {
            val pool = pool(poolId)
            val now = lifecycle.now()
            val generated = FleetTokens.generate(FleetTokens.REGISTRATION_PREFIX)
            val token = RegistrationToken(UUID.randomUUID(), pool.id, now, now.plus(lifecycle.settings.registrationTokenValidity), null)
            registrations.insert(token, generated.hash, user.login)
            audit.record(AuditEntry("RUNNER_REGISTRATION_TOKEN_CREATE", "RunnerPool", pool.id, details = mapOf("tokenId" to token.id.toString(), "expiresAt" to token.expiresAt.toString())))
            IssuedRegistrationToken(generated.plaintext, token.expiresAt, helmValues(pool, generated.plaintext))
        }
    }

    // ---------------------------------------------------------------- runners

    override fun list(poolId: String?): List<RunnerView> {
        gate.require(RunnerPermissions.FLEET_VIEW)
        val now = lifecycle.now()
        val running = jobs.countActiveByRunners()
        return nodes.list(poolId).map { runnerView(it, running[it.id] ?: 0, it.online(now, lifecycle.settings.onlineWindow)) }
    }

    override fun revoke(runnerId: UUID) {
        gate.require(RunnerPermissions.FLEET_MANAGE)
        tx.inTransaction {
            val node = nodes.find(runnerId) ?: throw NotFoundException("Runner", runnerId)
            if (node.revokedAt != null) return@inTransaction
            val requeued = lifecycle.revokeRunner(node)
            audit.record(AuditEntry("RUNNER_REVOKE", "Runner", runnerId.toString(), details = mapOf("poolId" to node.poolId, "name" to node.name, "requeuedJobs" to requeued)))
        }
    }

    // ---------------------------------------------------------------- agent jobs

    override fun list(filter: AgentJobFilter, page: Int, size: Int): PageOf<AgentJobView> {
        gate.require(RunnerPermissions.FLEET_VIEW)
        val result = jobs.page(filter, page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val online = onlineByPool()
        return PageOf(result.content.map { jobView(it, online) }, result.page, result.size, result.totalElements)
    }

    override fun get(jobId: RunnerJobId): AgentJobView {
        gate.require(RunnerPermissions.FLEET_VIEW)
        return jobView(JobLifecycle.existing(jobs.find(jobId), jobId), onlineByPool())
    }

    override fun cancel(jobId: RunnerJobId) {
        val user = gate.require(RunnerPermissions.FLEET_MANAGE)
        tx.inTransaction {
            val job = JobLifecycle.existing(jobs.lock(jobId), jobId)
            when {
                job.status == AgentJobStatus.QUEUED -> lifecycle.finish(job, AgentJobStatus.CANCELLED, "cancelled by ${user.displayName}")
                job.status.active -> jobs.update(job.copy(cancelRequested = true, reason = "cancellation requested by ${user.displayName}"))
                else -> throw InvalidStateException("agent job $jobId is already ${job.status}")
            }
            audit.record(AuditEntry("AGENT_JOB_CANCEL", "AgentJob", jobId.toString(), projectId = job.spec.usageContext.projectId, details = mapOf("status" to job.status.name)))
        }
    }

    // ---------------------------------------------------------------- views

    private fun pool(poolId: String): RunnerPool = pools.find(poolId) ?: throw NotFoundException("RunnerPool", poolId)

    private fun onlineByPool(): Map<String, Int> {
        val now = lifecycle.now()
        return nodes.list(null).filter { it.online(now, lifecycle.settings.onlineWindow) }.groupingBy { it.poolId }.eachCount()
    }

    private fun poolViews(list: List<RunnerPool>): List<RunnerPoolView> {
        val now = lifecycle.now()
        val allNodes = nodes.list(null).filter { it.revokedAt == null }
        val counts = jobs.countsByPool()
        return list.map { pool ->
            val poolNodes = allNodes.filter { it.poolId == pool.id }
            RunnerPoolView(
                input = RunnerPoolInput(pool.id, pool.name, pool.description, pool.labels, pool.ceiling, pool.allowedImages, pool.warmPoolSize, pool.maxConcurrentJobs, pool.projectIds),
                runners = poolNodes.size,
                onlineRunners = poolNodes.count { it.online(now, lifecycle.settings.onlineWindow) },
                queued = counts[pool.id]?.queued ?: 0,
                running = counts[pool.id]?.active ?: 0,
                createdAt = pool.createdAt,
                ownerUserId = pool.ownerUserId,
                llmCredentials = pool.llmCredentials,
            )
        }
    }

    private fun jobView(job: AgentJob, onlineByPool: Map<String, Int>): AgentJobView {
        val usage = job.spec.usageContext
        val reason = when {
            job.status == AgentJobStatus.QUEUED && job.poolId != null && (onlineByPool[job.poolId] ?: 0) == 0 ->
                "no online runner in pool ${job.poolId}" + (job.reason?.let { "; $it" } ?: "")
            else -> job.reason
        }
        return AgentJobView(
            id = job.id, label = job.label, poolId = job.poolId, runnerId = job.runnerId, status = job.status, mode = job.spec.mode.name,
            projectId = usage.projectId, runId = usage.runId, nodeId = usage.stepId, benchmarkId = usage.benchmarkId, model = job.spec.model,
            isolation = job.policy?.isolation, policy = job.policy, policySources = job.policySources, queuedAt = job.queuedAt,
            startedAt = job.startedAt, finishedAt = job.finishedAt, reason = reason, cost = job.costUsd?.let { Money(it) } ?: Money.ZERO,
            egressDenied = job.completion?.egressDenied.orEmpty(), cancelRequested = job.cancelRequested, leaseExpiresAt = job.leaseExpiresAt,
            attempts = job.leaseCount, priority = job.priority, gitAccess = job.spec.gitAccess,
        )
    }

    private fun validate(input: RunnerPoolInput) {
        val errors = mutableListOf<ValidationException.FieldError>()
        if (!RunnerPool.ID.matches(input.id)) errors += ValidationException.FieldError("id", "must match ${RunnerPool.ID.pattern}")
        if (input.name.isBlank()) errors += ValidationException.FieldError("name", "must not be blank")
        if (input.warmPoolSize < 0) errors += ValidationException.FieldError("warmPoolSize", "must not be negative")
        if (input.maxConcurrentJobs < 1) errors += ValidationException.FieldError("maxConcurrentJobs", "must be at least 1")
        if (input.allowedImages.any { it.isBlank() }) errors += ValidationException.FieldError("allowedImages", "must not contain blank patterns")
        if (input.id.startsWith(PERSONAL_PREFIX)) errors += ValidationException.FieldError("id", "ids starting with $PERSONAL_PREFIX are reserved for personal runners")
        lifecycle.resolver.validate(input.ceiling).forEach { errors += ValidationException.FieldError("ceiling", it) }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    // keys of deploy/helm/back-office-runner/values.yaml (backOffice.url/poolId, registration.token, backend)
    private fun helmValues(pool: RunnerPool, token: String): String = """
        |# back-office-runner chart values for pool ${pool.id}
        |backOffice:
        |  url: "${lifecycle.settings.apiUrl.trimEnd('/')}"
        |  poolId: "${pool.id}"
        |# single use, expires in 24 h; the controller exchanges it for a runner token on its first start
        |# (or put it into a Secret with key RUNNER_REGISTRATION_TOKEN and set registration.existingSecret)
        |registration:
        |  token: "$token"
        |# auto = AGENT_SANDBOX when the Agent Sandbox CRDs exist, else K8S_JOB
        |backend: auto
    """.trimMargin()

    companion object {
        const val MAX_PAGE_SIZE = 200
        private const val PERSONAL_PREFIX = "personal-"
        private val LOCAL_TOKEN_VALIDITY: java.time.Duration = java.time.Duration.ofDays(365)
    }
}

/** A runner as people see it (fleet administration and the personal runner page). */
internal fun runnerView(node: RunnerNode, running: Int, online: Boolean) = RunnerView(
    node.id, node.poolId, node.name, node.version, node.backends, node.runtimeClasses, node.maxConcurrent, running,
    node.lastSeenAt, online, node.cluster, node.registeredAt, node.revokedAt,
)
