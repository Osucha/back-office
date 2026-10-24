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
import java.util.UUID

/** Use case: start a run of a workflow (manual / API); the run is handed to the engine and returned as `RUNNING`. */
interface StartRun {
    /** Starts without checking the declared run inputs: system starters (personal intake, MCP) send what they always sent. */
    fun start(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource = TriggerSource.MANUAL): WorkflowRun

    /**
     * The REST start (UI and API token, ADR 0053): like [start], but the workflow's declared run inputs are checked
     * first (required, at most 20 000 characters) and an `ISSUE_KEY` input becomes the run's issue key.
     */
    fun startChecked(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource = TriggerSource.MANUAL): WorkflowRun
}

/**
 * Use case: a platform mechanism starts a run on behalf of a person (ADR 0052): a schedule for whoever last saved the
 * workflow, a "Start workflow" step for whoever decided at its gate. The run is theirs (`startedBy`, their personal
 * model profiles); they must still hold `run.start` in the project.
 */
interface StartRunOnBehalf {
    fun startOnBehalf(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource, onBehalfOf: UUID?): WorkflowRun
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

/** ADR 0052: start the workflows whose SCHEDULE trigger is due. Returns how many runs were started. */
interface StartScheduledRuns {
    fun sweep(): Int
}

/** ADR 0052: resume runs waiting for the pipeline of their branch once it finished. Returns how many were resumed. */
interface WatchPipelines {
    fun sweep(): Int
}
