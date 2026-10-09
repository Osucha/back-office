package pl.mos.backoffice.shared.provenance

import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

/*
 * Read port for provenance attestations (ADR 0025, docs/architecture/11-governance.md). Implemented by the
 * Projects context (`projects.adapter.out.provenance`), used by the Governance context. It is a system read:
 * implementations do not check permissions, the governance use cases do.
 */

/** One attempt of one node of a run, as recorded by the engine. */
data class ProvenanceNode(
    val executionId: UUID,
    val nodeId: String,
    val nodeName: String,
    /** `TASK`, `HUMAN_GATE`, `WAIT_FOR_CI`, ... */
    val kind: String,
    val templateKey: String?,
    val attempt: Int,
    /** `SUCCEEDED`, `FAILED`, `SKIPPED`, `RUNNING`, `WAITING`. */
    val status: String,
    val port: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val runnerJobId: UUID?,
    val usage: TokenUsage,
    val cost: Money,
    val error: String?,
    val artifactIds: List<UUID>,
    /** What a runner step did per repository (ADR 0034); empty for other nodes and for runs recorded before 0034. */
    val repositories: List<ProvenanceRepositoryResult> = emptyList(),
)

/** One repository of a runner step (ADR 0034): [pushed] commits become attestation subjects `git+<url>@<sha>`. */
data class ProvenanceRepositoryResult(
    val key: String,
    val url: String?,
    val access: String,
    val branch: String?,
    val commitSha: String?,
    val mergeRequestUrl: String?,
    val pushed: Boolean,
)

/** A repository of the project at attestation time (ADR 0034). */
data class ProvenanceRepository(val key: String, val url: String, val purpose: String, val primary: Boolean)

/** A decided human gate on the run's path. */
data class ProvenanceGateDecision(
    val approvalId: UUID,
    val nodeId: String,
    val option: String,
    val comment: String?,
    val checkedItems: List<String>,
    val decidedBy: Actor?,
    val decidedAt: Instant,
)

/** Artefact metadata with the SHA-256 (hex) of its content; the content itself stays in the Projects context. */
data class ProvenanceArtifact(
    val id: UUID,
    val nodeId: String,
    val name: String,
    val kind: String,
    val mediaType: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

data class RunProvenance(
    val runId: UUID,
    val projectId: UUID,
    val projectKey: String,
    /** URL of the project's primary repository (null without one, ADR 0034). */
    val repositoryUrl: String?,
    val workflowId: UUID,
    val workflowName: String,
    val workflowVersion: Int,
    val issueKey: String?,
    /** `SUCCEEDED`, `FAILED`, `CANCELLED` for finished runs. */
    val status: String,
    val outcome: String?,
    val terminal: Boolean,
    val startedBy: Actor?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val cost: Money,
    val mergeRequestUrl: String?,
    val branch: String?,
    val nodes: List<ProvenanceNode>,
    val decisions: List<ProvenanceGateDecision>,
    val artifacts: List<ProvenanceArtifact>,
    /** All repositories of the project (ADR 0034). */
    val repositories: List<ProvenanceRepository> = emptyList(),
)

fun interface RunProvenanceSource {
    /** Null when the run does not exist. */
    fun find(runId: UUID): RunProvenance?
}
