package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

enum class BenchmarkStatus {
    DRAFT, RUNNING, COMPLETED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class AttemptStatus {
    PENDING, RUNNING, JUDGING, SUCCEEDED, FAILED, TIMED_OUT;

    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == TIMED_OUT
}

/** Result of the verification command of a coding attempt (tests). */
data class AttemptVerification(val command: String, val exitCode: Int, val outputTail: String) {
    val passed: Boolean get() = exitCode == 0
}

/** One rubric criterion scored by the judge (0-10). */
data class JudgeCriterion(val name: String, val score: Double, val comment: String? = null)

/** Structured answer of the judge model. */
data class JudgeVerdict(
    val score: Double,
    val passed: Boolean,
    val criteria: List<JudgeCriterion> = emptyList(),
    val rationale: String? = null,
) {
    init {
        if (score < 0 || score > 100) throw ValidationException("judge score must be within 0..100, got $score", "score")
    }
}

/** Counts derived from the attempts of a benchmark. */
data class BenchmarkProgressCounts(val total: Int, val done: Int, val failed: Int) {
    val complete: Boolean get() = total > 0 && done == total

    companion object {
        val EMPTY = BenchmarkProgressCounts(0, 0, 0)
        fun of(attempts: Collection<Attempt>) = BenchmarkProgressCounts(
            total = attempts.size,
            done = attempts.count { it.status.terminal },
            failed = attempts.count { it.status == AttemptStatus.FAILED || it.status == AttemptStatus.TIMED_OUT },
        )
    }
}

/**
 * Aggregate root: one comparison of models on a suite. Attempts are persisted separately (they are
 * large and updated concurrently) and refer to the benchmark by id; the aggregate holds the plan and
 * the status machine `DRAFT -> RUNNING -> COMPLETED | FAILED | CANCELLED`.
 */
data class Benchmark(
    val id: BenchmarkId,
    val name: String,
    val suiteId: TaskSuiteId,
    /** Subset of suite tasks to run; empty means every task of the suite. */
    val taskIds: List<EvalTaskId> = emptyList(),
    val modelProfileIds: List<ModelProfileId>,
    val judgeModelProfileId: ModelProfileId,
    val repetitions: Int = 1,
    val codingHarness: AgentHarness = AgentHarness.OPENCODE,
    val status: BenchmarkStatus = BenchmarkStatus.DRAFT,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Who created the benchmark (ADR 0017); null for rows written before actor stamps existed. */
    val createdBy: Actor? = null,
    /** Who changed its lifecycle last (start, cancel); null until then. */
    val updatedBy: Actor? = null,
) {
    init {
        val errors = mutableListOf<ValidationException.FieldError>()
        if (name.isBlank()) errors += ValidationException.FieldError("name", "must not be blank")
        if (modelProfileIds.isEmpty()) errors += ValidationException.FieldError("modelProfileIds", "at least one model is required")
        if (modelProfileIds.toSet().size != modelProfileIds.size) errors += ValidationException.FieldError("modelProfileIds", "must not contain duplicates")
        if (repetitions !in 1..MAX_REPETITIONS) errors += ValidationException.FieldError("repetitions", "must be within 1..$MAX_REPETITIONS")
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    fun start(now: Instant, actor: Actor? = null): Benchmark {
        if (status != BenchmarkStatus.DRAFT) throw InvalidStateException("benchmark $id cannot be started from $status")
        return copy(status = BenchmarkStatus.RUNNING, startedAt = now, updatedBy = actor ?: updatedBy)
    }

    fun cancel(now: Instant, actor: Actor? = null): Benchmark {
        if (status.terminal) throw InvalidStateException("benchmark $id is already $status")
        return copy(status = BenchmarkStatus.CANCELLED, finishedAt = now, updatedBy = actor ?: updatedBy)
    }

    /**
     * Called when every attempt is terminal. `FAILED` only when no attempt succeeded; individual
     * failures are visible on the attempts but do not fail the comparison. Idempotent for terminal states.
     */
    fun finish(anySucceeded: Boolean, now: Instant): Benchmark {
        if (status.terminal) return this
        if (status != BenchmarkStatus.RUNNING) throw InvalidStateException("benchmark $id cannot finish from $status")
        return copy(status = if (anySucceeded) BenchmarkStatus.COMPLETED else BenchmarkStatus.FAILED, finishedAt = now)
    }

    companion object {
        const val MAX_REPETITIONS = 5

        fun create(
            name: String,
            suiteId: TaskSuiteId,
            taskIds: List<EvalTaskId>,
            modelProfileIds: List<ModelProfileId>,
            judgeModelProfileId: ModelProfileId,
            repetitions: Int,
            codingHarness: AgentHarness,
            now: Instant,
            id: BenchmarkId = BenchmarkId.new(),
            actor: Actor? = null,
        ): Benchmark = Benchmark(
            id = id,
            name = name,
            suiteId = suiteId,
            taskIds = taskIds,
            modelProfileIds = modelProfileIds,
            judgeModelProfileId = judgeModelProfileId,
            repetitions = repetitions,
            codingHarness = codingHarness,
            createdAt = now,
            createdBy = actor,
            updatedBy = actor,
        )
    }
}

/**
 * One execution of one task by one model. Snapshots the task and model descriptors so the leaderboard
 * and the UI stay meaningful after tasks or profiles are edited or deleted.
 */
data class Attempt(
    val id: AttemptId,
    val benchmarkId: BenchmarkId,
    val taskId: EvalTaskId,
    val taskName: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val kind: TaskKind,
    val modelProfileId: ModelProfileId,
    val modelDisplayName: String,
    val provider: Provider,
    val repetition: Int,
    val status: AttemptStatus = AttemptStatus.PENDING,
    val output: String? = null,
    val verification: AttemptVerification? = null,
    val score: Double? = null,
    val passed: Boolean? = null,
    val judgeRationale: String? = null,
    val judgeCriteria: List<JudgeCriterion> = emptyList(),
    /** Tokens and cost of the candidate model (or harness); the judge is booked separately. */
    val usage: TokenUsage = TokenUsage.ZERO,
    val cost: Money = Money.ZERO,
    val judgeCost: Money = Money.ZERO,
    val durationMs: Long? = null,
    val runnerJobId: RunnerJobId? = null,
    val error: String? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
) {
    fun start(now: Instant): Attempt = copy(status = AttemptStatus.RUNNING, startedAt = now, error = null)

    fun withRunnerJob(jobId: RunnerJobId): Attempt = copy(runnerJobId = jobId)

    fun judging(output: String?, verification: AttemptVerification?, usage: TokenUsage, cost: Money): Attempt =
        copy(status = AttemptStatus.JUDGING, output = output, verification = verification, usage = usage, cost = cost)

    fun succeed(score: Double, passed: Boolean, verdict: JudgeVerdict, judgeCost: Money, now: Instant): Attempt = copy(
        status = AttemptStatus.SUCCEEDED,
        score = score,
        passed = passed,
        judgeRationale = verdict.rationale,
        judgeCriteria = verdict.criteria,
        judgeCost = judgeCost,
        finishedAt = now,
        durationMs = durationSince(now),
    )

    fun fail(error: String, now: Instant): Attempt =
        copy(status = AttemptStatus.FAILED, error = error.take(MAX_ERROR_LENGTH), finishedAt = now, durationMs = durationSince(now))

    fun timeOut(error: String?, now: Instant): Attempt =
        copy(status = AttemptStatus.TIMED_OUT, error = error?.take(MAX_ERROR_LENGTH) ?: "timed out", finishedAt = now, durationMs = durationSince(now))

    /** Back to the queue after a restart; keeps `runnerJobId` so a coding job can re-attach. */
    fun requeue(): Attempt = copy(status = AttemptStatus.PENDING, startedAt = null, finishedAt = null, durationMs = null)

    private fun durationSince(now: Instant): Long? = startedAt?.let { now.toEpochMilli() - it.toEpochMilli() }

    companion object {
        const val MAX_ERROR_LENGTH = 4000
    }
}
