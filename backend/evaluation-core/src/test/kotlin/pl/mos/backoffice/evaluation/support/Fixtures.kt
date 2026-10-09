package pl.mos.backoffice.evaluation.support

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** A clock that advances by a fixed step on every read, so durations are deterministic and non-zero. */
class SteppingClock(start: Instant = Instant.parse("2026-09-26T10:00:00Z"), private val stepMillis: Long = 1000) : Clock {
    private val current = AtomicReference(start)
    override fun now(): Instant = current.getAndUpdate { it.plusMillis(stepMillis) }
}

object Fixtures {
    val now: Instant = Instant.parse("2026-09-26T10:00:00Z")

    fun profile(name: String = "Model A", provider: Provider = Provider.ANTHROPIC, modelId: String = "claude-sonnet-5", input: String = "2", output: String = "10") =
        ModelProfile.create(
            displayName = name,
            ref = ModelRef(provider, modelId),
            pricing = Pricing(BigDecimal(input), BigDecimal(output)),
            now = now,
        )

    fun promptTask(name: String = "Explain", category: TaskCategory = TaskCategory.ANALYSIS, difficulty: Difficulty = Difficulty.EASY) = EvalTask(
        id = EvalTaskId.new(),
        name = name,
        category = category,
        difficulty = difficulty,
        kind = TaskKind.PROMPT,
        instructions = "Explain the bug.",
        rubric = "Correctness 0-10.",
    )

    fun codingTask(name: String = "Implement") = EvalTask(
        id = EvalTaskId.new(),
        name = name,
        category = TaskCategory.CODING,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.CODING_JOB,
        instructions = "Add the endpoint.",
        rubric = "Quality 0-10.",
        repository = TaskRepository(url = "https://git.example.com/sample.git", baseRef = "main", verifyCommand = "./gradlew test"),
    )

    fun suite(vararg tasks: EvalTask) = TaskSuite.create(name = "Suite", tasks = tasks.toList(), now = now)

    fun benchmark(suite: TaskSuite, models: List<ModelProfile>, judge: ModelProfile, repetitions: Int = 1) = Benchmark.create(
        name = "Bench",
        suiteId = suite.id,
        taskIds = emptyList(),
        modelProfileIds = models.map { it.id },
        judgeModelProfileId = judge.id,
        repetitions = repetitions,
        codingHarness = pl.mos.backoffice.shared.runner.AgentHarness.OPENCODE,
        now = now,
    )

    fun attempt(
        benchmarkId: BenchmarkId,
        task: EvalTask,
        model: ModelProfile,
        repetition: Int = 1,
        status: AttemptStatus = AttemptStatus.SUCCEEDED,
        score: Double? = 80.0,
        passed: Boolean? = true,
        cost: Money = Money.usd("0.01"),
        durationMs: Long? = 1000,
    ) = Attempt(
        id = AttemptId.new(),
        benchmarkId = benchmarkId,
        taskId = task.id,
        taskName = task.name,
        category = task.category,
        difficulty = task.difficulty,
        kind = task.kind,
        modelProfileId = model.id,
        modelDisplayName = model.displayName,
        provider = model.ref.provider,
        repetition = repetition,
        status = status,
        score = score,
        passed = passed,
        cost = cost,
        durationMs = durationMs,
        createdAt = now,
        startedAt = now,
        finishedAt = durationMs?.let { now.plusMillis(it) },
    )

    fun id() = ModelProfileId.new()
}
