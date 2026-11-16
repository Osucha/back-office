package pl.mos.backoffice.evaluation.application.port.`in`.system

import pl.mos.backoffice.evaluation.domain.event.BenchmarkFinished
import pl.mos.backoffice.evaluation.domain.event.BenchmarkProgress
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId

/** Use case (internal): run one attempt to completion on the calling thread. Invoked by the executor adapter. */
interface ExecuteAttempt {
    /**
     * With [allowRetry], a retryable provider failure (`IntegrationException(retryable = true)`, e.g. the
     * rate limiter of ADR 0020 or a provider 429) puts the attempt back to `PENDING` and reports
     * [AttemptExecutionReport.retry] instead of failing it; the executor re-submits it later with backoff.
     * Without it (retry budget used up) the attempt fails as any other error.
     */
    fun execute(attemptId: AttemptId, allowRetry: Boolean = true): AttemptExecutionReport

    /** What happened; the executor adapter publishes the events so they leave on its thread after commit. */
    data class AttemptExecutionReport(
        val attempt: Attempt?,
        val progress: BenchmarkProgress?,
        val finished: BenchmarkFinished?,
        /** The attempt is `PENDING` again after a retryable failure; [retryReason] says why. */
        val retry: Boolean = false,
        val retryReason: String? = null,
        /** A CODING_JOB attempt submitted its runner job (or it is still running); its continuation calls again. */
        val waiting: Boolean = false,
    ) {
        companion object {
            val SKIPPED = AttemptExecutionReport(null, null, null)
        }
    }
}

/** Use case (startup): re-queue attempts of benchmarks that were running when the server stopped. */
interface RecoverBenchmarks {
    /** Returns the number of re-queued attempts. */
    fun recover(): Int
}

/** Use case (startup): create the dated model seed and the starter suite when the catalogue is empty. */
interface SeedStarterContent {
    fun seedIfEmpty(): SeedResult

    data class SeedResult(val profilesCreated: Int, val suitesCreated: Int, val suitesUpgraded: Int = 0)
}
