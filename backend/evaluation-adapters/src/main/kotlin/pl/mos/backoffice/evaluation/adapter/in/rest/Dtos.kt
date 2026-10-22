package pl.mos.backoffice.evaluation.adapter.`in`.rest

import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.security.PrincipalKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import pl.mos.backoffice.shared.runner.RunnerTarget

/* Request and response bodies of the model-profiles, task-suites and benchmarks tags in docs/api/openapi.yaml. */

data class MoneyDto(val amount: BigDecimal, val currency: String = "USD")

/** `ActorRef` schema: who created or last changed the entity (ADR 0017). */
data class ActorRefDto(val kind: PrincipalKind, val userId: UUID?, val displayName: String)

data class TokenUsageDto(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long)

data class ModelOptionsDto(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: Reasoning = Reasoning.MEDIUM,
    val extra: Map<String, Any?> = emptyMap(),
)

/** [connection] (ADR 0045): key of the provider connection serving the model; null = the provider's default. */
data class ModelRefDto(val provider: Provider, val modelId: String, val options: ModelOptionsDto = ModelOptionsDto(), val connection: String? = null)

data class PricingDto(
    val inputPerMTok: BigDecimal,
    val outputPerMTok: BigDecimal,
    val cacheReadPerMTok: BigDecimal? = null,
    val cacheWritePerMTok: BigDecimal? = null,
    val currency: String = "USD",
)

data class ModelProfileInputDto(
    val displayName: String,
    val ref: ModelRefDto,
    val pricing: PricingDto,
    val capabilities: Set<Capability> = emptySet(),
    val contextWindow: Int? = null,
    val notes: String? = null,
    val enabled: Boolean = true,
    val runtime: ModelRuntime = ModelRuntime.API,
    /** ADR 0050 step 2: create a personal profile of the caller (a login profile always is one). */
    val personal: Boolean = false,
)

data class ModelProfileDto(
    val id: UUID,
    val displayName: String,
    val ref: ModelRefDto,
    val pricing: PricingDto,
    val capabilities: Set<Capability>,
    val contextWindow: Int?,
    val notes: String?,
    val enabled: Boolean,
    val providerConfigured: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
    val runtime: ModelRuntime = ModelRuntime.API,
    val personal: Boolean = false,
)

/** ADR 0050: one model for one use (same shape as the projects' model options). */
data class ModelOptionDto(
    val profileId: UUID,
    val displayName: String,
    val provider: String,
    val modelId: String,
    val runtime: String,
    val route: String,
    val personal: Boolean,
    val usable: Boolean,
    val problem: String?,
    val note: String?,
) {
    companion object {
        fun of(o: pl.mos.backoffice.shared.llm.ModelOption) = ModelOptionDto(
            o.profileId.value, o.displayName, o.ref.provider.name, o.ref.modelId, o.runtime.name, o.route.name, o.personal, o.usable, o.problem, o.note,
        )
    }
}

/** ADR 0050 step 2: the default model per kind of step, the platform's and the caller's own. */
data class ModelDefaultsDto(val platform: Map<String, ModelProfileDto>, val mine: Map<String, ModelProfileDto>)

data class ModelDefaultInputDto(val stepKind: pl.mos.backoffice.shared.llm.StepKind, val profileId: UUID? = null)

data class ModelTestResultDto(val ok: Boolean, val latencyMs: Long, val usage: TokenUsageDto, val cost: MoneyDto, val sample: String?, val error: String?)

data class PreflightInputDto(val modelProfileIds: List<UUID> = emptyList())

data class PreflightResultDto(val modelProfileId: UUID, val displayName: String, val ok: Boolean, val latencyMs: Long, val error: String?)

data class ProviderStatusDto(val provider: Provider, val configured: Boolean, val baseUrl: String?)

data class TaskRepositoryDto(
    val url: String,
    val baseRef: String = "main",
    val setupCommand: String? = null,
    val verifyCommand: String,
    val acceptance: TaskAcceptanceDto? = null,
)

/** ADR 0047: hidden files (path → content), protected paths and the no-op check of a coding task. */
data class TaskAcceptanceDto(val files: Map<String, String> = emptyMap(), val protectedPaths: List<String> = emptyList(), val requireChanges: Boolean = true)

data class EvalTaskInputDto(
    val name: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val kind: TaskKind,
    val instructions: String,
    val context: String? = null,
    val rubric: String,
    val referenceAnswer: String? = null,
    val repository: TaskRepositoryDto? = null,
    val timeoutSeconds: Long = 900,
    val weight: Double = 1.0,
)

data class EvalTaskDto(
    val id: UUID,
    val name: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val kind: TaskKind,
    val instructions: String,
    val context: String?,
    val rubric: String,
    val referenceAnswer: String?,
    val repository: TaskRepositoryDto?,
    val timeoutSeconds: Long,
    val weight: Double,
)

