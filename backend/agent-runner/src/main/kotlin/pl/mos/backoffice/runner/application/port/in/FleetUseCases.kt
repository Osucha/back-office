package pl.mos.backoffice.runner.application.port.`in`

import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.JobCompletionReport
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Fleet administration (people; `fleet.view` / `fleet.manage`)
// ------------------------------------------------------------------------------------------------

data class RunnerPoolInput(
    val id: String,
    val name: String,
    val description: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val ceiling: SandboxPolicy = SandboxPolicy.DEFAULT,
    val allowedImages: List<String> = emptyList(),
    val warmPoolSize: Int = 2,
    val maxConcurrentJobs: Int = 10,
    val projectIds: Set<UUID> = emptySet(),
)

data class RunnerPoolView(
    val input: RunnerPoolInput,
    val runners: Int,
    val onlineRunners: Int,
    val queued: Int,
    val running: Int,
    val createdAt: Instant,
    /** ADR 0040: the owner of a personal pool (managed by them under My account); null = shared pool. */
    val ownerUserId: UUID? = null,
    val llmCredentials: LlmCredentials = LlmCredentials.GATEWAY,
)

data class RunnerView(
    val id: UUID,
    val poolId: String,
    val name: String,
    val version: String,
    val backends: List<String>,
    val runtimeClasses: List<String>,
    val maxConcurrent: Int,
    val running: Int,
    val lastSeenAt: Instant,
    val online: Boolean,
    val cluster: String?,
    val registeredAt: Instant,
    val revokedAt: Instant?,
)

data class IssuedRegistrationToken(val token: String, val expiresAt: Instant, val helmValues: String)

interface ManageRunnerPools {
    fun list(): List<RunnerPoolView>
    fun get(poolId: String): RunnerPoolView
    fun create(input: RunnerPoolInput): RunnerPoolView
    fun update(poolId: String, input: RunnerPoolInput): RunnerPoolView

    /** 409 while the pool still has queued or leased jobs. */
    fun delete(poolId: String)

    /** Single-use `bo_rrt_` token (24 h), shown once, with a ready-to-paste values snippet for the runner chart. */
    fun issueRegistrationToken(poolId: String): IssuedRegistrationToken
}

// ------------------------------------------------------------------------------------------------
// Personal runner (ADR 0040; the signed-in user's own pool, `fleet.personal`)
// ------------------------------------------------------------------------------------------------

data class PersonalRunnerView(
    val poolId: String,
    val llmCredentials: LlmCredentials,
    val runners: List<RunnerView>,
    val queued: Int,
    val running: Int,
    val createdAt: Instant,
)

/** A single-use registration token for the personal pool and the command that starts a runner-controller with it. */
data class IssuedPersonalRegistration(val token: String, val expiresAt: Instant, val dockerCommand: String)

interface ManagePersonalRunner {
    /** The caller's personal runner, or null when they have not set one up. */
    fun mine(): PersonalRunnerView?

    /** Creates the caller's personal pool (idempotent) or changes its model credentials. */
    fun enable(llmCredentials: LlmCredentials): PersonalRunnerView

    /** Deletes the personal pool and its runners; 409 while it still has queued or running jobs. */
    fun disable()

    fun issueRegistrationToken(): IssuedPersonalRegistration

    /** Revokes one runner of the caller's personal pool; its jobs go back to the queue. */
    fun revokeRunner(runnerId: UUID)
}

interface ManageRunners {
    fun list(poolId: String?): List<RunnerView>

    /** Revokes the runner token; jobs it holds go back to the queue. */
    fun revoke(runnerId: UUID)
}

data class AgentJobFilter(val poolId: String? = null, val status: AgentJobStatus? = null, val projectId: UUID? = null)

data class AgentJobView(
    val id: RunnerJobId,
    val label: String,
    val poolId: String?,
    val runnerId: UUID?,
    val status: AgentJobStatus,
    val mode: String,
    val projectId: UUID?,
    val runId: UUID?,
    val nodeId: String?,
    val benchmarkId: UUID?,
    val model: ModelRef,
    val isolation: SandboxIsolation?,
    val policy: SandboxPolicy?,
    val policySources: Map<String, String>,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    /** Why the job is where it is, e.g. `no online runner in pool gpu`, `heartbeat-timeout`, `OOMKilled`. */
    val reason: String?,
    val cost: Money,
    val egressDenied: List<String>,
    val cancelRequested: Boolean,
    val leaseExpiresAt: Instant?,
    val attempts: Int,
    val priority: Int,
    /** Git rights of the job (ADR 0031): the runner-controller picks a credential of this level. */
    val gitAccess: GitAccess = GitAccess.NONE,
)

interface ManageAgentJobs {
    fun list(filter: AgentJobFilter, page: Int, size: Int): PageOf<AgentJobView>
    fun get(jobId: RunnerJobId): AgentJobView

    /** QUEUED jobs end CANCELLED at once; leased jobs get `cancelRequested` and end when the controller reports. */
    fun cancel(jobId: RunnerJobId)
}

data class SandboxPolicyView(
    val project: SandboxPolicy,
    val effective: SandboxPolicy,
    val ceiling: SandboxPolicy,
    val sources: Map<String, String>,
    val poolId: String?,
    val warnings: List<String>,
)

interface ManageProjectSandboxPolicy {
    fun get(projectId: UUID): SandboxPolicyView
    fun update(projectId: UUID, policy: SandboxPolicy): SandboxPolicyView
}

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

data class JobHeartbeatAck(val leaseExpiresAt: Instant, val cancelRequested: Boolean)

data class QueueDepth(val queued: Int, val running: Int)

interface RunnerFleetProtocol {
    /** Consumes a registration token (401 when unknown, used or expired). */
    fun register(request: RunnerRegistrationRequest): RunnerRegistration

    /** The runner behind a `bo_rt_` token (updates `lastSeenAt`), or null. */
    fun authenticate(runnerToken: String): RunnerNode?

    fun config(runner: RunnerNode): RunnerConfigView

    /** Leases up to [maxJobs] queued jobs of the runner's pool without waiting (the web adapter long-polls). */
    fun lease(runner: RunnerNode, maxJobs: Int, freeSlots: Int?): List<LeasedJob>

    fun heartbeat(runner: RunnerNode, jobId: RunnerJobId, heartbeat: JobHeartbeatInput): JobHeartbeatAck

    fun complete(runner: RunnerNode, jobId: RunnerJobId, completion: JobCompletionReport)

    fun queueDepth(runner: RunnerNode, poolId: String): QueueDepth

    /**
     * ADR 0044: [held] = run workspaces (volumes / PVCs) the runner holds. Returns the ones it deletes: released, due,
     * unknown or now held elsewhere. Rows of this runner whose volume is gone are forgotten (they start over).
     */
    fun syncWorkspaces(runner: RunnerNode, held: List<String>): List<String>
}

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
