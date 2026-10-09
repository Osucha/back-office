package pl.mos.backoffice.evaluation.adapter.out.persistence

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptVerification
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.JudgeCriterion
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.usage.Pricing
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/** Domain <-> entity mapping; JSONB payloads go through Jackson 3 as plain maps and lists. */
class EvaluationMappers(private val json: JsonMapper) {

    // ----- model profile -------------------------------------------------------------------------

    fun toEntity(p: ModelProfile) = ModelProfileEntity(
        id = p.id.value,
        displayName = p.displayName,
        provider = p.ref.provider,
        modelId = p.ref.modelId,
        options = json.writeValueAsString(
            mapOf(
                "temperature" to p.ref.options.temperature,
                "maxOutputTokens" to p.ref.options.maxOutputTokens,
                "reasoning" to p.ref.options.reasoning.name,
                "extra" to p.ref.options.extra,
            ),
        ),
        inputPerMTok = p.pricing.inputPerMTok,
        outputPerMTok = p.pricing.outputPerMTok,
        cacheReadPerMTok = p.pricing.cacheReadPerMTok,
        cacheWritePerMTok = p.pricing.cacheWritePerMTok,
        currency = p.pricing.currency,
        capabilities = json.writeValueAsString(p.capabilities.map { it.name }.sorted()),
        contextWindow = p.contextWindow,
        notes = p.notes,
        enabled = p.enabled,
        createdAt = p.createdAt,
        updatedAt = p.updatedAt,
        updatedByKind = p.updatedBy?.kind?.name,
        updatedById = p.updatedBy?.userId,
        updatedByName = p.updatedBy?.displayName,
    )

    fun toDomain(e: ModelProfileEntity): ModelProfile {
        val options = readMap(e.options)
        @Suppress("UNCHECKED_CAST")
        val extra = (options["extra"] as? Map<String, Any?>) ?: emptyMap()
        return ModelProfile(
            id = ModelProfileId(e.id),
            displayName = e.displayName,
            ref = ModelRef(
                provider = e.provider,
                modelId = e.modelId,
                options = ModelOptions(
                    temperature = (options["temperature"] as? Number)?.toDouble(),
                    maxOutputTokens = (options["maxOutputTokens"] as? Number)?.toInt(),
                    reasoning = (options["reasoning"] as? String)?.let { runCatching { Reasoning.valueOf(it) }.getOrNull() } ?: Reasoning.MEDIUM,
                    extra = extra,
                ),
            ),
            pricing = Pricing(e.inputPerMTok.normalized(), e.outputPerMTok.normalized(), e.cacheReadPerMTok?.normalized(), e.cacheWritePerMTok?.normalized(), e.currency),
            capabilities = readList(e.capabilities).mapNotNull { c -> runCatching { Capability.valueOf(c.toString()) }.getOrNull() }.toSet(),
            contextWindow = e.contextWindow,
            notes = e.notes,
            enabled = e.enabled,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
        )
    }

    // ----- task suite ------------------------------------------------------------------------------

    fun toEntity(s: TaskSuite) = TaskSuiteEntity(
        id = s.id.value,
        name = s.name,
        description = s.description,
        weightVerification = s.codingScoreWeights.verification,
        weightJudge = s.codingScoreWeights.judge,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        updatedByKind = s.updatedBy?.kind?.name,
        updatedById = s.updatedBy?.userId,
        updatedByName = s.updatedBy?.displayName,
    )

    fun toEntity(t: EvalTask, suiteId: TaskSuiteId, position: Int) = EvalTaskEntity(
        id = t.id.value,
        suiteId = suiteId.value,
        position = position,
        name = t.name,
        category = t.category,
        difficulty = t.difficulty,
        kind = t.kind,
        instructions = t.instructions,
        context = t.context,
        rubric = t.rubric,
        referenceAnswer = t.referenceAnswer,
        repoUrl = t.repository?.url,
        repoBaseRef = t.repository?.baseRef,
        repoSetupCommand = t.repository?.setupCommand,
        repoVerifyCommand = t.repository?.verifyCommand,
        timeoutSeconds = t.timeout.seconds,
        weight = t.weight,
    )