data class CodingScoreWeightsDto(val verification: Double = 0.6, val judge: Double = 0.4)

data class TaskSuiteInputDto(val name: String, val description: String? = null, val codingScoreWeights: CodingScoreWeightsDto = CodingScoreWeightsDto())

data class TaskSuiteSummaryDto(
    val id: UUID,
    val name: String,
    val description: String?,
    val codingScoreWeights: CodingScoreWeightsDto,
    val taskCount: Int,
    val createdAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

data class TaskSuiteDto(
    val id: UUID,
    val name: String,
    val description: String?,
    val codingScoreWeights: CodingScoreWeightsDto,
    val taskCount: Int,
    val createdAt: Instant,
    val updatedBy: ActorRefDto? = null,
    val tasks: List<EvalTaskDto>,
)

data class BenchmarkInputDto(
    val name: String,
    val suiteId: UUID,
    val modelProfileIds: List<UUID>,
    val judgeModelProfileId: UUID,
    val repetitions: Int = 1,
    val codingHarness: AgentHarness = AgentHarness.OPENCODE,
    val taskIds: List<UUID> = emptyList(),
    val runnerTarget: RunnerTarget = RunnerTarget.SHARED,
)

data class ExecutionTargetDto(val target: RunnerTarget, val pools: Int, val onlineRunners: Int, val localLogin: Boolean = false)

data class ProgressDto(val total: Int, val done: Int, val failed: Int)

data class BenchmarkSummaryDto(
    val id: UUID,
    val name: String,
    val suiteId: UUID,
    val suiteName: String?,
    val modelProfileIds: List<UUID>,
    val judgeModelProfileId: UUID,
    val repetitions: Int,
    val codingHarness: AgentHarness,
    val taskIds: List<UUID>,
    val status: BenchmarkStatus,
    val progress: ProgressDto,
    val totalCost: MoneyDto,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdBy: ActorRefDto? = null,
    val runnerTarget: RunnerTarget = RunnerTarget.SHARED,
)

data class BenchmarkDto(
    val id: UUID,
    val name: String,
    val suiteId: UUID,
    val suiteName: String?,
    val modelProfileIds: List<UUID>,
    val judgeModelProfileId: UUID,
    val repetitions: Int,
    val codingHarness: AgentHarness,
    val taskIds: List<UUID>,
    val status: BenchmarkStatus,
    val progress: ProgressDto,
    val totalCost: MoneyDto,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdBy: ActorRefDto? = null,
    val runnerTarget: RunnerTarget = RunnerTarget.SHARED,
    val models: List<ModelProfileDto>,
    val judge: ModelProfileDto?,
)

data class AttemptDto(
    val id: UUID,
    val taskId: UUID,
    val taskName: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val modelProfileId: UUID,
    val modelDisplayName: String,
    val repetition: Int,
    val status: AttemptStatus,
    val kind: TaskKind,
    val score: Double?,
    val passed: Boolean?,
    val usage: TokenUsageDto,
    val cost: MoneyDto,
    val durationMs: Long?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class JudgeCriterionDto(val name: String, val score: Double, val comment: String?)

data class VerificationDto(val command: String, val exitCode: Int, val outputTail: String)

data class AttemptDetailDto(
    val id: UUID,
    val taskId: UUID,
    val taskName: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val modelProfileId: UUID,
    val modelDisplayName: String,
    val repetition: Int,
    val status: AttemptStatus,
    val score: Double?,
    val passed: Boolean?,
    val usage: TokenUsageDto,
    val cost: MoneyDto,
    val judgeCost: MoneyDto,
    val durationMs: Long?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val output: String?,
    val judgeRationale: String?,
    val judgeCriteria: List<JudgeCriterionDto>,
    val verification: VerificationDto?,
    val runnerJobId: UUID?,
    val error: String?,
)

data class LeaderboardRowDto(
    val modelProfileId: UUID,
    val modelDisplayName: String,
    val provider: Provider,
    val attempts: Int,
    val meanScore: Double?,
    val passRate: Double?,
    val meanCost: MoneyDto,
    val totalCost: MoneyDto,
    val scorePerDollar: Double?,
    val p50LatencyMs: Long?,
    val p95LatencyMs: Long?,
    val latencySamples: Int,
    val stability: Double?,
)

data class TaskLeaderboardDto(val taskId: UUID, val taskName: String, val rows: List<LeaderboardRowDto>)

data class LeaderboardDto(
    val benchmarkId: UUID,
    val overall: List<LeaderboardRowDto>,
    val byCategory: Map<String, List<LeaderboardRowDto>>,
    val byDifficulty: Map<String, List<LeaderboardRowDto>>,
    val byTask: List<TaskLeaderboardDto>,
)
