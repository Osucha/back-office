package pl.mos.backoffice.evaluation.adapter.out.executor

import org.slf4j.LoggerFactory
import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt
import pl.mos.backoffice.evaluation.application.port.out.AttemptExecutor
import pl.mos.backoffice.evaluation.application.port.out.AttemptWork
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkQueue
import java.time.Duration

/**
 * [AttemptExecutor] on the durable work queue (ADR 0023): every attempt becomes a `BENCHMARK_ATTEMPT` work
 * item (dedupe key `BENCHMARK_ATTEMPT:<attemptId>`), written in the caller's transaction, so it is executed
 * after commit by whichever worker replica claims it and survives restarts. Provider concurrency is no longer
 * limited here: the distributed provider rate limiter of the LLM gateway (ADR 0020) decides.
 */
class AttemptExecutorAdapter(private val queue: WorkQueue, private val retryPolicy: RetryPolicy = RetryPolicy()) : AttemptExecutor {

    /**
     * Retryable provider failures (rate limiter, 429/5xx) re-queue with exponential backoff, at most
     * [maxAttempts] times; the run after that has no retry budget and fails the attempt.
     */
    data class RetryPolicy(
        val maxAttempts: Int = 6,
        val initialBackoff: Duration = Duration.ofSeconds(15),
        val maxBackoff: Duration = Duration.ofMinutes(5),
    ) {
        /** Delay before re-queue number [retry] (1-based): initial * 2^(retry-1), capped. */
        fun backoff(retry: Int): Duration {
            val shift = (retry - 1).coerceIn(0, 20)
            val millis = initialBackoff.toMillis().coerceAtLeast(1) * (1L shl shift)
            return Duration.ofMillis(millis.coerceAtMost(maxBackoff.toMillis()))
        }

        /** Work-item attempts: every retry, the final run without retry, and one spare for a crashed worker. */
        val workItemAttempts: Int get() = maxAttempts + 3
    }

    override fun submit(attemptId: AttemptId) = queue.enqueue(AttemptWork.request(attemptId, retryPolicy.workItemAttempts))
}

/**
 * [WorkHandler] of `BENCHMARK_ATTEMPT`: runs [ExecuteAttempt] on the worker holding the item's lease and
 * publishes `BenchmarkProgress` / `BenchmarkFinished`. The work item's attempt counter is the retry counter:
 * attempts 1..maxAttempts+1 may re-queue (`report.retry` -> `Retry` after the policy's backoff, the last one
 * immediately), attempt maxAttempts+2 runs without a retry budget. A CODING_JOB attempt that submitted its
 * runner job reports `waiting`; the job's continuation (same dedupe key) calls again.
 */
class BenchmarkAttemptHandler(
    private val executeAttempt: ExecuteAttempt,
    private val events: EventPublisher,
    private val retryPolicy: AttemptExecutorAdapter.RetryPolicy = AttemptExecutorAdapter.RetryPolicy(),
) : WorkHandler {

    private val log = LoggerFactory.getLogger(BenchmarkAttemptHandler::class.java)

    override val kind: String = WorkKinds.BENCHMARK_ATTEMPT

    override fun handle(item: WorkItem): WorkOutcome {
        val attemptId = item.payload[AttemptWork.ATTEMPT_ID]?.toString()?.let { runCatching { AttemptId.of(it) }.getOrNull() }
            ?: return WorkOutcome.Dead("BENCHMARK_ATTEMPT without a valid attemptId")
        val allowRetry = item.attempt <= retryPolicy.maxAttempts + 1
        val report = executeAttempt.execute(attemptId, allowRetry)
        if (report.retry) {
            val retry = item.attempt
            if (retry > retryPolicy.maxAttempts) {
                log.warn("attempt retry budget used up attemptId={} retries={} reason={}", attemptId, retry - 1, report.retryReason)
                return WorkOutcome.Retry(Duration.ZERO, "retry budget used up: ${report.retryReason}")
            }
            val delay = retryPolicy.backoff(retry)
            log.info("attempt re-queued after retryable error attemptId={} retry={} delayMs={} reason={}", attemptId, retry, delay.toMillis(), report.retryReason)
            return WorkOutcome.Retry(delay, report.retryReason ?: "retryable provider error")
        }
        if (report.waiting) return WorkOutcome.Done
        report.progress?.let(events::publish)
        report.finished?.let {
            log.info("benchmark finished benchmarkId={} status={} done={} failed={}", it.benchmarkId, it.status, it.counts.done, it.counts.failed)
            events.publish(it)
        }
        return WorkOutcome.Done
    }
}
