package pl.mos.backoffice.shared.platform

import java.io.InputStream
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Durable work queue (ADR 0023, docs/architecture/09-scale-and-operations.md)
// ------------------------------------------------------------------------------------------------

/**
 * A unit of background work. `dedupeKey` guarantees at most one live (READY or LEASED) item per key,
 * e.g. `RUN_ADVANCE:<runId>`: enqueueing again while one is live merges `payload` into it (resume signals
 * must be idempotent and carried in the domain state, not only in the payload).
 */
data class WorkRequest(
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val dedupeKey: String? = null,
    val runAfter: Instant? = null,
    val priority: Int = 0,
    val maxAttempts: Int = 10,
)

data class WorkItem(
    val id: UUID,
    val kind: String,
    val payload: Map<String, Any?>,
    val attempt: Int,
    val dedupeKey: String?,
)

sealed interface WorkOutcome {
    data object Done : WorkOutcome
    /** Try again later (transient failure or "not yet"), counts as an attempt. */
    data class Retry(val after: Duration, val reason: String) : WorkOutcome
    /** Give up; the item becomes DEAD and is visible in operations. */
    data class Dead(val reason: String) : WorkOutcome
}

/** Outbound port: enqueue work. Implementations write in the caller's transaction (outbox semantics). */
fun interface WorkQueue {
    fun enqueue(request: WorkRequest)
}

/**
 * Adapter-side contract: one handler per `kind`, discovered as beans by the worker role. Handlers run on a
 * leased item; a crash or scale-in lets another worker pick the item up again, so handlers must be
 * idempotent.
 */
interface WorkHandler {
    val kind: String
    fun handle(item: WorkItem): WorkOutcome
}

object WorkKinds {
    const val RUN_ADVANCE = "RUN_ADVANCE"
    const val BENCHMARK_ATTEMPT = "BENCHMARK_ATTEMPT"
    const val CI_EXECUTION_COMPLETE = "CI_EXECUTION_COMPLETE"
    const val RETENTION = "RETENTION"

    /** One poll of a project's Jira intake watcher (ADR 0033), dedupe key `JIRA_INTAKE_POLL:<projectId>`. */
    const val JIRA_INTAKE_POLL = "JIRA_INTAKE_POLL"

    /** One poll of a personal intake rule (ADR 0038), dedupe key `PERSONAL_INTAKE_POLL:<ruleId>`. */
    const val PERSONAL_INTAKE_POLL = "PERSONAL_INTAKE_POLL"
}

// ------------------------------------------------------------------------------------------------
// Object storage
// ------------------------------------------------------------------------------------------------

data class BlobRef(val key: String, val sizeBytes: Long, val contentType: String, val sha256: String?)

data class PresignedUrl(val url: String, val method: String, val headers: Map<String, String>, val expiresAt: Instant)

/**
 * Outbound port for large content (artefacts > 64 kB, diffs, runner logs, uploads). Keys are
 * slash-separated paths such as `runs/<runId>/artifacts/<artifactId>`.
 */
interface BlobStore {
    fun put(key: String, contentType: String, content: ByteArray): BlobRef
    fun put(key: String, contentType: String, content: InputStream, sizeBytes: Long): BlobRef
    fun open(key: String): InputStream?
    fun readText(key: String, maxBytes: Int = 16 * 1024 * 1024): String?
    fun presignPut(key: String, contentType: String, sizeBytes: Long, ttl: Duration): PresignedUrl
    fun presignGet(key: String, ttl: Duration): PresignedUrl
    fun delete(key: String)
    fun deletePrefix(prefix: String): Int
}

// ------------------------------------------------------------------------------------------------
// Agent job credentials (ADR 0024) - issued by agent-runner, verified by the LLM proxy and callbacks
// ------------------------------------------------------------------------------------------------

/**
 * Which calls a `bo_job_` token may make (ADR 0029). The runner entrypoint holds the [FULL] job token
 * (callbacks: events, result, uploads) and hands the coding agent a [GATEWAY] token that only reaches the
 * LLM proxy, so a prompt-injected agent cannot post or forge the job's result.
 */
enum class JobTokenScope { FULL, GATEWAY }

/** What a `bo_job_` token allows. */
data class JobCredential(
    val jobId: UUID,
    val projectId: UUID?,
    val runId: UUID?,
    val nodeId: String?,
    val benchmarkId: UUID?,
    val attemptId: UUID?,
    /** `provider/modelId` labels the job may call through the LLM proxy. */
    val modelAllowlist: Set<String>,
    val maxCostUsd: BigDecimal?,
    val maxTokens: Long?,
    val expiresAt: Instant,
    /** Cap on metered LLM proxy calls of the job (runaway or hijacked agents); null = unlimited. */
    val maxRequests: Long? = null,
    val scope: JobTokenScope = JobTokenScope.FULL,
    /** ADR 0045: `provider/modelId` label -> provider connection key, for models served by a named connection. */
    val modelConnections: Map<String, String> = emptyMap(),
)

fun interface JobTokenVerifier {
    /** Null when the token is unknown, expired or the job is no longer active. */
    fun verify(token: String): JobCredential?
}

// ------------------------------------------------------------------------------------------------
// Signing (runner assignments, provenance attestations; ADR 0024/0025)
// ------------------------------------------------------------------------------------------------

data class DsseSignature(val keyId: String, val signature: String)

data class DsseEnvelope(val payloadType: String, val payloadBase64: String, val signatures: List<DsseSignature>)

/** Ed25519 signing with published, rotatable keys. */
interface EnvelopeSigner {
    /** DSSE: signs PAE(payloadType, payload). */
    fun signEnvelope(payloadType: String, payload: ByteArray): DsseEnvelope

    /** Detached signature over arbitrary bytes (runner assignments); returns base64 and the key id. */
    fun sign(bytes: ByteArray): DsseSignature

    fun verify(envelope: DsseEnvelope): Boolean
    fun verify(bytes: ByteArray, signature: DsseSignature): Boolean

    /** JWK set (current and retired public keys). */
    fun publicJwks(): List<Map<String, Any?>>
}
