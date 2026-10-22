package pl.mos.backoffice.runner.domain

import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Lifecycle of an agent job (docs/architecture/10-agent-execution.md, openapi `AgentJobStatus`). */
enum class AgentJobStatus {
    QUEUED, LEASED, STARTING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, LOST;

    val terminal: Boolean get() = this in TERMINAL
    val active: Boolean get() = this in ACTIVE

    /** The status as seen by callers of the shared `AgentRunner` port. */
    fun toRunnerStatus(): RunnerJobStatus = when (this) {
        QUEUED, LEASED, STARTING -> RunnerJobStatus.CREATED
        RUNNING -> RunnerJobStatus.RUNNING
        SUCCEEDED -> RunnerJobStatus.SUCCEEDED
        FAILED, LOST -> RunnerJobStatus.FAILED
        TIMED_OUT -> RunnerJobStatus.TIMED_OUT
        CANCELLED -> RunnerJobStatus.CANCELLED
    }

    companion object {
        val TERMINAL = setOf(SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, LOST)

        /** Leased by a runner: the lease must be kept alive with heartbeats and the job token is valid. */
        val ACTIVE = setOf(LEASED, STARTING, RUNNING)
    }
}

/** Permissions of the runner fleet (ADR 0017, docs/architecture/08-identity-and-access.md). */
object RunnerPermissions {
    const val MODULE = "runner"

    /** See runner pools, runners and agent jobs of every project. */
    const val FLEET_VIEW = "fleet.view"

    /** Create/edit/delete pools, issue registration tokens, revoke runners, cancel agent jobs. */
    const val FLEET_MANAGE = "fleet.manage"

    /** Your own personal runner pool (ADR 0040): connect your computer, revoke it, choose its model credentials. */
    const val FLEET_PERSONAL = "fleet.personal"

    /** Project sandbox policy (read with `project.view`, change with `project.manage`). */
    const val PROJECT_VIEW = "project.view"
    const val PROJECT_MANAGE = "project.manage"

    val definitions: List<PermissionDefinition> = listOf(
        PermissionDefinition(FLEET_VIEW, PermissionScope.PLATFORM, MODULE, "See runner pools, runners and agent jobs"),
        PermissionDefinition(FLEET_MANAGE, PermissionScope.PLATFORM, MODULE, "Manage runner pools, registration tokens, runners and agent jobs"),
        PermissionDefinition(FLEET_PERSONAL, PermissionScope.PLATFORM, MODULE, "Connect your own computer as a personal runner for your private projects"),
    )
}

/**
 * How coding agents of a pool reach the models (ADR 0040). `GATEWAY`: through the Back Office LLM gateway with the
 * job's agent token (metered, budgets apply). `LOCAL_LOGIN`: personal pools only; Claude Code uses the login of the
 * runner's owner (`CLAUDE_CODE_OAUTH_TOKEN` on their runner-controller) and talks to Anthropic directly; nothing
 * is metered by Back Office. Other harnesses (OpenCode) always use the gateway.
 */
enum class LlmCredentials { GATEWAY, LOCAL_LOGIN }

/**
 * Opaque credentials of the fleet: `bo_rrt_` (single-use registration, 24 h), `bo_rt_` (runner-controller),
 * `bo_job_` (one sandbox, one job). 256 random bits, base64url; only the SHA-256 hex digest is stored.
 */
object FleetTokens {
    const val REGISTRATION_PREFIX = "bo_rrt_"
    const val RUNNER_PREFIX = "bo_rt_"
    const val JOB_PREFIX = "bo_job_"

    private val random = SecureRandom()

    data class Generated(val plaintext: String, val hash: String)

    fun generate(prefix: String): Generated {
        val bytes = ByteArray(32).also(random::nextBytes)
        val plaintext = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return Generated(plaintext, hash(plaintext))
    }

    fun hash(plaintext: String): String =
        MessageDigest.getInstance("SHA-256").digest(plaintext.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Bearer value of an `Authorization` header, or null. */
    fun bearer(header: String?): String? =
        header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
}

data class RunnerPool(
    val id: String,
    val name: String,
    val description: String?,
    val labels: Map<String, String>,
    val ceiling: SandboxPolicy,
    val allowedImages: List<String>,
    val warmPoolSize: Int,
    val maxConcurrentJobs: Int,
    /** Empty = any project may use the pool. */
    val projectIds: Set<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** ADR 0040: the user of a personal pool (it runs only jobs routed to that user's personal runner); null = shared pool. */
    val ownerUserId: UUID? = null,
    val llmCredentials: LlmCredentials = LlmCredentials.GATEWAY,
) {
    val personal: Boolean get() = ownerUserId != null

    fun acceptsProject(projectId: UUID?): Boolean = projectIds.isEmpty() || (projectId != null && projectId in projectIds)

    fun matches(selector: Map<String, String>): Boolean = selector.all { (k, v) -> labels[k] == v }

    companion object {
        val ID = Regex("^[a-z][a-z0-9-]{1,39}$")

        /** Hosts a LOCAL_LOGIN sandbox must reach besides the API: Claude Code's own endpoint. */
        val LOCAL_LOGIN_HOSTS = listOf("api.anthropic.com")

        /** Id of the personal pool of [userId]: `personal-` + 24 hex digits of the user id. */
        fun personalId(userId: UUID): String = "personal-" + userId.toString().replace("-", "").take(24)
    }
}

data class RunnerNode(
    val id: UUID,
    val poolId: String,
    val name: String,
    val version: String,
    val cluster: String?,
    val backends: List<String>,
    val runtimeClasses: List<String>,
    val maxConcurrent: Int,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
    /** ADR 0050 step 3: coding agents reported with the last lease; null = not reported. */
    val harnesses: List<String>? = null,
    /** ADR 0050 step 3: whether a Claude login is set for personal-runner jobs; null = not reported. */
    val claudeLogin: Boolean? = null,
) {
    fun online(now: Instant, window: Duration): Boolean = revokedAt == null && !lastSeenAt.isBefore(now.minus(window))
}

data class RegistrationToken(
    val id: UUID,
    val poolId: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** What the controller reported when the sandbox ended (openapi `JobCompletion`). */
data class JobCompletionReport(
    val status: AgentJobStatus,
    val exitCode: Int? = null,
    val reason: String? = null,
    val imageDigest: String? = null,
    val egressLogDigest: String? = null,
    val egressDenied: List<String> = emptyList(),
    val durationMs: Long? = null,
)

/** A persisted agent job without its (possibly large) result. */
data class AgentJob(
    val id: RunnerJobId,
    val label: String,
    val poolId: String?,
    val runnerId: UUID?,
    val status: AgentJobStatus,
    val priority: Int,
    val spec: RunnerJobSpec,
    val requestedPolicy: SandboxPolicy?,
    /** Effective policy after resolution against the pool; null while no pool is assigned. */
    val policy: SandboxPolicy?,
    val policySources: Map<String, String>,
    val poolSelector: Map<String, String>,
    val continuation: WorkRequest?,
    val cancelRequested: Boolean,
    val leaseExpiresAt: Instant?,
    val leaseCount: Int,
    val lostCount: Int,
    val phase: String?,
    val sandboxName: String?,
    val node: String?,
    val queuedAt: Instant,
    val leasedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val queueDeadline: Instant?,
    val reason: String?,
    val completion: JobCompletionReport?,
    val hasResult: Boolean,
    val costUsd: BigDecimal?,
)
