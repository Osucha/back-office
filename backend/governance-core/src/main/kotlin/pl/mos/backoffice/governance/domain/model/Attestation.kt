package pl.mos.backoffice.governance.domain.model

import pl.mos.backoffice.shared.platform.DsseEnvelope
import java.time.Instant
import java.util.UUID

enum class AttestationKind { AGENT_JOB, RUN }

/** in-toto ResourceDescriptor subset used as a subject: a name and at least one digest. */
data class AttestationSubject(val name: String, val digest: Map<String, String>) {
    fun toMap(): Map<String, Any?> = mapOf("name" to name, "digest" to digest)
}

/**
 * A signed in-toto Statement v1 about one agent job or one finished run (ADR 0025). Immutable once written;
 * [subjectKey] (`RUN:<runId>` or `JOB:<runId>:<nodeExecutionId>`) makes creation idempotent.
 */
data class Attestation(
    val id: UUID,
    val runId: UUID,
    val projectId: UUID,
    val kind: AttestationKind,
    val nodeId: String?,
    val nodeExecutionId: UUID?,
    val jobId: UUID?,
    val subjectKey: String,
    val subjects: List<AttestationSubject>,
    val predicateType: String,
    val keyId: String,
    val envelope: DsseEnvelope,
    /** SHA-256 (hex) of the statement bytes (the DSSE payload). */
    val statementSha256: String,
    val createdAt: Instant,
) {
    companion object {
        fun runKey(runId: UUID) = "RUN:$runId"
        fun jobKey(runId: UUID, nodeExecutionId: UUID) = "JOB:$runId:$nodeExecutionId"
    }
}

/** Result of `POST /attestations/verify`. */
data class VerificationResult(
    val valid: Boolean,
    val keyId: String?,
    val statement: Map<String, Any?>?,
    val reason: String?,
)

object InToto {
    const val STATEMENT_TYPE = "https://in-toto.io/Statement/v1"
    const val PREDICATE_AGENT_RUN = "https://back-office.dev/attestations/agent-run/v1"
}

/**
 * What the execution plane knows about one agent job, beyond what the run records: the spec it ran with
 * (model and effort, prompt and skill digests, verify command, sandbox policy) and its result (commit, diff
 * digest, verification exit code, image and egress-log digests of runner v2). Every field is optional:
 * runner v1 does not report image or egress digests, and old jobs may be gone.
 */
data class JobEvidence(
    val jobId: UUID,
    val status: String? = null,
    val agent: String? = null,
    val modelRef: String? = null,
    val effort: String? = null,
    val promptSha256: String? = null,
    val constitutionSha256: String? = null,
    val skillSha256: Map<String, String> = emptyMap(),
    val verifyCommand: String? = null,
    val verificationExitCode: Int? = null,
    val commitSha: String? = null,
    val branch: String? = null,
    val diffSha256: String? = null,
    val mergeRequestUrl: String? = null,
    val policyDigest: String? = null,
    val isolation: String? = null,
    val image: String? = null,
    val imageDigest: String? = null,
    val allowedHosts: List<String> = emptyList(),
    val egressLogDigest: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)
