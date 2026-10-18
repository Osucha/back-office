package pl.mos.backoffice.evaluation.adapter.`in`.rest

import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.Leaderboard
import pl.mos.backoffice.evaluation.domain.model.LeaderboardRow
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.usage.Pricing
import java.time.Duration

/** Pure DTO mapping for the evaluation controllers. */
object RestMappers {

    fun money(m: Money) = MoneyDto(m.rounded().amount, m.currency)
    fun usage(u: TokenUsage) = TokenUsageDto(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens)

    fun ref(r: ModelRef) = ModelRefDto(r.provider, r.modelId, ModelOptionsDto(r.options.temperature, r.options.maxOutputTokens, r.options.reasoning, r.options.extra), r.connection)
    fun ref(d: ModelRefDto) = ModelRef(
        d.provider, d.modelId, ModelOptions(d.options.temperature, d.options.maxOutputTokens, d.options.reasoning, d.options.extra),
        d.connection?.trim()?.ifEmpty { null },
    )

    fun pricing(p: Pricing) = PricingDto(p.inputPerMTok, p.outputPerMTok, p.cacheReadPerMTok, p.cacheWritePerMTok, p.currency)
    fun pricing(d: PricingDto) = Pricing(d.inputPerMTok, d.outputPerMTok, d.cacheReadPerMTok, d.cacheWritePerMTok, d.currency)

    fun profile(p: ModelProfile, providerConfigured: Boolean) = ModelProfileDto(
        id = p.id.value,
        displayName = p.displayName,
        ref = ref(p.ref),
        pricing = pricing(p.pricing),
        capabilities = p.capabilities,
        contextWindow = p.contextWindow,
        notes = p.notes,
        enabled = p.enabled,
        providerConfigured = providerConfigured,
        createdAt = p.createdAt,
        updatedAt = p.updatedAt,
        updatedBy = actor(p.updatedBy),
    )

    fun profileCommand(d: ModelProfileInputDto) = ManageModelProfiles.ProfileCommand(
        displayName = d.displayName,
        ref = ref(d.ref),
        pricing = pricing(d.pricing),
        capabilities = d.capabilities,
        contextWindow = d.contextWindow,
        notes = d.notes,
        enabled = d.enabled,
    )

    fun testResult(r: ManageModelProfiles.ModelTestResult) = ModelTestResultDto(r.ok, r.latencyMs, usage(r.usage), money(r.cost), r.sample, r.error)

    fun task(t: EvalTask) = EvalTaskDto(
        id = t.id.value,
        name = t.name,
        category = t.category,
        difficulty = t.difficulty,
        kind = t.kind,
        instructions = t.instructions,
        context = t.context,
        rubric = t.rubric,
        referenceAnswer = t.referenceAnswer,
        repository = t.repository?.let { TaskRepositoryDto(it.url, it.baseRef, it.setupCommand, it.verifyCommand) },
        timeoutSeconds = t.timeout.seconds,
        weight = t.weight,
    )

    fun taskCommand(d: EvalTaskInputDto) = ManageTaskSuites.TaskCommand(
        name = d.name,
        category = d.category,
        difficulty = d.difficulty,
        kind = d.kind,
        instructions = d.instructions,
        context = d.context,
        rubric = d.rubric,
        referenceAnswer = d.referenceAnswer,
        repository = d.repository?.let { TaskRepository(it.url, it.baseRef, it.verifyCommand, it.setupCommand) },
        timeout = Duration.ofSeconds(d.timeoutSeconds),
        weight = d.weight,
    )

    fun weights(w: CodingScoreWeights) = CodingScoreWeightsDto(w.verification, w.judge)

    fun suiteCommand(d: TaskSuiteInputDto) = ManageTaskSuites.SuiteCommand(d.name, d.description, CodingScoreWeights(d.codingScoreWeights.verification, d.codingScoreWeights.judge))

    fun actor(a: Actor?): ActorRefDto? = a?.let { ActorRefDto(it.kind, it.userId, it.displayName) }

    fun suiteSummary(s: TaskSuite) = TaskSuiteSummaryDto(
        id = s.id.value, name = s.name, description = s.description, codingScoreWeights = weights(s.codingScoreWeights),
        taskCount = s.tasks.size, createdAt = s.createdAt, updatedBy = actor(s.updatedBy),
    )

    fun suite(s: TaskSuite) = TaskSuiteDto(
        id = s.id.value, name = s.name, description = s.description, codingScoreWeights = weights(s.codingScoreWeights),
        taskCount = s.tasks.size, createdAt = s.createdAt, updatedBy = actor(s.updatedBy), tasks = s.tasks.map { task(it) },
    )

