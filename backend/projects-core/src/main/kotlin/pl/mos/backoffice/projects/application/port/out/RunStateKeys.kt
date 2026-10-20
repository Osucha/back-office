package pl.mos.backoffice.projects.application.port.out

/**
 * Keys of the routing state kept in the engine checkpoint (docs/architecture/03-pipelines-and-agents.md).
 * Shared by the node executors (core) and the LangGraph adapter so both sides agree on names.
 */
object RunStateKeys {
    const val RUN_ID = "runId"
    /** Node the graph enters from START (the START node, or the failing node on retry). */
    const val ENTRY_NODE = "entryNode"
    const val ISSUE = "issue"
    /** Map artefact name -> artefact id. */
    const val ARTIFACTS = "artifacts"
    const val MR = "mr"
    /** Map repository key -> latest result of a runner step in it (`branch`, `commitSha`, `mergeRequestUrl`, `pushed`; ADR 0034). */
    const val REPOSITORIES = "repositories"
    const val REVIEW = "review"
    const val BRANCH = "branch"
    const val CONFIDENCE = "confidence"
    /** `SUCCESS` / `FAILURE` of the previous node. */
    const val LAST_OUTCOME = "lastOutcome"
    /** Output port the last node left through; conditional edges route on it. */
    const val LAST_PORT = "lastPort"
    const val FAILED_NODE = "failedNode"
    const val LAST_ERROR = "lastError"
    /** Pending human decision delivered with `GraphInput.resume`; consumed by the gate node. */
    const val DECISION = "decision"
    const val LAST_DECISION = "lastDecision"
    /** Pending CI signal delivered with `GraphInput.resume`; consumed by the WAIT_FOR_CI node. */
    const val CI = "ci"
    const val LAST_CI = "lastCi"
    /** Appender channel of all revision comments of the run (history; decision expressions read it as `feedback`). */
    const val FEEDBACK = "feedback"
    /**
     * Revision comments given since the last task step ran (list of strings): a gate's `revise` adds its comment, the
     * next task step that completes consumes them (they move to [FEEDBACK_BY_NODE] under that step's node id).
     */
    const val PENDING_FEEDBACK = "pendingFeedback"
    /** Map node id -> revision comments that step already received on earlier attempts (oldest first). */
    const val FEEDBACK_BY_NODE = "feedbackByNode"
    /** `SUCCESS` / `FAILURE` set by an END node, or `FAILED` / `CANCELLED` set by the system nodes. */
    const val RUN_OUTCOME = "runOutcome"
    /** Map node id -> number of visits (loop guard). */
    const val LOOP_COUNTS = "loopCounts"
    /** `{nodeId, jobId}` of the runner job a node waits for (non-blocking runner nodes, ADR 0023). */
    const val PENDING_JOB = "pendingJob"
    /** `{nodeId, action, issueKey, comment, toStatus, ...}` of the external write a TASK node waits to have confirmed (ADR 0033). */
    const val PENDING_WRITE = "pendingWrite"

    const val OUTCOME_SUCCESS = "SUCCESS"
    const val OUTCOME_FAILURE = "FAILURE"
    const val RUN_FAILED = "FAILED"
    const val RUN_CANCELLED = "CANCELLED"

    /** Synthetic status of a CI signal injected by the timeout sweep. */
    const val CI_STATUS_TIMEOUT = "TIMEOUT"

    /** Revision comments not yet consumed by a task step ([PENDING_FEEDBACK]). */
    fun pendingFeedback(state: Map<String, Any?>): List<String> =
        (state[PENDING_FEEDBACK] as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) } ?: emptyList()

    /** Revision comments the step [nodeId] received on earlier attempts ([FEEDBACK_BY_NODE]), oldest first. */
    fun feedbackOf(state: Map<String, Any?>, nodeId: String): List<String> =
        ((state[FEEDBACK_BY_NODE] as? Map<*, *>)?.get(nodeId) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    /**
     * State updates that hand the pending feedback to the task step [nodeId] which just completed: the comments move
     * to its entry of [FEEDBACK_BY_NODE] (so a retry of the same step still sees them) and the pending list is
     * cleared. Empty when nothing is pending.
     */
    fun consumeFeedback(state: Map<String, Any?>, nodeId: String): Map<String, Any?> {
        val pending = pendingFeedback(state)
        if (pending.isEmpty()) return emptyMap()
        val byNode = LinkedHashMap<String, Any?>()
        (state[FEEDBACK_BY_NODE] as? Map<*, *>)?.forEach { (k, v) -> byNode[k.toString()] = v }
        byNode[nodeId] = feedbackOf(state, nodeId) + pending
        return mapOf(PENDING_FEEDBACK to null, FEEDBACK_BY_NODE to byNode)
    }
}
