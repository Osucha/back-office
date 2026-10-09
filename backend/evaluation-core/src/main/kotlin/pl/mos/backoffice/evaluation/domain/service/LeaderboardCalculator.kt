package pl.mos.backoffice.evaluation.domain.service

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.Leaderboard
import pl.mos.backoffice.evaluation.domain.model.LeaderboardRow
import pl.mos.backoffice.evaluation.domain.model.TaskLeaderboard
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Pure aggregation of attempts into leaderboard rows (docs/architecture/04-model-evaluation.md).
 *
 * Only terminal attempts count. Score metrics use `SUCCEEDED` attempts; `passRate` counts failed and
 * timed-out attempts as not passed so a model that crashes is not rewarded. Cost and latency cover every
 * terminal attempt because failed calls are paid for too.
 */
object LeaderboardCalculator {

    fun leaderboard(benchmarkId: BenchmarkId, attempts: List<Attempt>): Leaderboard {
        val terminal = attempts.filter { it.status.terminal }
        return Leaderboard(
            benchmarkId = benchmarkId,
            overall = rows(terminal),
            byCategory = terminal.groupBy { it.category }.mapValues { (_, a) -> rows(a) }.toSortedMap(),
            byDifficulty = terminal.groupBy { it.difficulty }.mapValues { (_, a) -> rows(a) }.toSortedMap(),
            byTask = terminal.groupBy { it.taskId }.map { (taskId, a) ->
                TaskLeaderboard(taskId, a.first().taskName, rows(a))
            }.sortedBy { it.taskName },
        )
    }

    /** One row per model, best mean score first. */
    fun rows(attempts: List<Attempt>): List<LeaderboardRow> =
        attempts.filter { it.status.terminal }
            .groupBy { it.modelProfileId }
            .map { (modelId, modelAttempts) -> row(modelId, modelAttempts) }
            .sortedWith(compareByDescending<LeaderboardRow> { it.meanScore ?: -1.0 }.thenBy { it.modelDisplayName })

    fun row(modelId: ModelProfileId, attempts: List<Attempt>): LeaderboardRow {
        val first = attempts.first()
        val succeeded = attempts.filter { it.status == AttemptStatus.SUCCEEDED }
        val scores = succeeded.mapNotNull { it.score }
        val meanScore = scores.takeIf { it.isNotEmpty() }?.average()
        val passRate = if (attempts.isEmpty()) null else attempts.count { it.passed == true }.toDouble() / attempts.size
        val totalCost = Money.sum(attempts.map { it.cost })
        val meanCost = if (attempts.isEmpty()) Money.ZERO else totalCost / attempts.size
        val scorePerDollar = if (meanScore != null && !meanCost.isZero()) {
            BigDecimal.valueOf(meanScore).divide(meanCost.amount, 6, RoundingMode.HALF_UP).toDouble()
        } else {
            null
        }
        val latencies = attempts.mapNotNull { it.durationMs }.sorted()
        return LeaderboardRow(
            modelProfileId = modelId,
            modelDisplayName = first.modelDisplayName,
            provider = first.provider,
            attempts = attempts.size,
            meanScore = meanScore,
            passRate = passRate,
            meanCost = meanCost,
            totalCost = totalCost,
            scorePerDollar = scorePerDollar,
            p50LatencyMs = percentile(latencies, 0.50),
            p95LatencyMs = percentile(latencies, 0.95),
            stability = stability(succeeded),
        )
    }

    /** Nearest-rank percentile of a sorted list. */
    fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * Population standard deviation of scores across repetitions of the same task, averaged over the
     * tasks that have at least two scored repetitions. Null when no task was repeated.
     */
    fun stability(succeeded: List<Attempt>): Double? {
        val perTask = succeeded.groupBy { it.taskId }
            .values
            .map { group -> group.mapNotNull { it.score } }
            .filter { it.size >= 2 }
        if (perTask.isEmpty()) return null
        return perTask.map { stddev(it) }.average()
    }

    fun stddev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
