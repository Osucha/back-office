package pl.mos.backoffice.evaluation.application.port.`in`

import pl.mos.backoffice.evaluation.domain.event.BenchmarkFinished
import pl.mos.backoffice.evaluation.domain.event.BenchmarkProgress
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkProgressCounts
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.Leaderboard
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentHarness
import java.time.Instant

/** Use case: create, start, cancel and inspect benchmarks. */
interface ManageBenchmarks {
    /** Creates a `DRAFT` benchmark; validates that suite, models and judge exist. */
    fun create(command: BenchmarkCommand): BenchmarkDetails
    /** Plans the attempt matrix (tasks x models x repetitions), persists it and queues execution. */
    fun start(id: BenchmarkId): BenchmarkDetails
    /** Marks the benchmark `CANCELLED`; pending attempts are skipped, running coding jobs are cancelled best-effort. */
    fun cancel(id: BenchmarkId): BenchmarkDetails
    fun get(id: BenchmarkId): BenchmarkDetails
    fun list(): List<BenchmarkDetails>
    /** Fails with `InvalidStateException` while the benchmark is running. */
    fun delete(id: BenchmarkId)
    fun attempts(id: BenchmarkId, taskId: EvalTaskId? = null, modelProfileId: ModelProfileId? = null): List<Attempt>
    fun attempt(id: BenchmarkId, attemptId: AttemptId): Attempt
    fun leaderboard(id: BenchmarkId): Leaderboard

    data class BenchmarkCommand(
        val name: String,
        val suiteId: TaskSuiteId,
        val modelProfileIds: List<ModelProfileId>,
        val judgeModelProfileId: ModelProfileId,
        val repetitions: Int = 1,
        val codingHarness: AgentHarness = AgentHarness.OPENCODE,
        val taskIds: List<EvalTaskId> = emptyList(),
    )

    /** Benchmark plus what the UI needs next to it. `models`/`judge` are null when a profile was deleted. */
    data class BenchmarkDetails(
        val benchmark: Benchmark,
        val suiteName: String?,
        val progress: BenchmarkProgressCounts,
        val totalCost: Money,
        val models: List<ModelProfile>,
        val judge: ModelProfile?,
    )
}

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

/** Read side for the dashboard composed in the `app` module. */
interface EvaluationDashboardQuery {
    fun runningBenchmarks(): Int
    fun recentBenchmarks(limit: Int): List<BenchmarkSummaryView>

    data class BenchmarkSummaryView(
        val id: BenchmarkId,
        val name: String,
        val status: BenchmarkStatus,
        val progress: BenchmarkProgressCounts,
        val totalCost: Money,
        val createdAt: Instant,
        val finishedAt: Instant?,
        val models: List<ModelSummary>,
    )

    data class ModelSummary(val id: ModelProfileId, val displayName: String, val provider: Provider?)
}

/** Use case (startup): create the dated model seed and the starter suite when the catalogue is empty. */
interface SeedStarterContent {
    fun seedIfEmpty(): SeedResult

    data class SeedResult(val profilesCreated: Int, val suitesCreated: Int)
}
