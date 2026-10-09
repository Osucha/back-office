package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.GatewayUrls
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.LeaseSignal
import pl.mos.backoffice.runner.application.port.out.ProjectPolicyStore
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.JobCompletionReport
import pl.mos.backoffice.runner.domain.RunnerLogEvent
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.runner.domain.policy.HostPatterns
import pl.mos.backoffice.runner.domain.policy.PolicyLayer
import pl.mos.backoffice.runner.domain.policy.PolicyLevel
import pl.mos.backoffice.runner.domain.policy.ResolvedPolicy
import pl.mos.backoffice.runner.domain.policy.SandboxPolicyResolver
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.platform.JobTokenScope
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.SandboxPolicy
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/** Settings of the control plane, taken from `backoffice.runner.*` by the adapter configuration. */
data class FleetSettings(
    val apiUrl: String,
    val leaseDuration: Duration = Duration.ofSeconds(60),
    val onlineWindow: Duration = Duration.ofSeconds(90),
    val queueTimeout: Duration = Duration.ofHours(24),
    val maxUnacknowledgedLeases: Int = 5,
    val registrationTokenValidity: Duration = Duration.ofHours(24),
    val uploadUrlTtl: Duration = Duration.ofMinutes(15),
    val maxUploadBytes: Long = 1L shl 30,
    val inlineResultMaxBytes: Int = 64 * 1024,
    val extraAllowedModels: List<String> = emptyList(),
    val platformPolicy: SandboxPolicy = SandboxPolicy.DEFAULT,
    /** Metered LLM proxy calls per job; 0 or less = unlimited. */
    val maxLlmRequestsPerJob: Long = 2000,
) {
    private val api: String get() = apiUrl.trimEnd('/')

    val gateway: GatewayUrls
        get() = GatewayUrls(
            anthropicBaseUrl = "$api/api/v1/llm/anthropic",
            openaiBaseUrl = "$api/api/v1/llm/openai/v1",
            apiBaseUrl = "$api/api/v1",
        )

    /** `host[:port]` of the API (the LLM gateway and callbacks); always allowed egress. */
    val apiHost: String?
        get() = runCatching { URI(api) }.getOrNull()?.let { uri ->
            uri.host?.lowercase()?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host }
        }
}

/** Where a job goes: the chosen pool and its resolved policy, or why no pool fits. */
data class Routing(val pool: RunnerPool?, val resolved: ResolvedPolicy?, val selector: Map<String, String>, val reason: String?)

/**
 * The state machine shared by every entry point (submit, protocol calls, admin, lease scan): routing and
 * policy resolution, terminal transitions with the continuation enqueue, re-queueing and job credentials.
 * Callers hold the job's row lock (or just inserted it) and run inside [pl.mos.backoffice.runner.application.port.out.Transactions].
 */
