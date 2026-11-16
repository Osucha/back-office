package pl.mos.backoffice.runner.application.port.`in`.system

import pl.mos.backoffice.runner.application.port.out.SealedCredential
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.JobCompletionReport
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.SandboxPolicy
import java.time.Instant
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Runner protocol v2 (runner-controllers, authenticated by `bo_rrt_` / `bo_rt_` tokens) - system operations
// ------------------------------------------------------------------------------------------------

data class RunnerRegistrationRequest(
    val registrationToken: String,
    val name: String,
    val version: String,
    val cluster: String? = null,
    val backends: List<String> = emptyList(),
    val runtimeClasses: List<String> = emptyList(),
    val maxConcurrent: Int? = null,
)

data class RunnerRegistration(val runnerId: UUID, val runnerToken: String, val poolId: String)

data class GatewayUrls(val anthropicBaseUrl: String, val openaiBaseUrl: String, val apiBaseUrl: String)

data class RunnerConfigView(
    val poolId: String,
    val ceiling: SandboxPolicy,
    val allowedImages: List<String>,
    val warmPoolSize: Int,
    val gateway: GatewayUrls,
    val signingKeys: List<Map<String, Any?>>,
)

/** One leased job, handed to the controller exactly once (the job token is not stored in plaintext). */
data class LeasedJob(
    val jobId: RunnerJobId,
    val leaseExpiresAt: Instant,
    val jobToken: String,
    val spec: RunnerJobSpec,
    val policy: SandboxPolicy,
    val gateway: GatewayUrls,
    /** ADR 0040: LOCAL_LOGIN = Claude Code uses the login of the personal pool's owner, not the gateway. */
    val llmCredentials: LlmCredentials = LlmCredentials.GATEWAY,
)

data class JobHeartbeatInput(
    val phase: AgentJobStatus,
    val sandboxName: String? = null,
    val node: String? = null,
    val cpuMillis: Long? = null,
    val memoryBytes: Long? = null,
)

/** ADR 0050 step 3: what a runner reports with a lease; null fields = not reported. */
data class RunnerReport(val harnesses: List<String>?, val claudeLogin: Boolean?)

data class JobHeartbeatAck(val leaseExpiresAt: Instant, val cancelRequested: Boolean)

data class QueueDepth(val queued: Int, val running: Int)

interface RunnerFleetProtocol {
    /** Consumes a registration token (401 when unknown, used or expired). */
    fun register(request: RunnerRegistrationRequest): RunnerRegistration

    /** The runner behind a `bo_rt_` token (updates `lastSeenAt`), or null. */
    fun authenticate(runnerToken: String): RunnerNode?

    fun config(runner: RunnerNode): RunnerConfigView

    /** Leases up to [maxJobs] queued jobs of the runner's pool without waiting (the web adapter long-polls). */
    fun lease(runner: RunnerNode, maxJobs: Int, freeSlots: Int?, report: RunnerReport? = null): List<LeasedJob>

    fun heartbeat(runner: RunnerNode, jobId: RunnerJobId, heartbeat: JobHeartbeatInput): JobHeartbeatAck

    fun complete(runner: RunnerNode, jobId: RunnerJobId, completion: JobCompletionReport)

    fun queueDepth(runner: RunnerNode, poolId: String): QueueDepth

    /**
     * ADR 0044: [held] = run workspaces (volumes / PVCs) the runner holds. Returns the ones it deletes: released, due,
     * unknown or now held elsewhere. Rows of this runner whose volume is gone are forgotten (they start over).
     */
    fun syncWorkspaces(runner: RunnerNode, held: List<String>): List<String>

    /** ADR 0051: registers (or replaces) the controller's X25519 public key for sealed credentials. */
    fun setPublicKey(runner: RunnerNode, publicKey: String)

    /**
     * ADR 0051: the project repository tokens named in the job's signed spec, sealed to the runner's key; once per
     * lease, only to the runner holding the lease while it is valid. Empty when the spec names none.
     */
    fun releaseCredentials(runner: RunnerNode, jobId: RunnerJobId): CredentialRelease
}

/** ADR 0051: sealed tokens of one job; [expiresAt] is part of every token's associated data. */
data class CredentialRelease(val expiresAt: Instant, val credentials: List<ReleasedCredential>)

/** ADR 0064: [connector] = the connector key of a `CONNECTOR` secret, else null. */
data class ReleasedCredential(val ref: String, val repository: String, val capability: String, val sealed: SealedCredential, val connector: String? = null)

// ------------------------------------------------------------------------------------------------
// Sandbox callbacks (the agent inside the sandbox, authenticated by its `bo_job_` token) - system operations
// ------------------------------------------------------------------------------------------------

data class SandboxEvent(val type: String, val message: String, val at: Instant?)

data class UploadRequestInput(val name: String, val contentType: String, val sizeBytes: Long, val sha256: String? = null)

data class UploadTargetView(val url: String, val method: String, val headers: Map<String, String>, val blobKey: String, val expiresAt: Instant)

interface SandboxCallbacks {
    fun appendEvents(credential: JobCredential, jobId: RunnerJobId, events: List<SandboxEvent>)

    /** 409 when the job already has a result. */
    fun postResult(credential: JobCredential, jobId: RunnerJobId, result: RunnerResult)

    fun createUpload(credential: JobCredential, jobId: RunnerJobId, request: UploadRequestInput): UploadTargetView

    /**
     * Issues the gateway-only token the runner hands to the coding agent (ADR 0029): it reaches the LLM proxy
     * but none of these callbacks. Needs the full job token; once per lease (409 afterwards).
     */
    fun issueAgentToken(credential: JobCredential, jobId: RunnerJobId): String
}

// ------------------------------------------------------------------------------------------------
// Maintenance (scheduler)
// ------------------------------------------------------------------------------------------------

data class LeaseScanOutcome(val ran: Boolean, val requeued: Int = 0, val lost: Int = 0, val timedOut: Int = 0, val routed: Int = 0)

interface MaintainAgentJobs {
    /**
     * Expired leases (LEASED without a heartbeat: re-queued; STARTING/RUNNING: LOST, re-queued once when
     * nothing can have been pushed), queue timeouts, and routing of jobs that had no matching pool. Guarded by
     * a PostgreSQL advisory lock so one replica scans at a time.
     */
    fun scan(): LeaseScanOutcome
}

// ------------------------------------------------------------------------------------------------
// Startup (ADR 0046)
// ------------------------------------------------------------------------------------------------

/**
 * Laptop stacks only: the shared pool [poolId] (created when missing) and the configured registration token, usable
 * again after every start, so the stack's bundled runner-controller registers without a manual step.
 */
interface BootstrapLocalFleet {
    fun bootstrapLocalFleet(poolId: String, registrationToken: String, allowedImages: List<String>, maxConcurrentJobs: Int)
}
