package pl.mos.backoffice.runnercontroller.engine

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressRegistry
import pl.mos.backoffice.runnercontroller.policy.PolicyResolver
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import pl.mos.backoffice.runnercontroller.policy.ResolvedPolicy
import pl.mos.backoffice.runnercontroller.policy.SandboxPolicy
import pl.mos.backoffice.runnercontroller.protocol.AgentJobAssignment
import pl.mos.backoffice.runnercontroller.protocol.AgentJobStatus
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeHttpException
import pl.mos.backoffice.runnercontroller.protocol.GatewayUrls
import pl.mos.backoffice.runnercontroller.protocol.HeartbeatPhase
import pl.mos.backoffice.runnercontroller.protocol.JobCompletion
import pl.mos.backoffice.runnercontroller.protocol.JobHeartbeat
import pl.mos.backoffice.runnercontroller.protocol.LeaseRequest
import pl.mos.backoffice.runnercontroller.protocol.RunnerConfig
import pl.mos.backoffice.runnercontroller.sandbox.CompletionMapper
import pl.mos.backoffice.runnercontroller.sandbox.JobBundle
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.SandboxHandle
import pl.mos.backoffice.runnercontroller.sandbox.SandboxLaunchFailed
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxOutcome
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import pl.mos.backoffice.runnercontroller.sandbox.Workspaces
import pl.mos.backoffice.runnercontroller.security.AssignmentVerifier
import pl.mos.backoffice.runnercontroller.security.Verification
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** `spec.llmCredentials` of an assignment whose Claude Code uses the personal runner owner's own login (ADR 0040). */
private const val LOCAL_LOGIN = "LOCAL_LOGIN"

/** A job this controller runs. Mutated only on the monitor thread (after [JobSupervisor.accept] put it in). */
class RunningJob(
    val jobId: String,
    val handle: SandboxHandle,
    val launchedAt: Instant,
    val launchedNanos: Long,
    val deadline: Instant,
    @Volatile var leaseExpiresAt: String,
    /** ADR 0044: the run workspace the sandbox uses (never deleted while it runs). */
    val workspace: String? = null,
) {
    var phase: HeartbeatPhase = HeartbeatPhase.STARTING
    var lastHeartbeat: Instant = Instant.EPOCH
    var startRecorded = false
    var lastObservation: SandboxObservation? = null
    var cancelRequested = false
}

/**
 * The controller loop (doc 10, "Job lifecycle"): lease -> verify -> resolve policy -> launch sandbox ->
 * watch + heartbeat -> complete -> delete. Leasing runs on its own thread with long polls and asks only for
 * as many jobs as there are free slots; one monitor thread observes sandboxes, sends heartbeats every
 * `heartbeatSeconds`, enforces deadlines and reports completions.
 */
