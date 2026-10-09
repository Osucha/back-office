package pl.mos.backoffice.evaluation.domain.event

import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkProgressCounts
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.shared.DomainEvent
import java.time.Instant

/** Emitted after every attempt reaches a terminal state; the UI updates progress bars from it. */
data class BenchmarkProgress(
    val benchmarkId: BenchmarkId,
    val counts: BenchmarkProgressCounts,
    val attemptId: AttemptId,
    val attemptStatus: AttemptStatus,
    override val occurredAt: Instant,
) : DomainEvent {
    override val type: String = "BenchmarkProgress"
    override val payload: Map<String, Any?>
        get() = mapOf(
            "benchmarkId" to benchmarkId.toString(),
            "total" to counts.total,
            "done" to counts.done,
            "failed" to counts.failed,
            "attemptId" to attemptId.toString(),
            "status" to attemptStatus.name,
        )
}

/** Emitted once when a benchmark leaves `RUNNING`. */
data class BenchmarkFinished(
    val benchmarkId: BenchmarkId,
    val status: BenchmarkStatus,
    val counts: BenchmarkProgressCounts,
    override val occurredAt: Instant,
) : DomainEvent {
    override val type: String = "BenchmarkFinished"
    override val payload: Map<String, Any?>
        get() = mapOf(
            "benchmarkId" to benchmarkId.toString(),
            "status" to status.name,
            "total" to counts.total,
            "done" to counts.done,
            "failed" to counts.failed,
        )
}