    fun benchmarkCommand(d: BenchmarkInputDto) = ManageBenchmarks.BenchmarkCommand(
        name = d.name,
        suiteId = TaskSuiteId(d.suiteId),
        modelProfileIds = d.modelProfileIds.map { ModelProfileId(it) },
        judgeModelProfileId = ModelProfileId(d.judgeModelProfileId),
        repetitions = d.repetitions,
        codingHarness = d.codingHarness,
        taskIds = d.taskIds.map { EvalTaskId(it) },
    )

    fun benchmarkSummary(d: ManageBenchmarks.BenchmarkDetails) = BenchmarkSummaryDto(
        id = d.benchmark.id.value,
        name = d.benchmark.name,
        suiteId = d.benchmark.suiteId.value,
        suiteName = d.suiteName,
        modelProfileIds = d.benchmark.modelProfileIds.map { it.value },
        judgeModelProfileId = d.benchmark.judgeModelProfileId.value,
        repetitions = d.benchmark.repetitions,
        codingHarness = d.benchmark.codingHarness,
        taskIds = d.benchmark.taskIds.map { it.value },
        status = d.benchmark.status,
        progress = ProgressDto(d.progress.total, d.progress.done, d.progress.failed),
        totalCost = money(d.totalCost),
        createdAt = d.benchmark.createdAt,
        startedAt = d.benchmark.startedAt,
        finishedAt = d.benchmark.finishedAt,
        createdBy = actor(d.benchmark.createdBy),
    )

    fun benchmark(d: ManageBenchmarks.BenchmarkDetails, configured: (ModelProfile) -> Boolean): BenchmarkDto {
        val s = benchmarkSummary(d)
        return BenchmarkDto(
            id = s.id, name = s.name, suiteId = s.suiteId, suiteName = s.suiteName, modelProfileIds = s.modelProfileIds,
            judgeModelProfileId = s.judgeModelProfileId, repetitions = s.repetitions, codingHarness = s.codingHarness, taskIds = s.taskIds,
            status = s.status, progress = s.progress, totalCost = s.totalCost, createdAt = s.createdAt, startedAt = s.startedAt, finishedAt = s.finishedAt,
            createdBy = s.createdBy,
            models =d.models.map { profile(it, configured(it)) },
            judge = d.judge?.let { profile(it, configured(it)) },
        )
    }

    fun attempt(a: Attempt) = AttemptDto(
        id = a.id.value, taskId = a.taskId.value, taskName = a.taskName, category = a.category, difficulty = a.difficulty,
        modelProfileId = a.modelProfileId.value, modelDisplayName = a.modelDisplayName, repetition = a.repetition, status = a.status,
        score = a.score, passed = a.passed, usage = usage(a.usage), cost = money(a.cost), durationMs = a.durationMs,
        startedAt = a.startedAt, finishedAt = a.finishedAt,
    )

    fun attemptDetail(a: Attempt) = AttemptDetailDto(
        id = a.id.value, taskId = a.taskId.value, taskName = a.taskName, category = a.category, difficulty = a.difficulty,
        modelProfileId = a.modelProfileId.value, modelDisplayName = a.modelDisplayName, repetition = a.repetition, status = a.status,
        score = a.score, passed = a.passed, usage = usage(a.usage), cost = money(a.cost), judgeCost = money(a.judgeCost), durationMs = a.durationMs,
        startedAt = a.startedAt, finishedAt = a.finishedAt, output = a.output, judgeRationale = a.judgeRationale,
        judgeCriteria = a.judgeCriteria.map { JudgeCriterionDto(it.name, it.score, it.comment) },
        verification = a.verification?.let { VerificationDto(it.command, it.exitCode, it.outputTail) },
        runnerJobId = a.runnerJobId?.value, error = a.error,
    )

    fun row(r: LeaderboardRow) = LeaderboardRowDto(
        modelProfileId = r.modelProfileId.value, modelDisplayName = r.modelDisplayName, provider = r.provider, attempts = r.attempts,
        meanScore = r.meanScore, passRate = r.passRate, meanCost = money(r.meanCost), totalCost = money(r.totalCost),
        scorePerDollar = r.scorePerDollar, p50LatencyMs = r.p50LatencyMs, p95LatencyMs = r.p95LatencyMs, stability = r.stability,
    )

    fun leaderboard(l: Leaderboard) = LeaderboardDto(
        benchmarkId = l.benchmarkId.value,
        overall = l.overall.map { row(it) },
        byCategory = l.byCategory.entries.associate { (k, v) -> k.name to v.map { row(it) } },
        byDifficulty = l.byDifficulty.entries.associate { (k, v) -> k.name to v.map { row(it) } },
        byTask = l.byTask.map { TaskLeaderboardDto(it.taskId.value, it.taskName, it.rows.map { r -> row(r) }) },
    )
}
