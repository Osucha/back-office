package pl.mos.backoffice.evaluation.adapter.out.executor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt
import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt.AttemptExecutionReport
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Attempts on the work queue (ADR 0023) with the retry and backoff of ADR 0020: the work item's attempt number
 * is the retry counter, so the policy is deterministic without timers.
 */
class AttemptExecutorAdapterTest {

    private val attemptId = AttemptId.new()
    private val attempt = Attempt(
        id = attemptId, benchmarkId = BenchmarkId.new(), taskId = EvalTaskId.new(), taskName = "t", category = TaskCategory.ANALYSIS,
        difficulty = Difficulty.EASY, kind = TaskKind.PROMPT, modelProfileId = ModelProfileId.new(), modelDisplayName = "m",
        provider = Provider.ANTHROPIC, repetition = 1, createdAt = Instant.parse("2026-09-27T10:00:00Z"),
    )
    private val execute = mockk<ExecuteAttempt>()
    private val published = mutableListOf<DomainEvent>()
    private val policy = AttemptExecutorAdapter.RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(25))
    private val handler = BenchmarkAttemptHandler(execute, EventPublisher { published += it }, policy)

    private val retry = AttemptExecutionReport(attempt, null, null, retry = true, retryReason = "rate limited")
    private val done = AttemptExecutionReport(attempt, null, null)

    private fun item(attemptNo: Int) = WorkItem(UUID.randomUUID(), WorkKinds.BENCHMARK_ATTEMPT, mapOf("attemptId" to attemptId.toString()), attemptNo, "BENCHMARK_ATTEMPT:$attemptId")

    @Test
    fun `submit enqueues one deduplicated BENCHMARK_ATTEMPT work item`() {
        val enqueued = mutableListOf<WorkRequest>()
        AttemptExecutorAdapter({ enqueued += it }, policy).submit(attemptId)
        val request = enqueued.single()
        assertThat(request.kind).isEqualTo(WorkKinds.BENCHMARK_ATTEMPT)
        assertThat(request.dedupeKey).isEqualTo("BENCHMARK_ATTEMPT:$attemptId")
        assertThat(request.payload).containsEntry("attemptId", attemptId.toString())
        assertThat(request.maxAttempts).isEqualTo(policy.workItemAttempts)
    }

    @Test
    fun `a retryable failure is re-queued with exponential backoff until it succeeds`() {
        every { execute.execute(attemptId, true) } returnsMany listOf(retry, retry, done)

        val outcomes = (1..3).map { handler.handle(item(it)) }

        assertThat(outcomes).containsExactly(
            WorkOutcome.Retry(Duration.ofSeconds(10), "rate limited"), WorkOutcome.Retry(Duration.ofSeconds(20), "rate limited"), WorkOutcome.Done,
        )
        verify(exactly = 3) { execute.execute(attemptId, true) }
        verify(exactly = 0) { execute.execute(attemptId, false) }
    }

    @Test
    fun `after the retry budget the attempt runs once more without retry and fails there`() {
        every { execute.execute(attemptId, true) } returns retry
        every { execute.execute(attemptId, false) } returns done

        val outcomes = (1..5).map { handler.handle(item(it)) }

        assertThat(outcomes.take(3).map { (it as WorkOutcome.Retry).after }).describedAs("capped at maxBackoff")
            .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(25))
        assertThat((outcomes[3] as WorkOutcome.Retry).after).isEqualTo(Duration.ZERO)
        assertThat(outcomes[4]).isEqualTo(WorkOutcome.Done)
        verifyOrder {
            execute.execute(attemptId, true)
            execute.execute(attemptId, true)
            execute.execute(attemptId, true)
            execute.execute(attemptId, true)
            execute.execute(attemptId, false)
        }
    }

    @Test
    fun `a coding attempt waiting for its runner job completes the item without events`() {
        every { execute.execute(attemptId, true) } returns AttemptExecutionReport(attempt, null, null, waiting = true)
        assertThat(handler.handle(item(1))).isEqualTo(WorkOutcome.Done)
        assertThat(published).isEmpty()
    }

    @Test
    fun `backoff doubles from the initial delay and never exceeds the maximum`() {
        val p = AttemptExecutorAdapter.RetryPolicy(6, Duration.ofSeconds(15), Duration.ofMinutes(5))
        assertThat((1..7).map { p.backoff(it).seconds }).containsExactly(15, 30, 60, 120, 240, 300, 300)
    }
}
