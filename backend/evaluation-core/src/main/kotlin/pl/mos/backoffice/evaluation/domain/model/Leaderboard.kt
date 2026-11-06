package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.Provider

/**
 * Aggregated metrics of one candidate (ADR 0059: an agent with a model) over a set of attempts. Nullable metrics are
 * undefined without successful attempts.
 */
data class LeaderboardRow(
    val modelProfileId: ModelProfileId,
    val modelDisplayName: String,
    val provider: Provider,
    val attempts: Int,
    val meanScore: Double?,
    val passRate: Double?,
    val meanCost: Money,
    val totalCost: Money,
    val scorePerDollar: Double?,
    /** ADR 0046: of the candidate's own time in succeeded attempts; [latencySamples] says over how many. */
    val p50LatencyMs: Long?,
    val p95LatencyMs: Long?,
    val latencySamples: Int = 0,
    /** Standard deviation of the score across repetitions of the same task, averaged over tasks. */
    val stability: Double?,
    /** ADR 0059: the agent and model of the row. */
    val candidate: CandidateRef = CandidateRef("", "", null, modelProfileId, modelDisplayName),
    /** ADR 0059: tasks of the benchmark this candidate cannot do (overall rows only). */
    val notApplicable: Int = 0,
    /** ADR 0059: [totalCost] / passed attempts; null without a passed attempt. */
    val costPerAccepted: Money? = null,
)

data class TaskLeaderboard(val taskId: EvalTaskId, val taskName: String, val rows: List<LeaderboardRow>)

/**
 * ADR 0059: a candidate of a benchmark as the overall leaderboard needs it besides its attempts: how many selected tasks
 * it can do ([applicable]) and cannot do ([notApplicable]), and the provider of its model.
 */
data class LeaderboardCandidate(val ref: CandidateRef, val provider: Provider, val applicable: Int, val notApplicable: Int)

/** Read model computed from attempts; never stored. */
data class Leaderboard(
    val benchmarkId: BenchmarkId,
    val overall: List<LeaderboardRow>,
    val byCategory: Map<TaskCategory, List<LeaderboardRow>>,
    val byDifficulty: Map<Difficulty, List<LeaderboardRow>>,
    val byTask: List<TaskLeaderboard>,
)
