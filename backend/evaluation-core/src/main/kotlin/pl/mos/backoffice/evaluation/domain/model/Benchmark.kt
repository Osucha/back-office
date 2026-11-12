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
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import java.time.Instant
import pl.mos.backoffice.shared.runner.RunnerTarget

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
 * ADR 0059: an agent with a model compared in a benchmark. The model is resolved when the benchmark is created (the
 * agent's own model when none was chosen); [agentName] and [harness] are a snapshot of the agent (refreshed at start).
 * A model answer agent ([harness] null) does prompt tasks, a coding agent coding tasks.
 */
data class BenchmarkCandidate(
    val agentKey: String,
    val modelProfileId: ModelProfileId,
    val agentName: String = agentKey,
    val harness: AgentHarness? = null,
) {
    /** The agent can do a task of [kind]: model answers do prompt tasks, coding agents coding tasks. */
    fun applicable(kind: TaskKind): Boolean = CandidateRef.applicable(harness, kind)
}

/**
 * ADR 0059: an agent with a model as attempts and the leaderboard show it; [label] is "<agent> · <model>".
 */
data class CandidateRef(
    val agentKey: String,
    val agentName: String,
    val harness: AgentHarness?,
    val modelProfileId: ModelProfileId,
    val modelDisplayName: String,
) {
    val label: String get() = "$agentName · $modelDisplayName"

    /** The grouping key of the leaderboard: agent and model. */
    val key: Pair<String, ModelProfileId> get() = agentKey to modelProfileId

    companion object {
        /** Model answers ([harness] null) do prompt tasks, coding agents coding tasks; other pairs are not applicable. */
        fun applicable(harness: AgentHarness?, kind: TaskKind): Boolean = (harness == null) == (kind == TaskKind.PROMPT)

        /** Built-in names of the agents a benchmark without candidates uses (ADR 0057 decision 6). */
        const val MODEL_ANSWER_NAME = "Model answer"
        const val OPENCODE_NAME = "OpenCode"
        const val CLAUDE_CODE_NAME = "Claude Code"

        /**
         * A benchmark created with models only (before ADR 0059): prompt tasks are done by `model-answer`, coding tasks by
         * the agent of the benchmark's coding harness. Returns the agent's key, name and harness.
         */
        fun legacyAgent(kind: TaskKind, codingHarness: AgentHarness): Triple<String, String, AgentHarness?> = when {
            kind == TaskKind.PROMPT -> Triple(AgentSnapshot.MODEL_ANSWER, MODEL_ANSWER_NAME, null)
            codingHarness == AgentHarness.CLAUDE_CODE -> Triple(AgentSnapshot.CLAUDE_CODE, CLAUDE_CODE_NAME, AgentHarness.CLAUDE_CODE)
            else -> Triple(AgentSnapshot.OPENCODE, OPENCODE_NAME, AgentHarness.OPENCODE)
        }
    }
}

/**
 * Aggregate root: one comparison of candidates (ADR 0059: agents with models; before it, models) on a suite. Attempts
 * are persisted separately (they are large and updated concurrently) and refer to the benchmark by id; the aggregate
 * holds the plan and the status machine `DRAFT -> RUNNING -> COMPLETED | FAILED | CANCELLED`.
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
    /** ADR 0046: where its coding jobs run, the shared fleet or the personal runner of the user who created it. */
    val runnerTarget: RunnerTarget = RunnerTarget.SHARED,
    val status: BenchmarkStatus = BenchmarkStatus.DRAFT,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Who created the benchmark (ADR 0017); null for rows written before actor stamps existed. */
    val createdBy: Actor? = null,
    /** Who changed its lifecycle last (start, cancel); null until then. */
    val updatedBy: Actor? = null,
    /**
     * ADR 0059: the agents with models compared; [modelProfileIds] are then their distinct models. Empty = a benchmark
     * of models only ([legacy]): each model as `model-answer` for prompt tasks and as the agent of [codingHarness] for
     * coding tasks.
     */
    val candidates: List<BenchmarkCandidate> = emptyList(),
) {
    init {
        val errors = mutableListOf<ValidationException.FieldError>()
        if (name.isBlank()) errors += ValidationException.FieldError("name", "must not be blank")
        if (modelProfileIds.isEmpty()) errors += ValidationException.FieldError("modelProfileIds", "at least one candidate or model is required")
        if (candidates.isEmpty() && modelProfileIds.toSet().size != modelProfileIds.size) errors += ValidationException.FieldError("modelProfileIds", "must not contain duplicates")
        if (candidates.map { it.agentKey to it.modelProfileId }.toSet().size != candidates.size) {
            errors += ValidationException.FieldError("candidates", "must not contain the same agent with the same model twice")
        }
        if (repetitions !in 1..MAX_REPETITIONS) errors += ValidationException.FieldError("repetitions", "must be within 1..$MAX_REPETITIONS")
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    /** ADR 0059: created with models only (no candidates); runs as before ADR 0059. */
    val legacy: Boolean get() = candidates.isEmpty()

    /** ADR 0059: the same candidates with fresh agent snapshots (name, harness), taken when the benchmark starts. */
    fun withCandidates(refreshed: List<BenchmarkCandidate>): Benchmark = copy(candidates = refreshed)

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
            runnerTarget: RunnerTarget = RunnerTarget.SHARED,
            candidates: List<BenchmarkCandidate> = emptyList(),
        ): Benchmark = Benchmark(
            id = id,
            name = name,
            suiteId = suiteId,
            taskIds = taskIds,
            // ADR 0059: with candidates the models are theirs (a model-only benchmark lists its models)
            modelProfileIds = if (candidates.isEmpty()) modelProfileIds else candidates.map { it.modelProfileId }.distinct(),
            candidates = candidates,
            judgeModelProfileId = judgeModelProfileId,
            repetitions = repetitions,
            codingHarness = codingHarness,
            runnerTarget = runnerTarget,
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
    /**
     * ADR 0046/0065: the candidate's own time (model answer or agent job, from the start of the job on its runner), without
     * the judge, queueing or retries.
     */
    val candidateLatencyMs: Long? = null,
    /**
     * ADR 0065: the candidate's work at API list prices; equal to [cost] through the gateway, the harness's estimate (or
     * the tokens at API prices) on a subscription, where [cost] is 0.
     */
    val apiCost: Money = cost,
    /** ADR 0065: the judge's verdicts at API list prices (a judge on a subscription: its estimate; [judgeCost] is 0). */
    val judgeApiCost: Money = judgeCost,
    val runnerJobId: RunnerJobId? = null,
    val error: String? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** ADR 0059: the candidate's agent (key, name, harness; no harness = model answer); null on attempts before it. */
    val agentKey: String? = null,
    val agentName: String? = null,
    val harness: AgentHarness? = null,
) {
    /**
     * ADR 0059: the agent and model of this attempt. An attempt without an agent (stored before ADR 0059) had the agent
     * of a benchmark without candidates ([CandidateRef.legacyAgent] with the benchmark's [codingHarness]).
     */
    fun candidate(codingHarness: AgentHarness = AgentHarness.OPENCODE): CandidateRef {
        if (agentKey != null) return CandidateRef(agentKey, agentName ?: agentKey, harness, modelProfileId, modelDisplayName)
        val (key, name, legacyHarness) = CandidateRef.legacyAgent(kind, codingHarness)
        return CandidateRef(key, name, legacyHarness, modelProfileId, modelDisplayName)
    }

    fun start(now: Instant): Attempt = copy(status = AttemptStatus.RUNNING, startedAt = now, error = null)

    fun withRunnerJob(jobId: RunnerJobId): Attempt = copy(runnerJobId = jobId)

    /** The candidate answered; [apiCost] (ADR 0065) defaults to [cost], the figure of a gateway call. */
    fun judging(
        output: String?,
        verification: AttemptVerification?,
        usage: TokenUsage,
        cost: Money,
        candidateLatencyMs: Long? = this.candidateLatencyMs,
        apiCost: Money = cost,
    ): Attempt = copy(
        status = AttemptStatus.JUDGING, output = output, verification = verification, usage = usage, cost = cost,
        candidateLatencyMs = candidateLatencyMs, apiCost = apiCost,
    )

    fun succeed(score: Double, passed: Boolean, verdict: JudgeVerdict, judgeCost: Money, now: Instant, judgeApiCost: Money = judgeCost): Attempt = copy(
        status = AttemptStatus.SUCCEEDED,
        score = score,
        passed = passed,
        judgeRationale = verdict.rationale,
        judgeCriteria = verdict.criteria,
        judgeCost = judgeCost,
        judgeApiCost = judgeApiCost,
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
