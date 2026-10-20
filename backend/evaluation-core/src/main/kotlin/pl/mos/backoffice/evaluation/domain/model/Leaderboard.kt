package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.Provider

/** Aggregated metrics of one model over a set of attempts. Nullable metrics are undefined without successful attempts. */
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
)

data class TaskLeaderboard(val taskId: EvalTaskId, val taskName: String, val rows: List<LeaderboardRow>)

/** Read model computed from attempts; never stored. */
data class Leaderboard(
    val benchmarkId: BenchmarkId,
    val overall: List<LeaderboardRow>,
    val byCategory: Map<TaskCategory, List<LeaderboardRow>>,
    val byDifficulty: Map<Difficulty, List<LeaderboardRow>>,
    val byTask: List<TaskLeaderboard>,
)
