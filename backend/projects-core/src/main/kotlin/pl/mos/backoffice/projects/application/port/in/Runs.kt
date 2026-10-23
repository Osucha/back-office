package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant

/** Use case: start a run of a workflow (manual / API); the run is handed to the engine and returned as `RUNNING`. */
interface StartRun {
    fun start(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource = TriggerSource.MANUAL): WorkflowRun
}

/** Use case: cancel an active run (RUNNING or WAITING); open approvals expire. */
interface CancelRun {
    fun cancel(runId: WorkflowRunId): WorkflowRun
}

/** Use case: retry a FAILED run from its failing node on the same engine thread. */
interface RetryRun {
    /** [comment]: what the retried step should do differently; it receives it as `{{feedback}}`, like a gate's revise comment. */
    fun retry(runId: WorkflowRunId, comment: String? = null): WorkflowRun
}

/**
 * Use case: deliver a CI signal (from `backoffice-ci signal` or the owner) to the run waiting on it.
 * Matching: explicit run id, else the newest WAITING run of the project whose `waitingOn.signal` equals
 * the signal and whose merge request branch equals `branch` (or the only candidate).
 */
interface SignalRun {
    fun signal(command: SignalCommand): SignalResult

    /** Owner-side signal for a specific run (`POST /runs/{id}/signals`). */
    fun signalRun(runId: WorkflowRunId, signal: String, status: CiSignalStatus, message: String?): WorkflowRun

    data class SignalCommand(
        val projectId: ProjectId,
        val signal: String,
        val status: CiSignalStatus,
        val runId: WorkflowRunId? = null,
        val branch: String? = null,
        val commitSha: String? = null,
        val message: String? = null,
        val context: CiContext = CiContext(),
    )

    data class SignalResult(val runId: WorkflowRunId?, val resumed: Boolean, val message: String)
}

/** Queries over runs, artefacts and logs. */
interface RunQueries {
    fun get(runId: WorkflowRunId): WorkflowRun
    fun list(filter: RunFilter, page: Int, size: Int): PageOf<WorkflowRun>
    fun artifacts(runId: WorkflowRunId): List<Artifact>
    fun artifact(runId: WorkflowRunId, artifactId: ArtifactId): Artifact
    fun logs(runId: WorkflowRunId, nodeId: String?, after: Instant?): List<RunLogLine>
}

/** Use case run by the scheduled sweep: expire waits whose `timeoutSeconds` elapsed. Returns how many runs were resumed. */
interface ExpireWaits {
    fun sweep(): Int
}

/** ADR 0052: resume runs waiting for the pipeline of their branch once it finished. Returns how many were resumed. */
interface WatchPipelines {
    fun sweep(): Int
}
