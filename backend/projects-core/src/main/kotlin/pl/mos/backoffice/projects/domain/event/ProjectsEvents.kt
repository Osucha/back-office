package pl.mos.backoffice.projects.domain.event

import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.DomainEvent
import java.time.Instant

/**
 * Domain events of the Projects & Workflows context. `type` values match `DomainEventEnvelope.type`
 * in openapi.yaml so the SSE adapter can forward them verbatim. Every payload carries `projectId`: the
 * SSE stream delivers an event only to subscribers who may view that project (`project.view`).
 */
sealed class ProjectsEvent : DomainEvent

data class WorkflowRunStarted(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val workflowName: String,
    val trigger: String,
    val issueKey: String?,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "WorkflowRunStarted"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId.toString(), "projectId" to projectId.toString(), "workflowName" to workflowName, "trigger" to trigger, "issueKey" to issueKey,
    )
}

data class NodeStarted(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val nodeId: String,
    val kind: String,
    val attempt: Int,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "NodeStarted"
    override val payload: Map<String, Any?> = mapOf("runId" to runId.toString(), "projectId" to projectId.toString(), "nodeId" to nodeId, "kind" to kind, "attempt" to attempt)
}

data class NodeCompleted(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val nodeId: String,
    val port: String?,
    val summary: String?,
    val confidence: Double?,
    val costAmount: String,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "NodeCompleted"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId.toString(), "projectId" to projectId.toString(), "nodeId" to nodeId, "port" to port, "summary" to summary, "confidence" to confidence, "cost" to costAmount,
    )
}

data class NodeFailed(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val nodeId: String,
    val error: String,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "NodeFailed"
    override val payload: Map<String, Any?> = mapOf("runId" to runId.toString(), "projectId" to projectId.toString(), "nodeId" to nodeId, "error" to error)
}

data class RunWaitingForHuman(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val nodeId: String,
    val approvalId: ApprovalRequestId,
    val question: String,
    override val occurredAt: Instant,
    /** `GATE` or `WRITE_CONFIRMATION` (ADR 0033). */
    val kind: String = "GATE",
) : ProjectsEvent() {
    override val type = "RunWaitingForHuman"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId.toString(), "projectId" to projectId.toString(), "nodeId" to nodeId, "approvalId" to approvalId.toString(), "question" to question,
        "kind" to kind,
    )
}

data class RunWaitingForCi(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val nodeId: String,
    val signal: String,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "RunWaitingForCi"
    override val payload: Map<String, Any?> = mapOf("runId" to runId.toString(), "projectId" to projectId.toString(), "nodeId" to nodeId, "signal" to signal)
}

data class ApprovalDecided(
    val approvalId: ApprovalRequestId,
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val option: String,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "ApprovalDecided"
    override val payload: Map<String, Any?> = mapOf("approvalId" to approvalId.toString(), "runId" to runId.toString(), "projectId" to projectId.toString(), "option" to option)
}

data class CiSignalReceived(
    val runId: WorkflowRunId?,
    val projectId: ProjectId,
    val signal: String,
    val status: String,
    val resumed: Boolean,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "CiSignalReceived"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId?.toString(), "projectId" to projectId.toString(), "signal" to signal, "status" to status, "resumed" to resumed,
    )
}

data class WorkflowRunFinished(
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val status: RunStatus,
    val outcome: String?,
    val costAmount: String,
    val failure: String?,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "WorkflowRunFinished"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId.toString(), "projectId" to projectId.toString(), "status" to status.name, "outcome" to outcome,
        "cost" to costAmount, "failure" to failure,
    )
}

data class CiExecutionFinished(
    val executionId: CiExecutionId,
    val projectId: ProjectId,
    val entrypoint: String,
    val status: String,
    val gateResult: String,
    val runId: WorkflowRunId?,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "CiExecutionFinished"
    override val payload: Map<String, Any?> = mapOf(
        "executionId" to executionId.toString(), "projectId" to projectId.toString(), "entrypoint" to entrypoint,
        "status" to status, "gateResult" to gateResult, "runId" to runId?.toString(),
    )
}

/** A log line produced while a run executes (engine or runner). Payload mirrors `LogLine` in the API. */
data class RunnerLog(
    val runId: WorkflowRunId?,
    val projectId: ProjectId?,
    val nodeId: String?,
    val jobId: String?,
    val level: String,
    val message: String,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "RunnerLog"
    override val payload: Map<String, Any?> = mapOf(
        "runId" to runId?.toString(), "projectId" to projectId?.toString(), "nodeId" to nodeId, "jobId" to jobId, "level" to level, "message" to message,
    )
}

/** An open approval was assigned to other users (or the assignment was cleared: [assignees] is empty). */
/**
 * A connection's token expires within 14 days or has expired (daily reminder, ADR 0030). [projectId] null = a
 * platform connection. Only names and dates, never the secret.
 */
data class ConnectionExpiring(
    val connectionId: String,
    val projectId: ProjectId?,
    val name: String,
    val scope: String,
    val expiresAt: java.time.LocalDate,
    val daysLeft: Long,
    override val occurredAt: Instant,
    /** ADR 0035: the owner of a personal connection; the stream delivers the event to that person only. */
    val ownerUserId: java.util.UUID? = null,
) : ProjectsEvent() {
    override val type = "ConnectionExpiring"
    override val payload: Map<String, Any?> = mapOf(
        "connectionId" to connectionId, "projectId" to projectId?.toString(), "name" to name, "scope" to scope,
        "expiresAt" to expiresAt.toString(), "daysLeft" to daysLeft,
    ) + (ownerUserId?.let { mapOf("ownerUserId" to it.toString()) } ?: emptyMap())
}

data class ApprovalAssigned(
    val approvalId: ApprovalRequestId,
    val runId: WorkflowRunId,
    val projectId: ProjectId,
    val assignees: List<java.util.UUID>,
    override val occurredAt: Instant,
) : ProjectsEvent() {
    override val type = "ApprovalAssigned"
    override val payload: Map<String, Any?> = mapOf(
        "approvalId" to approvalId.toString(), "runId" to runId.toString(), "projectId" to projectId.toString(), "assignees" to assignees.map { it.toString() },
    )
}
