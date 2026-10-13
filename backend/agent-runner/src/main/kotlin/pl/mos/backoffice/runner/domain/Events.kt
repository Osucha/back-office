package pl.mos.backoffice.runner.domain

import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.RunnerJobId
import java.time.Instant
import java.util.UUID

/**
 * `RunnerLog` domain event forwarded to the SSE stream for every log line posted by a sandbox. `projectId`
 * (from the job's usage context) lets the stream deliver project runner logs only to people who may view
 * the project; benchmark jobs have no project.
 */
data class RunnerLogEvent(
    val jobId: RunnerJobId,
    val message: String,
    val progress: Boolean,
    override val occurredAt: Instant,
    val projectId: UUID? = null,
    val runId: UUID? = null,
    val level: String = "INFO",
    /** The workflow step that submitted the job (usage context `stepId`), so run pages attribute the line live. */
    val nodeId: String? = null,
) : DomainEvent {
    override val type: String = "RunnerLog"
    override val payload: Map<String, Any?> = mapOf(
        "jobId" to jobId.toString(), "projectId" to projectId?.toString(), "runId" to runId?.toString(),
        "message" to message, "progress" to progress, "level" to level, "nodeId" to nodeId,
    )
}
