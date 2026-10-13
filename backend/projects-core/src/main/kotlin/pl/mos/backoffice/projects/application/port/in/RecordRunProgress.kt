package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.application.port.out.LogLevel
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.domain.model.ApprovalContext
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage

/**
 * Use case used by the engine: the domain model, not the graph, is the source of truth for the UI, so
 * every node reports here. Each call persists the run and publishes the matching domain event.
 */
interface RecordRunProgress {
    fun nodeStarted(runId: WorkflowRunId, nodeId: String): WorkflowRun
    fun nodeFinished(runId: WorkflowRunId, nodeId: String, completion: NodeCompletion): WorkflowRun
    fun nodeFailed(
        runId: WorkflowRunId,
        nodeId: String,
        error: String,
        port: String? = null,
        usage: TokenUsage = TokenUsage.ZERO,
        cost: Money = Money.ZERO,
        runnerJobId: RunnerJobId? = null,
        repositories: List<RepositoryResult> = emptyList(),
        apiCost: Money? = null,
    ): WorkflowRun
    fun nodeSkipped(runId: WorkflowRunId, nodeId: String, reason: String): WorkflowRun
    fun waitingForHuman(runId: WorkflowRunId, nodeId: String, question: String, options: List<String>, context: ApprovalContext): ApprovalRequest
    fun waitingForCi(runId: WorkflowRunId, nodeId: String, signal: String): WorkflowRun

    /**
     * The running TASK [nodeId] prepared [write] and waits for a person to confirm it (ADR 0033): opens a write
     * confirmation (options approve / reject) decided by [approvers]. Idempotent per open approval.
     */
    fun waitingForWriteConfirmation(runId: WorkflowRunId, nodeId: String, write: PendingWrite, approvers: GateApprovers, context: ApprovalContext): ApprovalRequest
    fun finished(runId: WorkflowRunId, status: RunStatus, outcome: RunOutcome? = null, failure: RunFailure? = null): WorkflowRun
    fun addArtifact(runId: WorkflowRunId, nodeId: String, artifact: NewArtifact): Artifact
    /** [projectId] (when the caller knows it) saves a lookup of the run for the event. */
    fun appendLog(runId: WorkflowRunId, nodeId: String?, level: LogLevel, message: String, projectId: ProjectId? = null)

    data class NodeCompletion(
        val port: String?,
        val summary: String? = null,
        val confidence: Double? = null,
        val usage: TokenUsage = TokenUsage.ZERO,
        val cost: Money = Money.ZERO,
        /** ADR 0042: null = the same as [cost]. */
        val apiCost: Money? = null,
        val runnerJobId: RunnerJobId? = null,
        val artifacts: List<NewArtifact> = emptyList(),
        val mergeRequest: MergeRequestSnapshot? = null,
        val review: ReviewReport? = null,
        /** Per-repository result of a runner step (ADR 0034). */
        val repositories: List<RepositoryResult> = emptyList(),
    )
}