class JobLifecycle(
    private val jobs: AgentJobStore,
    private val pools: RunnerPoolStore,
    private val nodes: RunnerNodeStore,
    private val projectPolicies: ProjectPolicyStore,
    private val workQueue: WorkQueue?,
    private val signal: LeaseSignal,
    private val events: EventPublisher,
    private val clock: Clock,
    val settings: FleetSettings,
) {
    private val log = LoggerFactory.getLogger(JobLifecycle::class.java)
    val resolver = SandboxPolicyResolver()

    fun now(): Instant = clock.now()

    // ---------------------------------------------------------------- policy and routing

    /** The policy on the spec as the TEMPLATE layer; the spec timeout caps `timeoutSeconds`. */
    fun templateLayer(spec: RunnerJobSpec): PolicyLayer {
        val requested = spec.policy ?: SandboxPolicy.DEFAULT
        val specTimeout = spec.timeout.seconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        return PolicyLayer(PolicyLevel.TEMPLATE, requested.copy(timeoutSeconds = minOf(requested.timeoutSeconds ?: specTimeout, specTimeout)))
    }

    fun platformLayer() = PolicyLayer(PolicyLevel.PLATFORM, settings.platformPolicy)

    /** The pool ceiling as a layer; its `poolSelector` is not a request and is ignored. */
    fun poolLayer(pool: RunnerPool) = PolicyLayer(PolicyLevel.POOL, pool.ceiling.copy(poolSelector = emptyMap()))

    fun mandatoryHosts(spec: RunnerJobSpec?): List<String> =
        (listOfNotNull(settings.apiHost, HostPatterns.hostOf(spec?.repository?.url)) +
            spec?.repositories.orEmpty().mapNotNull { HostPatterns.hostOf(it.url) }).distinct()

    fun resolveFor(pool: RunnerPool, spec: RunnerJobSpec, projectPolicy: SandboxPolicy?): ResolvedPolicy =
        resolver.resolve(
            listOfNotNull(platformLayer(), poolLayer(pool), projectPolicy?.let { PolicyLayer(PolicyLevel.PROJECT, it) }, templateLayer(spec)),
            allowedImages = pool.allowedImages,
            mandatoryHosts = mandatoryHosts(spec),
        )

    fun projectPolicy(projectId: java.util.UUID?): SandboxPolicy? = projectId?.let(projectPolicies::findPolicy)

    fun route(spec: RunnerJobSpec): Routing {
        val projectId = spec.usageContext.projectId
        val projectPolicy = projectPolicy(projectId)
        val selector = resolver.resolve(
            listOfNotNull(platformLayer(), projectPolicy?.let { PolicyLayer(PolicyLevel.PROJECT, it) }, templateLayer(spec)),
        ).policy.poolSelector
        val all = pools.findAll()
        if (all.isEmpty()) return Routing(null, null, selector, "no runner pool exists; create one in Administration → Runner fleet")
        val rejections = mutableListOf<String>()
        val candidates = all.mapNotNull { pool ->
            when {
                !pool.acceptsProject(projectId) -> null.also { rejections += "${pool.id}: project not allowed" }
                !pool.matches(selector) -> null.also { rejections += "${pool.id}: labels do not match poolSelector $selector" }
                else -> {
                    val resolved = resolveFor(pool, spec, projectPolicy)
                    if (resolved.ok) pool to resolved else null.also { rejections += "${pool.id}: ${resolved.errors.joinToString("; ")}" }
                }
            }
        }
        if (candidates.isEmpty()) return Routing(null, null, selector, "no runner pool can run this job (${rejections.joinToString(" | ")})")
        val now = clock.now()
        val online = nodes.list(null).filter { it.online(now, settings.onlineWindow) }.groupingBy { it.poolId }.eachCount()
        val queued = jobs.countsByPool()
        val (pool, resolved) = candidates.sortedWith(
            compareByDescending<Pair<RunnerPool, ResolvedPolicy>> { (online[it.first.id] ?: 0) > 0 }
                .thenBy { queued[it.first.id]?.queued ?: 0 }
                .thenBy { it.first.id },
        ).first()
        return Routing(pool, resolved, selector, resolved.warnings.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** Puts a routed job into [pool] (or leaves it unassigned with the reason). */
    fun applyRouting(job: AgentJob, routing: Routing): AgentJob = job.copy(
        poolId = routing.pool?.id,
        policy = routing.resolved?.policy,
        policySources = routing.resolved?.sources.orEmpty(),
        poolSelector = routing.selector,
        reason = routing.reason,
    )

    // ---------------------------------------------------------------- transitions

    /** Terminal state: token revoked, continuation enqueued (same transaction), log line published. */
    fun finish(job: AgentJob, status: AgentJobStatus, reason: String?, completion: JobCompletionReport? = null, costUsd: BigDecimal? = null): AgentJob {
        require(status.terminal) { "$status is not terminal" }
        val now = clock.now()
        val finished = job.copy(
            status = status,
            finishedAt = now,
            reason = reason ?: job.reason,
            completion = completion ?: job.completion,
            leaseExpiresAt = null,
            costUsd = costUsd ?: job.costUsd,
        )
        jobs.update(finished)
        jobs.setTokenHash(job.id, null)
        enqueueContinuation(finished)
        val usage = job.spec.usageContext
        events.publish(
            RunnerLogEvent(
                job.id, "agent job ${status.name.lowercase()}" + (finished.reason?.let { ": $it" } ?: ""), false, now,
                usage.projectId, usage.runId, if (status == AgentJobStatus.SUCCEEDED) "INFO" else "WARN",
            ),
        )
        return finished
    }

    /** Back to the queue of its pool: runner, lease and token are dropped. */
    fun requeue(job: AgentJob, reason: String, countAsLost: Boolean): AgentJob {
        val queued = job.copy(
            status = AgentJobStatus.QUEUED,
            runnerId = null,
            leaseExpiresAt = null,
            phase = null,
            sandboxName = null,
            node = null,
            lostCount = job.lostCount + if (countAsLost) 1 else 0,
            reason = reason,
        )
        jobs.update(queued)
        jobs.setTokenHash(job.id, null)
        signal.wake(job.poolId)
        return queued
    }

    private fun enqueueContinuation(job: AgentJob) {
        val continuation = job.continuation ?: return
        val queue = workQueue
        if (queue == null) {
            log.warn("no WorkQueue configured: continuation {} of agent job {} not enqueued", continuation.kind, job.id)
            return
        }
        queue.enqueue(
            continuation.copy(
                payload = continuation.payload + mapOf(
                    "jobId" to job.id.toString(),
                    "status" to job.status.toRunnerStatus().name,
                    "agentJobStatus" to job.status.name,
                ),
            ),
        )
    }

    fun wake(poolId: String?) = signal.wake(poolId)

    // ---------------------------------------------------------------- credentials

    fun credentialFor(job: AgentJob, scope: JobTokenScope = JobTokenScope.FULL): JobCredential {
        val usage = job.spec.usageContext
        return JobCredential(
            jobId = job.id.value,
            projectId = usage.projectId,
            runId = usage.runId,
            nodeId = usage.stepId,
            benchmarkId = usage.benchmarkId,
            attemptId = usage.attemptId,
            modelAllowlist = linkedSetOf(job.spec.model.label) + settings.extraAllowedModels,
            maxCostUsd = job.policy?.maxCostUsd,
            maxTokens = job.policy?.maxTokens,
            expiresAt = job.leaseExpiresAt ?: clock.now(),
            maxRequests = settings.maxLlmRequestsPerJob.takeIf { it > 0 },
            scope = scope,
        )
    }

    companion object {
        /** DNS-1123 label: lowercase alphanumerics and dashes, max 63 chars, starts and ends alphanumeric. */
        fun normaliseLabel(label: String): String {
            val cleaned = label.lowercase().replace(Regex("[^a-z0-9-]"), "-").replace(Regex("-{2,}"), "-").trim('-')
            val cut = cleaned.take(63).trimEnd('-')
            return cut.ifEmpty { "job-${System.nanoTime().toString(36)}" }
        }

        fun existing(job: AgentJob?, id: RunnerJobId): AgentJob = job ?: throw pl.mos.backoffice.shared.NotFoundException("AgentJob", id)
    }
}