    fun toDomain(e: TaskSuiteEntity, tasks: List<EvalTaskEntity>) = TaskSuite(
        id = TaskSuiteId(e.id),
        name = e.name,
        description = e.description,
        codingScoreWeights = CodingScoreWeights(e.weightVerification, e.weightJudge),
        tasks = tasks.sortedBy { it.position }.map { toDomain(it) },
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    fun toDomain(e: EvalTaskEntity) = EvalTask(
        id = EvalTaskId(e.id),
        name = e.name,
        category = e.category,
        difficulty = e.difficulty,
        kind = e.kind,
        instructions = e.instructions,
        context = e.context,
        rubric = e.rubric,
        referenceAnswer = e.referenceAnswer,
        repository = e.repoUrl?.let { TaskRepository(it, e.repoBaseRef ?: "main", e.repoVerifyCommand ?: "", e.repoSetupCommand) },
        timeout = Duration.ofSeconds(e.timeoutSeconds),
        weight = e.weight,
    )

    // ----- benchmark -------------------------------------------------------------------------------

    fun toEntity(b: Benchmark) = BenchmarkEntity(
        id = b.id.value,
        name = b.name,
        suiteId = b.suiteId.value,
        taskIds = json.writeValueAsString(b.taskIds.map { it.toString() }),
        modelProfileIds = json.writeValueAsString(b.modelProfileIds.map { it.toString() }),
        judgeModelProfileId = b.judgeModelProfileId.value,
        repetitions = b.repetitions,
        codingHarness = b.codingHarness,
        status = b.status,
        createdAt = b.createdAt,
        startedAt = b.startedAt,
        finishedAt = b.finishedAt,
        createdByKind = b.createdBy?.kind?.name,
        createdById = b.createdBy?.userId,
        createdByName = b.createdBy?.displayName,
        updatedByKind = b.updatedBy?.kind?.name,
        updatedById = b.updatedBy?.userId,
        updatedByName = b.updatedBy?.displayName,
    )

    fun toDomain(e: BenchmarkEntity) = Benchmark(
        id = BenchmarkId(e.id),
        name = e.name,
        suiteId = TaskSuiteId(e.suiteId),
        taskIds = readList(e.taskIds).map { EvalTaskId(UUID.fromString(it.toString())) },
        modelProfileIds = readList(e.modelProfileIds).map { ModelProfileId(UUID.fromString(it.toString())) },
        judgeModelProfileId = ModelProfileId(e.judgeModelProfileId),
        repetitions = e.repetitions,
        codingHarness = e.codingHarness,
        status = e.status,
        createdAt = e.createdAt,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
        createdBy = actorOf(e.createdByKind, e.createdById, e.createdByName),
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    fun toEntity(a: Attempt) = BenchmarkAttemptEntity(
        id = a.id.value,
        benchmarkId = a.benchmarkId.value,
        taskId = a.taskId.value,
        taskName = a.taskName,
        category = a.category,
        difficulty = a.difficulty,
        kind = a.kind,
        modelProfileId = a.modelProfileId.value,
        modelDisplayName = a.modelDisplayName,
        provider = a.provider,
        repetition = a.repetition,
        status = a.status,
        output = a.output,
        verification = a.verification?.let { json.writeValueAsString(mapOf("command" to it.command, "exitCode" to it.exitCode, "outputTail" to it.outputTail)) },
        score = a.score,
        passed = a.passed,
        judgeRationale = a.judgeRationale,
        judgeCriteria = json.writeValueAsString(a.judgeCriteria.map { mapOf("name" to it.name, "score" to it.score, "comment" to it.comment) }),
        inputTokens = a.usage.inputTokens,
        outputTokens = a.usage.outputTokens,
        cacheReadTokens = a.usage.cacheReadTokens,
        cacheWriteTokens = a.usage.cacheWriteTokens,
        cost = a.cost.rounded().amount,
        judgeCost = a.judgeCost.rounded().amount,
        currency = a.cost.currency,
        durationMs = a.durationMs,
        runnerJobId = a.runnerJobId?.value,
        error = a.error,
        createdAt = a.createdAt,
        startedAt = a.startedAt,
        finishedAt = a.finishedAt,
    )

    fun toDomain(e: BenchmarkAttemptEntity) = Attempt(
        id = AttemptId(e.id),
        benchmarkId = BenchmarkId(e.benchmarkId),
        taskId = EvalTaskId(e.taskId),
        taskName = e.taskName,
        category = e.category,
        difficulty = e.difficulty,
        kind = e.kind,
        modelProfileId = ModelProfileId(e.modelProfileId),
        modelDisplayName = e.modelDisplayName,
        provider = e.provider,
        repetition = e.repetition,
        status = e.status,
        output = e.output,
        verification = e.verification?.let { readMap(it) }?.let {
            AttemptVerification(it["command"]?.toString() ?: "", (it["exitCode"] as? Number)?.toInt() ?: -1, it["outputTail"]?.toString() ?: "")
        },
        score = e.score,
        passed = e.passed,
        judgeRationale = e.judgeRationale,
        judgeCriteria = readList(e.judgeCriteria).mapNotNull { item ->
            (item as? Map<*, *>)?.let { JudgeCriterion(it["name"]?.toString() ?: "", (it["score"] as? Number)?.toDouble() ?: 0.0, it["comment"]?.toString()) }
        },
        usage = TokenUsage(e.inputTokens, e.outputTokens, e.cacheReadTokens, e.cacheWriteTokens),
        cost = Money(e.cost, e.currency),
        judgeCost = Money(e.judgeCost, e.currency),
        durationMs = e.durationMs,
        runnerJobId = e.runnerJobId?.let { RunnerJobId(it) },
        error = e.error,
        createdAt = e.createdAt,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
    )

    /** `*_by_*` columns back to an [Actor]; rows from before V201 have none. Unknown kinds read as SYSTEM. */
    private fun actorOf(kind: String?, id: UUID?, name: String?): Actor? = kind?.let {
        Actor(PrincipalKind.entries.firstOrNull { k -> k.name == it } ?: PrincipalKind.SYSTEM, id, name ?: "")
    }

    /** `numeric(18,6)` comes back as e.g. `2.000000`; prices are compared by value, so trailing zeros are dropped. */
    private fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    @Suppress("UNCHECKED_CAST")
    private fun readMap(text: String): Map<String, Any?> = json.readValue(text, Map::class.java) as Map<String, Any?>

    private fun readList(text: String): List<Any?> = json.readValue(text, List::class.java)
}
