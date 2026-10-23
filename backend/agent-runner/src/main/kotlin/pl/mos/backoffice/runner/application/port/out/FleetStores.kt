package pl.mos.backoffice.runner.application.port.out

import pl.mos.backoffice.runner.application.port.`in`.AgentJobFilter
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.RegistrationToken
import pl.mos.backoffice.runner.domain.RunWorkspace
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant
import java.util.UUID

interface RunnerPoolStore {
    fun findAll(): List<RunnerPool>
    fun find(id: String): RunnerPool?
    fun insert(pool: RunnerPool)
    fun update(pool: RunnerPool)
    fun delete(id: String)
}

interface RunnerNodeStore {
    fun insert(node: RunnerNode, tokenHash: String)
    fun find(id: UUID): RunnerNode?
    fun findRunnerByTokenHash(hash: String): RunnerNode?
    fun list(poolId: String?): List<RunnerNode>
    fun touch(id: UUID, at: Instant)
    fun revoke(id: UUID, at: Instant)

    /** ADR 0050 step 3: stores what the runner reported with its lease. */
    fun report(id: UUID, harnesses: List<String>?, claudeLogin: Boolean?)

    /** ADR 0051: the controller's public key for sealed credentials. */
    fun setPublicKey(id: UUID, publicKey: String, at: Instant)
}

interface RegistrationTokenStore {
    fun insert(token: RegistrationToken, tokenHash: String, createdBy: String?)

    /** ADR 0046: makes the token with [tokenHash] usable for [poolId] until [expiresAt], also when it was used before. */
    fun rearm(id: UUID, poolId: String, tokenHash: String, now: Instant, expiresAt: Instant)

    /** Atomically marks an unused, unexpired token as used; null when there is none. */
    fun consume(tokenHash: String, now: Instant, runnerId: UUID): RegistrationToken?
}

/** Per-pool counters for the admin view. */
data class PoolCounts(val queued: Int, val active: Int)

interface AgentJobStore {
    fun find(id: RunnerJobId): AgentJob?
    fun findByLabel(label: String): AgentJob?

    /** Inserts the job; returns the existing job when another one with the same label exists (idempotent submit). */
    fun insertIfAbsent(job: AgentJob): AgentJob

    /** `SELECT ... FOR UPDATE` of one job (serialises protocol calls on it). */
    fun lock(id: RunnerJobId): AgentJob?

    /**
     * Queued, not cancelled jobs of a pool by priority and age, locked with `FOR UPDATE SKIP LOCKED`. Jobs of a run
     * workspace (ADR 0044) only while no other job uses it and it is on no runner or on [runnerId].
     */
    fun lockQueued(poolId: String, runnerId: UUID, limit: Int): List<AgentJob>

    /** Jobs whose lease expired, locked with `FOR UPDATE SKIP LOCKED`. */
    fun lockExpiredLeases(now: Instant, limit: Int): List<AgentJob>

    /** Queued jobs past their queue deadline, locked with `FOR UPDATE SKIP LOCKED`. */
    fun lockQueuedPastDeadline(now: Instant, limit: Int): List<AgentJob>

    /** Queued jobs without a pool, locked with `FOR UPDATE SKIP LOCKED`. */
    fun lockUnassigned(limit: Int): List<AgentJob>

    /** Writes every mutable column of [job]. */
    fun update(job: AgentJob)

    /** Sets (or with null clears) the hash of the job's current `bo_job_` token. */
    fun setTokenHash(id: RunnerJobId, hash: String?)

    /** ADR 0051: marks the job's tokens released for its current lease; false when they already were. */
    fun markCredentialsReleased(id: RunnerJobId, at: Instant): Boolean

    fun findJobByTokenHash(hash: String): AgentJob?

    /**
     * Sets the hash of the gateway-only agent token (ADR 0029) when the job has none yet; false when one was
     * already issued for the current lease. [setTokenHash] clears it.
     */
    fun setAgentTokenHash(id: RunnerJobId, hash: String): Boolean

    fun findJobByAgentTokenHash(hash: String): AgentJob?

