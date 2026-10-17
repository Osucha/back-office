package pl.mos.backoffice.runner.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.runner.JobCompletionJson
import pl.mos.backoffice.runner.RunnerEventJson
import pl.mos.backoffice.runner.RunnerResultJson
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.application.port.`in`.JobHeartbeatInput
import pl.mos.backoffice.runner.application.port.`in`.RunnerFleetProtocol
import pl.mos.backoffice.runner.application.port.`in`.RunnerRegistrationRequest
import pl.mos.backoffice.runner.application.port.`in`.SandboxCallbacks
import pl.mos.backoffice.runner.application.port.`in`.SandboxEvent
import pl.mos.backoffice.runner.application.port.`in`.UploadRequestInput
import pl.mos.backoffice.runner.application.port.out.LeaseSignal
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration
import java.util.UUID

/**
 * Runner protocol v2 (`/api/v1/runner/v2`, openapi tag `runner-v2`), called by runner-controllers with their
 * `bo_rt_` token (checked by [RunnerTokenFilter]) - except `register` (registration token in the body) and
 * `uploads` (the sandbox's job token). No person is behind these calls: the use cases run as the system
 * principal and are scoped to the authenticated runner's pool and leases.
 */
@RestController
@RequestMapping("/api/v1/runner/v2")
class RunnerProtocolController(
    private val protocol: RunnerFleetProtocol,
    private val callbacks: SandboxCallbacks,
    private val signer: AssignmentSigner,
    private val signal: LeaseSignal,
    private val gate: AuthorizationGate,
    private val clock: Clock,
    private val longPoll: LongPollSettings,
) {

    /** Bounds of the lease long poll (`backoffice.runner.max-lease-wait`, `lease-poll-interval`). */
    data class LongPollSettings(val maxWait: Duration, val pollInterval: Duration)

    @PostMapping("/register")
    fun register(@RequestBody body: RunnerRegistrationJson): ResponseEntity<RunnerRegisteredJson> {
        val registered = gate.asSystem(REASON) {
            protocol.register(
                RunnerRegistrationRequest(
                    body.registrationToken, body.name, body.version, body.cluster,
                    body.capabilities.backends, body.capabilities.runtimeClasses, body.capabilities.maxConcurrent,
                ),
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(RunnerRegisteredJson(registered.runnerId, registered.runnerToken, registered.poolId))
    }

    @GetMapping("/config")
    fun config(request: HttpServletRequest): RunnerConfigJson {
        val config = gate.asSystem(REASON) { protocol.config(RunnerTokenFilter.runner(request)) }
        return RunnerConfigJson(config.poolId, SandboxPolicyJson.of(config.ceiling), config.allowedImages, config.warmPoolSize, config.gateway.toJson(), config.signingKeys)
    }

    /** Long poll: returns as soon as something was leased, or empty after `waitSeconds` (max 30). */
    @PostMapping("/jobs/lease")
    fun lease(request: HttpServletRequest, @RequestBody(required = false) body: LeaseRequestJson?): List<AgentJobAssignmentJson> {
        val runner = RunnerTokenFilter.runner(request)
        val wait = Duration.ofSeconds((body?.waitSeconds ?: DEFAULT_WAIT_SECONDS).toLong().coerceAtLeast(0)).coerceAtMost(longPoll.maxWait)
        val deadline = clock.now().plus(wait)
        while (true) {
            val leased = gate.asSystem(REASON) { protocol.lease(runner, body?.maxJobs ?: 1, body?.freeSlots) }
            if (leased.isNotEmpty()) return leased.map(signer::assignment)
            val remaining = Duration.between(clock.now(), deadline)
            if (remaining.isNegative || remaining.isZero) return emptyList()
            signal.await(runner.poolId, minOf(remaining, longPoll.pollInterval).toMillis())
        }
    }

    @PostMapping("/jobs/{jobId}/heartbeat")
    fun heartbeat(request: HttpServletRequest, @PathVariable jobId: UUID, @RequestBody body: JobHeartbeatJson): JobHeartbeatResponseJson {
        val phase = when (body.phase?.uppercase()) {
            null, "STARTING" -> AgentJobStatus.STARTING
            "RUNNING" -> AgentJobStatus.RUNNING
            else -> throw ValidationException("phase must be STARTING or RUNNING", "phase")
        }
        val ack = gate.asSystem(REASON) {
            protocol.heartbeat(RunnerTokenFilter.runner(request), RunnerJobId(jobId), JobHeartbeatInput(phase, body.sandboxName, body.node, body.cpuMillis, body.memoryBytes))
        }
        return JobHeartbeatResponseJson(ack.leaseExpiresAt, ack.cancelRequested)
    }

    @PostMapping("/jobs/{jobId}/complete")
    fun complete(request: HttpServletRequest, @PathVariable jobId: UUID, @RequestBody body: JobCompletionJson): ResponseEntity<Void> {
        val report = body.toReport()
        gate.asSystem(REASON) { protocol.complete(RunnerTokenFilter.runner(request), RunnerJobId(jobId), report) }
        return ResponseEntity.accepted().build()
    }

    /** Presigned object-storage URL; called from the sandbox with its job token. */
    @PostMapping("/jobs/{jobId}/uploads")
    fun upload(request: HttpServletRequest, @PathVariable jobId: UUID, @RequestBody body: UploadRequestJson): UploadTargetJson {
        val target = gate.asSystem(REASON) {
            callbacks.createUpload(RunnerTokenFilter.credential(request), RunnerJobId(jobId), UploadRequestInput(body.name, body.contentType, body.sizeBytes, body.sha256))
        }
        return UploadTargetJson(target.url, target.method, target.headers, target.blobKey, target.expiresAt)
    }

    /** ADR 0044: the run workspaces this runner holds; the answer names the ones it deletes. */
    @PostMapping("/workspaces/sync")
    fun syncWorkspaces(request: HttpServletRequest, @RequestBody(required = false) body: WorkspaceSyncJson?): WorkspaceSyncResultJson {
        val release = gate.asSystem(REASON) { protocol.syncWorkspaces(RunnerTokenFilter.runner(request), body?.held.orEmpty()) }
        return WorkspaceSyncResultJson(release)
    }

    /** Queue depth for autoscaling (KEDA metrics-api scaler). */
    @GetMapping("/pools/{poolId}/queue")
    fun queue(request: HttpServletRequest, @PathVariable poolId: String): QueueDepthJson {
        val depth = gate.asSystem(REASON) { protocol.queueDepth(RunnerTokenFilter.runner(request), poolId) }
        return QueueDepthJson(depth.queued, depth.running)
    }

    companion object {
        const val REASON = "runner protocol v2"
        const val DEFAULT_WAIT_SECONDS = 25
    }
}

/**
 * Sandbox callbacks (`/api/v1/internal/runner/jobs/{jobId}/events|result`, unchanged shapes), authenticated
 * with the job token by [RunnerTokenFilter]; the token must belong to the job in the path.
 */
@RestController
@RequestMapping("/api/v1/internal/runner/jobs/{jobId}")
class SandboxCallbackController(private val callbacks: SandboxCallbacks, private val gate: AuthorizationGate, private val clock: Clock) {

    @PostMapping("/events")
    fun events(request: HttpServletRequest, @PathVariable jobId: UUID, @RequestBody body: List<RunnerEventJson>): ResponseEntity<Void> {
        gate.asSystem(REASON) {
            callbacks.appendEvents(RunnerTokenFilter.credential(request), RunnerJobId(jobId), body.map { SandboxEvent(it.type, it.message, it.at) })
        }
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/result")
    fun result(request: HttpServletRequest, @PathVariable jobId: UUID, @RequestBody body: RunnerResultJson): ResponseEntity<Void> {
        gate.asSystem(REASON) { callbacks.postResult(RunnerTokenFilter.credential(request), RunnerJobId(jobId), body.toRunnerResult(clock.now())) }
        return ResponseEntity.accepted().build()
    }

    /** The gateway-only token for the coding agent (ADR 0029); the runner keeps the job token to itself. */
    @PostMapping("/agent-token")
    fun agentToken(request: HttpServletRequest, @PathVariable jobId: UUID): ResponseEntity<Map<String, String>> {
        val token = gate.asSystem(REASON) { callbacks.issueAgentToken(RunnerTokenFilter.credential(request), RunnerJobId(jobId)) }
        return ResponseEntity.status(201).body(mapOf("token" to token, "scope" to "GATEWAY"))
    }

    companion object {
        const val REASON = "sandbox callback"
    }
}
