package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkPayload
import pl.mos.backoffice.shared.platform.WorkRequest

/**
 * Shape of a `RUN_ADVANCE` work item (ADR 0023): `{runId, resume: {kind, ...}}`, dedupe key
 * `RUN_ADVANCE:<runId>`, so a run has at most one live item and is advanced by exactly one worker at a time.
 * Runner continuations additionally carry `jobId` and `status` (added by the runner control plane).
 */
object RunAdvance {
    const val RUN_ID = "runId"
    const val RESUME = "resume"
    const val KIND = "kind"
    const val NODE_ID = "nodeId"
    const val DECISION = "decision"
    const val CI = "ci"
    const val FROM_NODE = "fromNodeId"
    const val JOB_ID = "jobId"

    const val START = "START"
    const val RESUME_DECISION = "DECISION"
    const val RESUME_CI = "CI"
    const val RETRY = "RETRY"
    const val RECOVER = "RECOVER"
    const val RUNNER_RESULT = "RUNNER_RESULT"

    fun dedupeKey(runId: WorkflowRunId): String = "${WorkKinds.RUN_ADVANCE}:$runId"

    fun request(runId: WorkflowRunId, resume: Map<String, Any?>, keepExisting: Boolean = false): WorkRequest {
        val payload = HashMap<String, Any?>()
        payload[RUN_ID] = runId.toString()
        payload[RESUME] = resume
        if (keepExisting) payload[WorkPayload.MERGE] = WorkPayload.KEEP_EXISTING
        return WorkRequest(WorkKinds.RUN_ADVANCE, payload, dedupeKey = dedupeKey(runId), maxAttempts = MAX_ATTEMPTS)
    }

    /** Continuation handed to `AgentRunner.submit`: the finished job resumes the waiting node. */
    fun runnerContinuation(runId: WorkflowRunId, nodeId: String): WorkRequest =
        request(runId, mapOf(KIND to RUNNER_RESULT, NODE_ID to nodeId))

    const val MAX_ATTEMPTS = 10
}