    fun saveResult(id: RunnerJobId, result: RunnerResult?, blobKey: String?)
    fun loadResult(id: RunnerJobId): StoredResult?

    fun countActive(poolId: String): Int
    fun countActiveByRunner(runnerId: UUID): Int
    fun countsByPool(): Map<String, PoolCounts>
    fun countActiveByRunners(): Map<UUID, Int>
    fun activeOfRunner(runnerId: UUID): List<AgentJob>

    /** True when the pool has queued or leased jobs. */
    fun hasLiveJobs(poolId: String): Boolean

    fun page(filter: AgentJobFilter, page: Int, size: Int): PageOf<AgentJob>

    fun appendEvent(id: RunnerJobId, at: Instant, message: String, progress: Boolean)
    fun events(id: RunnerJobId, after: Instant?): List<RunnerLogLine>

    /** Transaction-scoped `pg_try_advisory_xact_lock`. */
    fun tryAdvisoryLock(key: Long): Boolean
}

/** A stored result: inline, or a pointer to the BlobStore. */
data class StoredResult(val inline: RunnerResult?, val blobKey: String?)

interface ProjectPolicyStore {
    fun findPolicy(projectId: UUID): SandboxPolicy?
    fun savePolicy(projectId: UUID, policy: SandboxPolicy, at: Instant, updatedBy: String?)
}

/**
 * Wakes long polls waiting for work in a pool on this replica right after a submit or re-queue. Other
 * replicas notice within `lease-poll-interval`.
 */
interface LeaseSignal {
    fun wake(poolId: String?)

    /** Waits up to [timeoutMillis] for a wake-up of [poolId]; returns early when one arrives. */
    fun await(poolId: String, timeoutMillis: Long)
}

/** ADR 0044: persistent workspaces of runs (`runner_workspace`). */
interface RunWorkspaceStore {
    /**
     * The workspace [name] of run [runId] for a new job: created when absent; a released one starts over (no pool, no
     * runner); a pending release is cancelled.
     */
    fun bind(name: String, runId: UUID, key: String, now: Instant): RunWorkspace

    /** Sets the pool; a different pool than before also drops the runner (the workspace starts over there). */
    fun assignPool(name: String, poolId: String)

    /** Claims the workspace for [jobId] on [runnerId] when no job uses it and no other runner holds it. */
    fun claim(name: String, runnerId: UUID, jobId: UUID, now: Instant): Boolean

    /** The job [jobId] no longer uses its workspace (finished or back in the queue). */
    fun releaseJob(jobId: UUID, now: Instant)

    /** Workspaces of run [runId] not yet released are released at [at]. */
    fun scheduleRelease(runId: UUID, at: Instant)

    /** Rows named [names] plus the unreleased rows of [runnerId], locked for the sync. */
    fun lockForSync(runnerId: UUID, names: Collection<String>): List<RunWorkspace>

    fun markReleased(names: Collection<String>, now: Instant)

    /** Moves [names] to [runnerId]: their runner is gone and this runner of the same pool holds the volumes. */
    fun adopt(names: Collection<String>, runnerId: UUID)

    /** Drops the runner of [names] (their volume is gone): the next job starts an empty workspace anywhere in the pool. */
    fun forget(names: Collection<String>)
}

/** Object storage for large results and sandbox uploads (the shared `BlobStore`; optional). */
interface ResultArchive {
    /** Serialised size of [result] in bytes. */
    fun sizeOf(result: RunnerResult): Int

    /** Writes the full result to object storage; null when no BlobStore is configured. */
    fun store(jobId: RunnerJobId, result: RunnerResult): String?

    fun read(blobKey: String): RunnerResult?

    /** Presigned PUT; null when no BlobStore is configured. */
    fun presignUpload(key: String, contentType: String, sizeBytes: Long, ttl: java.time.Duration): pl.mos.backoffice.shared.platform.PresignedUrl?
}

/** Runs a block in one database transaction (the continuation enqueue shares it: outbox semantics). */
interface Transactions {
    fun <T> inTransaction(block: () -> T): T
}
