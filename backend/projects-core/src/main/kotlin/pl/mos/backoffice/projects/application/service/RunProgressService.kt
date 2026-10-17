package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.CompleteCiExecution
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress.NodeCompletion
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.LogLevel
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.NodeCompleted
import pl.mos.backoffice.projects.domain.event.NodeFailed
import pl.mos.backoffice.projects.domain.event.NodeStarted
import pl.mos.backoffice.projects.domain.event.RunWaitingForCi
import pl.mos.backoffice.projects.domain.event.RunWaitingForHuman
import pl.mos.backoffice.projects.domain.event.RunnerLog
import pl.mos.backoffice.projects.domain.event.WorkflowRunFinished
import pl.mos.backoffice.projects.domain.model.ApprovalContext
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.runner.AgentRunner
import java.time.Duration
import java.time.Instant

/**
 * Implements [RecordRunProgress]. Serialises updates per run with a simple lock so concurrent node
 * callbacks (e.g. log lines while a node finishes) never lose writes. A finished CI-triggered run is
 * propagated to its CI execution. Called only by the workflow engine (system principal); events carry the
 * run's `projectId` so the SSE stream can filter them. An opened approval copies the gate's approver
 * rule and checklist and the run's `startedBy`.
 */
class RunProgressService(
    private val runs: WorkflowRunRepository,
    private val approvals: ApprovalRequestRepository,
    private val logs: RunLogRepository,
    private val ciExecutions: CompleteCiExecution,
    private val events: EventPublisher,
    private val clock: Clock,
    /** ADR 0044: releases the run's workspaces when it ends; null = no runner (tests). */
    private val runner: AgentRunner? = null,
) : RecordRunProgress {

    /** In-process only; across replicas a run is advanced by the one worker holding its RUN_ADVANCE lease. */
    private val lock = Any()

    @SystemOperation(ENGINE)
    override fun nodeStarted(runId: WorkflowRunId, nodeId: String): WorkflowRun = synchronized(lock) {
        val run = load(runId)
        val node = run.definitionSnapshot.requireNode(nodeId)
        val now = clock.now()
        val saved = runs.save(run.nodeStarted(node, now))
        val execution = saved.nodes.last { it.nodeId == nodeId }
        events.publish(NodeStarted(runId, saved.projectId, nodeId, node.kind.name, execution.attempt, now))
        saved
    }

    @SystemOperation(ENGINE)
    override fun nodeFinished(runId: WorkflowRunId, nodeId: String, completion: NodeCompletion): WorkflowRun = synchronized(lock) {
        var run = load(runId)
        val now = clock.now()
        val created = completion.artifacts.map { toArtifact(nodeId, it, now) }
        created.forEach { run = run.addArtifact(it) }
        completion.mergeRequest?.let { run = run.withMergeRequest(it) }
        completion.review?.let { run = run.withReview(it) }
        run = run.nodeFinished(
            nodeId = nodeId,
            port = completion.port,
            now = now,
            summary = completion.summary,
            confidence = completion.confidence,
            usage = completion.usage,
            cost = completion.cost,
            runnerJobId = completion.runnerJobId,
            artifactIds = created.map { it.id },
            repositories = completion.repositories,
            apiCost = completion.apiCost,
        )
        val saved = runs.save(run)
        events.publish(NodeCompleted(runId, saved.projectId, nodeId, completion.port, completion.summary, completion.confidence, completion.cost.amount.toPlainString(), now))
        saved
    }

    @SystemOperation(ENGINE)
    override fun nodeFailed(
        runId: WorkflowRunId,
        nodeId: String,
        error: String,
        port: String?,
        usage: TokenUsage,
        cost: Money,
        runnerJobId: RunnerJobId?,
        repositories: List<RepositoryResult>,
        apiCost: Money?,
    ): WorkflowRun =
        synchronized(lock) {
            val run = load(runId)
            val now = clock.now()
            val saved = runs.save(run.nodeFailed(nodeId, error, now, port, usage, cost, runnerJobId, repositories, apiCost))
            events.publish(NodeFailed(runId, saved.projectId, nodeId, error, now))
            saved
        }

    @SystemOperation(ENGINE)
    override fun nodeSkipped(runId: WorkflowRunId, nodeId: String, reason: String): WorkflowRun = synchronized(lock) {
        val run = load(runId)
        runs.save(run.nodeSkipped(run.definitionSnapshot.requireNode(nodeId), clock.now(), reason))
    }

    @SystemOperation(ENGINE)
    override fun waitingForHuman(runId: WorkflowRunId, nodeId: String, question: String, options: List<String>, context: ApprovalContext): ApprovalRequest =
        synchronized(lock) {
            val run = load(runId)
            approvals.findOpenByRunId(runId)?.let { return it }
            val node = run.definitionSnapshot.requireNode(nodeId)
            val now = clock.now()
            val approval = ApprovalRequest(
                id = ApprovalRequestId.new(),
                runId = runId,
                nodeId = nodeId,
                projectId = run.projectId,
                projectKey = run.projectKey,
                workflowName = run.workflowName,
                issueKey = run.issueKey,
                question = question,
                options = options.ifEmpty { GateSpec.DEFAULT_OPTIONS },
                context = context,
                createdAt = now,
                approvers = (node as? WorkflowNode.HumanGate)?.gate?.approvers ?: GateApprovers.ANYONE,
                checklist = (node as? WorkflowNode.HumanGate)?.gate?.checklist ?: emptyList(),
                startedBy = run.startedBy,
            )
            val waiting = run.waitingForHuman(node, approval.id, now)
            approvals.save(approval)
            runs.save(waiting)
            events.publish(RunWaitingForHuman(runId, run.projectId, nodeId, approval.id, question, now))
            approval
        }

    @SystemOperation(ENGINE)
    override fun waitingForWriteConfirmation(
        runId: WorkflowRunId,
        nodeId: String,
        write: PendingWrite,
        approvers: GateApprovers,
        context: ApprovalContext,
    ): ApprovalRequest = synchronized(lock) {
        val run = load(runId)
        approvals.findOpenByRunId(runId)?.let { return it }
        val node = run.definitionSnapshot.requireNode(nodeId)
        val now = clock.now()
        val approval = ApprovalRequest(
            id = ApprovalRequestId.new(),
            runId = runId,
            nodeId = nodeId,
            projectId = run.projectId,
            projectKey = run.projectKey,
            workflowName = run.workflowName,
            issueKey = write.issueKey,
            question = write.question(),
            options = WriteApprovalConfig.OPTIONS,
            context = context,
            createdAt = now,
            approvers = approvers,
            startedBy = run.startedBy,
            kind = ApprovalKind.WRITE_CONFIRMATION,
            write = write,
        )
        val waiting = run.waitingForWriteConfirmation(node, approval.id, now)
        approvals.save(approval)
        runs.save(waiting)
        events.publish(RunWaitingForHuman(runId, run.projectId, nodeId, approval.id, approval.question, now, ApprovalKind.WRITE_CONFIRMATION.name))
        approval
    }

    @SystemOperation(ENGINE)
    override fun waitingForCi(runId: WorkflowRunId, nodeId: String, signal: String): WorkflowRun = synchronized(lock) {
        val run = load(runId)
        if (run.status == RunStatus.WAITING && run.waitingOn?.nodeId == nodeId) return run
        val node = run.definitionSnapshot.requireNode(nodeId)
        val now = clock.now()
        val saved = runs.save(run.waitingForCi(node, signal, now))
        events.publish(RunWaitingForCi(runId, saved.projectId, nodeId, signal, now))
        saved
    }

    @SystemOperation(ENGINE)
    override fun finished(runId: WorkflowRunId, status: RunStatus, outcome: RunOutcome?, failure: RunFailure?): WorkflowRun = synchronized(lock) {
        val run = load(runId)
        if (run.status.terminal) return run
        val now = clock.now()
        val finished = when (status) {
            RunStatus.SUCCEEDED -> run.succeeded(outcome ?: RunOutcome.SUCCESS, now)
            RunStatus.FAILED -> run.failed(failure ?: RunFailure(run.currentNodeId, "run failed", true), now)
            RunStatus.CANCELLED -> run.cancelled(now)
            else -> throw InvalidStateException("$status is not a terminal status")
        }.rollUpCost()
        approvals.findOpenByRunId(runId)?.let { approvals.save(it.expire()) }
        val saved = runs.save(finished)
        events.publish(WorkflowRunFinished(runId, run.projectId, status, saved.outcome?.name, saved.cost.amount.toPlainString(), failure?.message, now))
        ciExecutions.runFinished(saved)
        // ADR 0044: a failed run keeps its workspaces for a retry for a while; others release them now
        runner?.releaseWorkspaces(runId.value, if (status == RunStatus.FAILED) WORKSPACE_KEEP_AFTER_FAILURE else Duration.ZERO)
        saved
    }

    @SystemOperation(ENGINE)
    override fun addArtifact(runId: WorkflowRunId, nodeId: String, artifact: NewArtifact): Artifact = synchronized(lock) {
        val run = load(runId)
        val created = toArtifact(nodeId, artifact, clock.now())
        runs.save(run.addArtifact(created))
        created
    }

    @SystemOperation(ENGINE)
    override fun appendLog(runId: WorkflowRunId, nodeId: String?, level: LogLevel, message: String, projectId: ProjectId?) {
        val now = clock.now()
        logs.append(RunLogLine(runId, nodeId, now, level, message))
        // the engine passes the project; other callers pay one lookup (no per-process map: runs move between workers)
        events.publish(RunnerLog(runId, projectId ?: runs.findById(runId)?.projectId, nodeId, null, level.name, message, now))
    }

    private fun load(runId: WorkflowRunId): WorkflowRun = runs.findById(runId) ?: throw NotFoundException("Run", runId)

    private companion object {
        const val ENGINE = "workflow engine progress callback"

        /** ADR 0044: how long the workspaces of a failed run wait for a retry. */
        val WORKSPACE_KEEP_AFTER_FAILURE: Duration = Duration.ofHours(24)
    }

    private fun toArtifact(nodeId: String, artifact: NewArtifact, now: Instant) = Artifact(
        id = ArtifactId.new(),
        nodeId = nodeId,
        name = artifact.name,
        kind = artifact.kind,
        mediaType = artifact.mediaType,
        content = artifact.content,
        createdAt = now,
    )
}
