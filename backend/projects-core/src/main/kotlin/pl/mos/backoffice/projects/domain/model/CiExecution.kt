package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import java.time.Instant

enum class CiExecutionStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
enum class GateResult { PASS, FAIL, PENDING }
enum class CiSignalStatus { SUCCESS, FAIL }

/** A CI job's report about one signal (`backoffice-ci signal tests --status success`); stored on the run. */
data class CiSignal(
    val signal: String,
    val status: CiSignalStatus,
    val message: String? = null,
    val context: CiContext = CiContext(),
    val receivedAt: Instant,
) {
    fun toStateMap(): Map<String, Any?> = mapOf("signal" to signal, "status" to status.name, "message" to message)
}

/**
 * Aggregate root: one `backoffice-ci run <entrypoint>` call. Idempotent per
 * `(project, entrypoint, commitSha, mergeRequest.iid)` for 24 h; the CI job polls `gateResult`.
 */
data class CiExecution(
    val id: CiExecutionId,
    val projectId: ProjectId,
    val projectKey: String,
    val entrypoint: String,
    val mode: CiMode,
    val status: CiExecutionStatus = CiExecutionStatus.QUEUED,
    val context: CiContext,
    val variables: Map<String, String> = emptyMap(),
    val diffArtifactId: ArtifactId? = null,
    val changedFiles: List<String> = emptyList(),
    val runId: WorkflowRunId? = null,
    val outcome: RunOutcome? = null,
    val summary: String? = null,
    val report: ReviewReport? = null,
    val cost: Money = Money.ZERO,
    val error: String? = null,
    val createdAt: Instant,
    val finishedAt: Instant? = null,
    /** Project repository the CI job runs in, matched by URL (ADR 0034); null for CI clients that send no URL. */
    val repositoryKey: String? = null,
) {
    /** PASS when the run outcome is SUCCESS or the mode is ADVISORY; FAIL otherwise; PENDING while running. */
    val gateResult: GateResult
        get() = when (status) {
            CiExecutionStatus.SUCCEEDED, CiExecutionStatus.FAILED -> when {
                mode == CiMode.ADVISORY -> GateResult.PASS
                status == CiExecutionStatus.SUCCEEDED && outcome == RunOutcome.SUCCESS -> GateResult.PASS
                else -> GateResult.FAIL
            }
            else -> GateResult.PENDING
        }

    val finished: Boolean get() = status == CiExecutionStatus.SUCCEEDED || status == CiExecutionStatus.FAILED

    fun running(runId: WorkflowRunId, diffArtifactId: ArtifactId?): CiExecution {
        if (status != CiExecutionStatus.QUEUED) throw InvalidStateException("CI execution $id is $status, expected QUEUED")
        return copy(status = CiExecutionStatus.RUNNING, runId = runId, diffArtifactId = diffArtifactId)
    }

    /** Applies the finished run: SUCCEEDED when the run reached an END node, FAILED when it failed or was cancelled. */
    fun finishedWith(run: WorkflowRun, now: Instant): CiExecution {
        if (finished) return this
        val succeeded = run.status == RunStatus.SUCCEEDED
        return copy(
            status = if (succeeded) CiExecutionStatus.SUCCEEDED else CiExecutionStatus.FAILED,
            outcome = run.outcome ?: if (succeeded) RunOutcome.SUCCESS else RunOutcome.FAILURE,
            summary = run.review?.summary ?: run.failure?.message ?: run.nodes.lastOrNull { it.summary != null }?.summary,
            report = run.review,
            cost = run.cost,
            error = run.failure?.message ?: if (run.status == RunStatus.CANCELLED) "run cancelled" else null,
            finishedAt = now,
        )
    }

    fun failed(error: String, now: Instant): CiExecution {
        if (finished) return this
        return copy(status = CiExecutionStatus.FAILED, outcome = RunOutcome.FAILURE, error = error, finishedAt = now)
    }

    /** Idempotency key: same project, entrypoint, commit and merge request. */
    val idempotencyKey: String
        get() = idempotencyKey(projectId, entrypoint, context.commitSha, context.mergeRequest?.iid)

    companion object {
        fun idempotencyKey(projectId: ProjectId, entrypoint: String, commitSha: String?, mergeRequestIid: String?): String =
            "$projectId|$entrypoint|${commitSha ?: ""}|${mergeRequestIid ?: ""}"
    }
}