class JobSupervisor(
    private val props: ControllerProperties,
    private val client: BackOfficeClient,
    private val backend: SandboxBackend,
    private val egress: EgressRegistry,
    private val resolver: PolicyResolver,
    private val json: JsonMapper,
    meters: MeterRegistry,
    private val now: () -> Instant = Instant::now,
    private val configProvider: () -> RunnerConfig = client::config,
    private val env: (String) -> String? = System::getenv,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val running = ConcurrentHashMap<String, RunningJob>()
    private val pendingCompletions = ConcurrentHashMap<String, JobCompletion>()
    private val stopped = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val leaseLock = ReentrantLock()
    private val slotFreed = leaseLock.newCondition()

    @Volatile var config: RunnerConfig = RunnerConfig()
        private set
    @Volatile private var verifier = AssignmentVerifier(emptyList())
    @Volatile private var lastConfigRefresh = Instant.EPOCH
    val poolId: String get() = config.poolId ?: "default"

    private var monitor: ScheduledExecutorService? = null
    private var leaseThread: Thread? = null

    private val startTimer = Timer.builder("backoffice.sandbox.start")
        .description("Time from launching a sandbox until its agent container runs")
        .publishPercentileHistogram()
        .register(meters)
    private val completedCounter: (AgentJobStatus) -> Counter = { status ->
        Counter.builder("backoffice.runner.jobs.completed").tag("status", status.name).register(meters)
    }
    private val rejectedCounter: (String) -> Counter = { reason ->
        Counter.builder("backoffice.runner.assignments.rejected").tag("reason", reason).register(meters)
    }

    init {
        meters.gauge("backoffice.runner.active.jobs", running) { it.size.toDouble() }
        meters.gauge("backoffice.runner.free.slots", running) { (props.maxConcurrent - it.size).coerceAtLeast(0).toDouble() }
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    /** Fetches the pool configuration, prepares the pool and removes sandboxes left over from a previous run. */
    fun bootstrap() {
        refreshConfig()
        backend.gitCredentials.warnings().forEach { log.warn("{}", it) }
        backend.ensurePool(PoolSettings(poolId, config.warmPoolSize ?: 0, defaultPolicy()))
        cleanupOrphans()
    }

    fun start() {
        bootstrap()
        monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sandbox-monitor").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ safeTick() }, props.monitorIntervalMillis, props.monitorIntervalMillis, TimeUnit.MILLISECONDS)
            if (backend.supportsWorkspaces) {
                it.scheduleWithFixedDelay({ safeSyncWorkspaces() }, props.workspaceSyncSeconds, props.workspaceSyncSeconds, TimeUnit.SECONDS)
            }
        }
        leaseThread = Thread.ofPlatform().name("lease-loop").daemon(true).start { leaseLoop() }
        log.info("runner-controller started: pool={} backend={} maxConcurrent={}", poolId, backend.kind, props.maxConcurrent)
    }

    /**
     * Graceful shutdown: stop leasing, let running jobs finish for up to [grace], then delete the remaining
     * sandboxes and hand their jobs back as `LOST` (reason `runner-shutdown`) so Back Office can re-queue them.
     */
    fun shutdown(grace: Duration = Duration.ofSeconds(props.shutdownGraceSeconds)) {
        if (!draining.compareAndSet(false, true)) return
        log.info("draining: {} running job(s), grace {}", running.size, grace)
        leaseThread?.interrupt()
        val until = System.nanoTime() + grace.toNanos()
        while (running.isNotEmpty() && System.nanoTime() < until) {
            if (monitor == null) safeTick()
            Thread.sleep(200)
        }
        monitor?.shutdown()
        monitor?.awaitTermination(30, TimeUnit.SECONDS)
        running.values.toList().forEach { job ->
            log.warn("job {} still running after the grace period; handing it back", job.jobId)
            runCatching { backend.delete(job.handle) }
            egress.forget(job.jobId)
            running.remove(job.jobId)
            report(job.jobId, JobCompletion(AgentJobStatus.LOST, reason = "runner-shutdown", durationMs = durationMs(job)))
        }
        stopped.set(true)
    }

    fun isDraining() = draining.get()

    // ------------------------------------------------------------------------------------------ config

    fun refreshConfig() {
        val c = configProvider()
        config = c
        verifier = AssignmentVerifier(c.signingKeys, props.verification.requireSignature, Duration.ofSeconds(props.verification.clockSkewSeconds), now)
        lastConfigRefresh = now()
        log.info("pool config: pool={} allowedImages={} warmPoolSize={} signingKeys={}", c.poolId, c.allowedImages, c.warmPoolSize, verifier.keyCount)
    }

    private fun defaultPolicy(): ResolvedPolicy? = runCatching {
        resolver.resolve(SandboxPolicy.EMPTY, SandboxPolicy.from(config.ceiling), config.allowedImages, backend.kind, null, emptyList(), gatewayList(config.gateway))
    }.getOrNull()

    // ------------------------------------------------------------------------------------------ leasing

    private fun leaseLoop() {
        var backoff = 1000L
        while (!stopped.get() && !draining.get()) {
            try {
                if (leaseOnce()) backoff = 1000L
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: RuntimeException) {
                if (draining.get() || !pauseAfterLeaseFailure(e, backoff)) return
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    /** Leases up to the free slots and launches the assignments; false when every slot was busy (waited up to 1 s). */
    private fun leaseOnce(): Boolean {
        val free = props.maxConcurrent - running.size
        if (free <= 0) {
            leaseLock.withLock { slotFreed.await(1, TimeUnit.SECONDS) }
            return false
        }
        if (Duration.between(lastConfigRefresh, now()) > Duration.ofMinutes(5)) runCatching { refreshConfig() }
        val assignments = client.lease(LeaseRequest(minOf(free, props.lease.maxJobsPerLease), props.lease.waitSeconds, free))
        assignments.forEach { accept(it) }
        return true
    }

    /** Logs a failed lease and sleeps [backoff] ms; false when the sleep was interrupted (shutdown). */
    private fun pauseAfterLeaseFailure(e: RuntimeException, backoff: Long): Boolean {
        val detail = if (e is BackOfficeHttpException && e.status == 401) "runner token rejected (revoked?)" else e.message
        log.warn("lease failed: {}; retrying in {} ms", detail, backoff)
        return try {
            Thread.sleep(backoff)
            true
        } catch (_: InterruptedException) {
            false
        }
    }

    /** Verifies, resolves and launches one assignment. Returns true when a sandbox was started. */
    fun accept(a: AgentJobAssignment): Boolean {
        var verification = verifier.verify(a)
        if (verification is Verification.Rejected && verification.reason.startsWith("unknown signing key")) {
            runCatching { refreshConfig() }
            verification = verifier.verify(a)
        }
        if (verification is Verification.Rejected) {
            // Not reported: an assignment that does not verify is not trusted to name a real job.
            log.error("rejecting assignment {}: {}", a.jobId, verification.reason)
            rejectedCounter("verification").increment()
            return false
        }
        if (running.containsKey(a.jobId)) return false

        val spec = a.spec.deepCopy()
        val localLogin = try {
            localLoginCredential(spec)
        } catch (e: PolicyViolation) {
            log.warn("job {}: {}", a.jobId, e.message)
            rejectedCounter("local-login").increment()
            report(a.jobId, JobCompletion(AgentJobStatus.FAILED, reason = "local-login: ${e.message}"))
            return false
        }
        val (git, policy) = try {
            // least privilege (ADR 0031), per repository: READ gets the read token, PUSH_MR the push token, NONE nothing
            val plan = backend.gitCredentials.plan(spec)
            plan to resolver.resolve(
                requested = SandboxPolicy.from(a.policy),
                ceiling = SandboxPolicy.from(config.ceiling),
                allowedImages = config.allowedImages,
                backend = backend.kind,
                specTimeoutSeconds = spec.get("timeoutSeconds")?.takeIf { it.isNumber }?.asInt(),
                // every repository host is reachable, whatever its level (NONE repositories are cloned anonymously)
                repositoryUrls = plan.repositories.urls,
                gatewayUrls = gatewayList(effectiveGateway(spec)) + localLogin?.let { props.localLogin.hosts }.orEmpty(),
            )
        } catch (e: PolicyViolation) {
            log.warn("job {} violates the pool policy: {}", a.jobId, e.message)
            rejectedCounter("policy").increment()
            report(a.jobId, JobCompletion(AgentJobStatus.FAILED, reason = "${CompletionMapper.REASON_POLICY_VIOLATION}: ${e.message}"))
            return false
        }
        policy.notes.forEach { log.info("job {} policy: {}", a.jobId, it) }
        log.info("job {} git access {}", a.jobId, git.describe())
        git.notes.forEach { log.warn("job {} git: {}", a.jobId, it) }
        prepareSpec(spec, policy)

        val proxy = egress.register(a.jobId, policy.egressHosts)
        val launchedAt = now()
        val deadline = launchedAt.plusSeconds(policy.timeoutSeconds.toLong() + props.deadlineGraceSeconds)
        val request = LaunchRequest(
            jobId = a.jobId,
            poolId = poolId,
            projectId = a.projectId ?: spec.get("projectId")?.takeIf { it.isString }?.asString(),
            runId = spec.get("runId")?.takeIf { it.isString }?.asString(),
            policy = policy,
            bundle = JobBundle(json.writeValueAsString(spec), json.writeValueAsString(policy.toContractMap()), a.jobId, a.jobToken),
            gitAccess = git.access,
            gitCredential = git.legacy.credential,
            proxy = proxy,
            deadline = deadline,
            repositoryTokens = git.tokens,
            repositoryAccess = git.repositories.repositories.filter { git.repositories.multi }.associate { it.key to it.access },
            localLoginCredential = localLogin,
            workspace = workspaceOf(a.jobId, spec),
        )
        val handle = try {
            backend.launch(request)
        } catch (e: PolicyViolation) {
            egress.forget(a.jobId)
            rejectedCounter("policy").increment()
            report(a.jobId, JobCompletion(AgentJobStatus.FAILED, reason = "${CompletionMapper.REASON_POLICY_VIOLATION}: ${e.message}"))
            return false
        } catch (e: RuntimeException) {
            egress.forget(a.jobId)
            log.error("job {}: sandbox launch failed", a.jobId, e)
            val reason = if (e is SandboxLaunchFailed) "SandboxLaunchFailed: ${e.message}" else "SandboxLaunchFailed: ${e.javaClass.simpleName}"
            report(a.jobId, JobCompletion(AgentJobStatus.FAILED, reason = reason.take(500)))
            return false
        }
        val job = RunningJob(a.jobId, handle, launchedAt, System.nanoTime(), deadline, a.leaseExpiresAt, request.workspace)
        running[a.jobId] = job
        log.info("job {} launched as {} (isolation {}, image {}, timeout {}s)", a.jobId, handle.name, policy.isolation, policy.image, policy.timeoutSeconds)
        return true
    }

    /**
     * ADR 0040: for a LOCAL_LOGIN assignment the name of the variable holding the owner's Claude Code login, null for
     * gateway jobs. Throws when this controller cannot deliver it (not DOCKER, or the variable is not set).
     */
    private fun localLoginCredential(spec: ObjectNode): String? {
        if (spec.get("llmCredentials")?.asString() != LOCAL_LOGIN) return null
        if (backend.kind != BackendKind.DOCKER) throw PolicyViolation("Claude Code with the owner's own login runs only on a personal runner (DOCKER backend)")
        val name = props.localLogin.tokenEnv
        if (env(name).isNullOrBlank()) throw PolicyViolation("$name is not set on this runner-controller; run claude setup-token and start it with -e $name")
        return name
    }

    /** The copy of the spec delivered to the sandbox: gateway filled in, timeout clamped to the policy. */
    private fun prepareSpec(spec: ObjectNode, policy: ResolvedPolicy) {
        val gw = effectiveGateway(spec)
        if (spec.get("gateway") == null || spec.get("gateway").isNull) {
            spec.putObject("gateway").apply {
                gw?.anthropicBaseUrl?.let { put("anthropicBaseUrl", it) }
                gw?.openaiBaseUrl?.let { put("openaiBaseUrl", it) }
                gw?.apiBaseUrl?.let { put("apiBaseUrl", it) }
            }
        }
        val specTimeout = spec.get("timeoutSeconds")?.takeIf { it.isNumber }?.asInt()
        if (specTimeout == null || specTimeout > policy.timeoutSeconds) spec.put("timeoutSeconds", policy.timeoutSeconds)
    }

    private fun effectiveGateway(spec: ObjectNode): GatewayUrls? {
        val g = spec.get("gateway")
        if (g != null && g.isObject) {
            return GatewayUrls(
                g.get("anthropicBaseUrl")?.asString() ?: config.gateway?.anthropicBaseUrl,
                g.get("openaiBaseUrl")?.asString() ?: config.gateway?.openaiBaseUrl,
                g.get("apiBaseUrl")?.asString() ?: config.gateway?.apiBaseUrl,
            )
        }
        return config.gateway
    }

    private fun gatewayList(g: GatewayUrls?) = listOf(g?.anthropicBaseUrl, g?.openaiBaseUrl, g?.apiBaseUrl)

    // ------------------------------------------------------------------------------------------ monitoring

    private fun safeTick() {
        try {
            tick()
        } catch (e: Throwable) {
            log.error("monitor tick failed", e)
        }
    }

    /** ADR 0044: the run workspace of [spec] when this backend can keep one; otherwise the job gets a throwaway workspace. */
    private fun workspaceOf(jobId: String, spec: ObjectNode): String? {
        if (!Workspaces.requested(spec)) return null
        val name = Workspaces.nameOf(spec)
        when {
            name == null -> log.warn("job {}: invalid workspace name in the spec; using a throwaway workspace", jobId)
            !backend.supportsWorkspaces -> log.warn("job {}: backend {} keeps no run workspaces; using a throwaway workspace", jobId, backend.kind)
            else -> return name
        }
        return null
    }

    /**
     * ADR 0044: reports the run workspaces this controller holds and deletes the ones Back Office released (never one
     * a running sandbox uses). Skipped when the backend cannot list them, so nothing is deleted by mistake.
     */
    fun syncWorkspaces() {
        val held = backend.listWorkspaces(poolId) ?: return
        val release = client.syncWorkspaces(held)
        val inUse = running.values.mapNotNull { it.workspace }.toSet()
        release.filter { it in held && it !in inUse }.forEach { backend.deleteWorkspace(it) }
    }

    private fun safeSyncWorkspaces() {
        try {
            syncWorkspaces()
        } catch (e: RuntimeException) {
            log.warn("workspace sync failed: {}", e.message)
        }
    }

    /** One monitor pass over every running job (public for tests). */
    fun tick() {
        retryPendingCompletions()
        running.values.toList().forEach { job ->
            try {
                monitorJob(job)
            } catch (e: RuntimeException) {
                log.warn("job {}: monitoring failed: {}", job.jobId, e.message)
            }
        }
    }

    private fun monitorJob(job: RunningJob) {
        val t = now()
        val obs = backend.observe(job.handle)
        job.lastObservation = obs
        if (obs.phase == SandboxPhase.RUNNING && obs.bundleDelivered) {
            if (!job.startRecorded) {
                job.startRecorded = true
                startTimer.record(Duration.ofNanos(System.nanoTime() - job.launchedNanos))
            }
            job.phase = HeartbeatPhase.RUNNING
        }
        val outcome = CompletionMapper.map(
            obs, job.launchedAt, t,
            Duration.ofSeconds(props.imagePullGraceSeconds), Duration.ofSeconds(props.startTimeoutSeconds),
        )
        when {
            outcome != null -> finish(job, outcome, obs)
            t.isAfter(job.deadline) -> {
                log.warn("job {} exceeded its deadline {}; deleting the sandbox", job.jobId, job.deadline)
                finish(job, SandboxOutcome(AgentJobStatus.TIMED_OUT, obs.exitCode, CompletionMapper.REASON_DEADLINE), obs)
            }
            Duration.between(job.lastHeartbeat, t).seconds >= props.heartbeatSeconds -> heartbeat(job, obs)
        }
    }

    private fun heartbeat(job: RunningJob, obs: SandboxObservation) {
        job.lastHeartbeat = now()
        try {
            val response = client.heartbeat(job.jobId, JobHeartbeat(job.phase, job.handle.name, obs.node))
            response.leaseExpiresAt?.let { job.leaseExpiresAt = it }
            if (response.cancelRequested && !job.cancelRequested) {
                job.cancelRequested = true
                log.info("job {}: cancellation requested", job.jobId)
                finish(job, SandboxOutcome(AgentJobStatus.CANCELLED, obs.exitCode, "cancel-requested"), obs)
            }
        } catch (e: BackOfficeHttpException) {
            if (e.status == 409 || e.status == 404 || e.status == 410) {
                log.warn("job {}: lease lost (HTTP {}); deleting the sandbox", job.jobId, e.status)
                runCatching { backend.delete(job.handle) }
                egress.forget(job.jobId)
                removeRunning(job.jobId)
            } else {
                log.warn("job {}: heartbeat failed: {}", job.jobId, e.message)
            }
        } catch (e: RuntimeException) {
            log.warn("job {}: heartbeat failed: {}", job.jobId, e.message)
        }
    }

    private fun finish(job: RunningJob, outcome: SandboxOutcome, obs: SandboxObservation?) {
        runCatching { egress.record(backend.egressDecisions(job.handle)) }
            .onFailure { log.warn("job {}: cannot read egress decisions: {}", job.jobId, it.message) }
        egress.unregister(job.jobId)
        val summary = egress.summary(job.jobId)
        if (!props.keepFinishedSandboxes) {
            runCatching { backend.delete(job.handle) }.onFailure { log.warn("job {}: delete failed: {}", job.jobId, it.message) }
        }
        egress.forget(job.jobId)
        removeRunning(job.jobId)
        val completion = JobCompletion(
            status = outcome.status,
            exitCode = outcome.exitCode,
            reason = outcome.reason,
            imageDigest = obs?.imageDigest,
            egressLogDigest = summary.digest,
            egressDenied = summary.denied,
            durationMs = durationMs(job),
        )
        log.info("job {} finished: {} reason={} exit={} egress decisions={} denied={}", job.jobId, completion.status, completion.reason,
            completion.exitCode, summary.decisions, summary.denied)
        report(job.jobId, completion)
    }

    private fun removeRunning(jobId: String) {
        running.remove(jobId)
        leaseLock.withLock { slotFreed.signalAll() }
    }

    private fun durationMs(job: RunningJob) = Duration.between(job.launchedAt, now()).toMillis()

    /** Posts a completion; on failure it is retried on every tick (409 = Back Office already closed the job). */
    private fun report(jobId: String, completion: JobCompletion) {
        completedCounter(completion.status).increment()
        if (!send(jobId, completion)) pendingCompletions[jobId] = completion
    }

    private fun send(jobId: String, completion: JobCompletion): Boolean = try {
        client.complete(jobId, completion)
        true
    } catch (e: BackOfficeHttpException) {
        if (e.status in 400..499 && e.status != 408 && e.status != 429) {
            log.warn("job {}: completion refused (HTTP {}); dropping it", jobId, e.status)
            true
        } else {
            false
        }
    } catch (e: RuntimeException) {
        log.warn("job {}: cannot report completion yet: {}", jobId, e.message)
        false
    }

    private fun retryPendingCompletions() {
        pendingCompletions.entries.toList().forEach { (jobId, completion) ->
            if (send(jobId, completion)) pendingCompletions.remove(jobId)
        }
    }

    fun pendingCompletionCount() = pendingCompletions.size

    // ------------------------------------------------------------------------------------------ orphans

    /** Sandboxes of this pool that no running job owns (after a restart): deleted, their jobs reported LOST. */
    fun cleanupOrphans() {
        val managed = runCatching { backend.listManaged(poolId) }.getOrElse {
            log.warn("cannot list managed sandboxes: {}", it.message)
            return
        }
        managed.filter { !running.containsKey(it.handle.jobId) }.forEach { orphan ->
            log.warn("deleting orphaned sandbox {} (job {})", orphan.handle.name, orphan.handle.jobId)
            runCatching { backend.delete(orphan.handle) }
            report(orphan.handle.jobId, JobCompletion(AgentJobStatus.LOST, reason = "controller-restarted"))
        }
    }
}
