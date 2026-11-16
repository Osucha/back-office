package pl.mos.backoffice.runner.application.port.`in`

import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.SortSpec
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

/** `sort` orders by one of [SORT_FIELDS]; null keeps newest queued first. */
data class AgentJobFilter(val poolId: String? = null, val status: AgentJobStatus? = null, val projectId: UUID? = null, val sort: SortSpec? = null) {
    companion object {
        val SORT_FIELDS = listOf("status", "label", "model", "pool", "duration", "cost", "queuedAt")
    }
}

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
