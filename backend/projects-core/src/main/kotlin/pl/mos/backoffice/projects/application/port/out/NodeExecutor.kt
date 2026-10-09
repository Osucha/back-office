package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.NodeExecution
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor

/** Artefact produced by a node, persisted by the progress recorder. */
data class NewArtifact(
    val name: String,
    val kind: ArtifactKind,
    val mediaType: String = "text/markdown",
    val content: String,
)

/**
 * Everything a node executor needs: the run (with artefacts), its project, the node and the routing
 * state of the graph (`issue`, `artifacts` name->id, `mr`, `review`, `feedback`, `confidence`, `decision`, `ci`, ...).
 */
data class NodeContext(
    val run: WorkflowRun,
    val project: Project,
    val node: WorkflowNode,
    val execution: NodeExecution,
    val state: Map<String, Any?>,
    /** Run-log sink for warnings (level WARN); null = [log]. */
    val warnSink: ((String) -> Unit)? = null,
    val log: (String) -> Unit = {},
) {
    val attempt: Int get() = execution.attempt

    /** Appends a WARN line to the run log (e.g. an over-privileged connection, ADR 0030). */
    fun warn(message: String) = (warnSink ?: log)(message)

    @Suppress("UNCHECKED_CAST")
    fun stateMap(key: String): Map<String, Any?>? = state[key] as? Map<String, Any?>

    fun stateList(key: String): List<Any?> = (state[key] as? List<Any?>) ?: emptyList()

    fun stateString(key: String): String? = state[key]?.toString()?.takeIf { it.isNotBlank() }
}

/** Outcome of one node execution: the output port taken plus everything to record on the run. */
data class NodeResult(
    val port: String?,
    val summary: String? = null,
    val confidence: Double? = null,
    val usage: TokenUsage = TokenUsage.ZERO,
    val cost: Money = Money.ZERO,
    val runnerJobId: RunnerJobId? = null,
    val artifacts: List<NewArtifact> = emptyList(),
    val mergeRequest: MergeRequestSnapshot? = null,
    val review: ReviewReport? = null,
    /** Set by END nodes: the run outcome. */
    val outcome: RunOutcome? = null,
    /** Extra graph-state updates (e.g. `issue`, `mr`, `review`, `branch`). Values must be JSON-like. */
    val stateUpdates: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    /**
     * Set when the node submitted a runner job and waits for it without blocking (ADR 0023/0024): the engine
     * interrupts the graph; the job's continuation (`RUN_ADVANCE`) resumes it and [NodeExecutor.resume] maps
     * the result.
     */
    val awaitingJob: RunnerJobId? = null,
    /**
     * Set when the node prepared an external write that needs a person's confirmation first (ADR 0033): the engine
     * pauses at `<nodeId>::confirm`, opens a write confirmation with exactly this content, and after approval calls
     * [NodeExecutor.commit] with it (no re-rendering).
     */
    val pendingWrite: PendingWrite? = null,
    /** Who confirms [pendingWrite] (node config `approvers`, default owners and maintainers). */
    val writeApprovers: GateApprovers? = null,
    /** Per-repository result of a runner step (ADR 0034), recorded on the node execution. */
    val repositories: List<RepositoryResult> = emptyList(),
) {
    val failed: Boolean get() = error != null

    companion object {
        fun success(port: String = WorkflowNode.PORT_SUCCESS, summary: String? = null) = NodeResult(port = port, summary = summary)

        fun confirming(write: PendingWrite, approvers: GateApprovers) =
            NodeResult(port = null, summary = "waiting for confirmation: ${write.describe()}", pendingWrite = write, writeApprovers = approvers)

        fun awaiting(jobId: RunnerJobId) = NodeResult(port = null, runnerJobId = jobId, awaitingJob = jobId)

        fun failure(error: String, port: String = WorkflowNode.PORT_FAILURE, usage: TokenUsage = TokenUsage.ZERO, cost: Money = Money.ZERO, runnerJobId: RunnerJobId? = null) =
            NodeResult(port = port, error = error, usage = usage, cost = cost, runnerJobId = runnerJobId)
    }
}

/** Strategy executing one node kind. Implementations live in `application.service.nodes` and use ports only. */
interface NodeExecutor {
    val kind: NodeKind
    fun execute(context: NodeContext): NodeResult

    /** Completes a node whose [execute] returned [NodeResult.awaiting]: the runner job [jobId] has finished. */
    fun resume(context: NodeContext, jobId: RunnerJobId): NodeResult = NodeResult.failure("node kind $kind does not wait for runner jobs")

    /** Performs the confirmed external write [write] that [execute] returned with [NodeResult.confirming] (ADR 0033). */
    fun commit(context: NodeContext, write: PendingWrite): NodeResult = NodeResult.failure("node kind $kind does not make external writes")
}

/** Strategy executing one `TaskExecutor` (LLM in-process, SPEC_KIT/IMPLEMENT in the runner, Jira tasks). */
interface TaskExecutorStrategy {
    val executor: TaskExecutor
    fun execute(context: NodeContext, task: ResolvedTask): NodeResult

    /** Maps the result of the runner job [jobId] submitted by [execute] (runner-backed executors only). */
    fun resume(context: NodeContext, task: ResolvedTask, jobId: RunnerJobId): NodeResult =
        NodeResult.failure("task executor $executor does not wait for runner jobs")

    /** Writes exactly the approved [write] prepared by [execute] (write executors only, ADR 0033). */
    fun commit(context: NodeContext, task: ResolvedTask, write: PendingWrite): NodeResult =
        NodeResult.failure("task executor $executor does not make external writes")
}
