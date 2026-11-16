package pl.mos.backoffice.projects.application.port.`in`.system

import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import java.util.UUID

/**
 * Use case: a platform mechanism starts a run on behalf of a person (ADR 0052): a schedule for whoever last saved the
 * workflow, a "Start workflow" step for whoever decided at its gate. The run is theirs (`startedBy`, their personal
 * model profiles); they must still hold `run.start` in the project.
 */
interface StartRunOnBehalf {
    fun startOnBehalf(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource, onBehalfOf: UUID?): WorkflowRun

    /**
     * Starts a run for an issue found by the Jira intake watcher (ADR 0033): trigger `JIRA`, `startedBy` the watcher
     * (a system actor), audited `RUN_START` like every start; [dedupeKey] goes into the audit details.
     */
    fun startForJira(workflowId: WorkflowDefinitionId, issueKey: String, dedupeKey: String): WorkflowRun
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

/** Internal use case: called when a CI-triggered run finishes to derive status, gate result and report. */
interface CompleteCiExecution {
    fun runFinished(run: WorkflowRun)
}
