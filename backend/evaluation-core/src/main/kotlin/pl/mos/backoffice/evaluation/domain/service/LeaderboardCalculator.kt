package pl.mos.backoffice.evaluation.domain.service

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.CandidateRef
import pl.mos.backoffice.evaluation.domain.model.Leaderboard
import pl.mos.backoffice.evaluation.domain.model.LeaderboardCandidate
import pl.mos.backoffice.evaluation.domain.model.LeaderboardRow
import pl.mos.backoffice.evaluation.domain.model.TaskLeaderboard
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.runner.AgentHarness
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
 *
 * ADR 0059: one row per candidate (agent and model; attempts stored before it have the agent of a benchmark without
 * candidates, see [Attempt.candidate] with the benchmark's [AgentHarness] for coding tasks). `costPerAccepted` is the
 * total cost over passed attempts. The overall rows carry how many tasks a candidate cannot do; a candidate that can
 * do none of them gets an empty row so it is not silently missing.
 */
object LeaderboardCalculator {

    fun leaderboard(
        benchmarkId: BenchmarkId,
        attempts: List<Attempt>,
        codingHarness: AgentHarness = AgentHarness.OPENCODE,
        candidates: List<LeaderboardCandidate> = emptyList(),
    ): Leaderboard {
        val terminal = attempts.filter { it.status.terminal }
        return Leaderboard(
            benchmarkId = benchmarkId,
            overall = overall(rows(terminal, codingHarness), candidates),
            byCategory = terminal.groupBy { it.category }.mapValues { (_, a) -> rows(a, codingHarness) }.toSortedMap(),
            byDifficulty = terminal.groupBy { it.difficulty }.mapValues { (_, a) -> rows(a, codingHarness) }.toSortedMap(),
            byTask = terminal.groupBy { it.taskId }.map { (taskId, a) ->
                TaskLeaderboard(taskId, a.first().taskName, rows(a, codingHarness))
            }.sortedBy { it.taskName },
        )
    }

    /** One row per candidate, best mean score first. */
    fun rows(attempts: List<Attempt>, codingHarness: AgentHarness = AgentHarness.OPENCODE): List<LeaderboardRow> =
        attempts.filter { it.status.terminal }
            .groupBy { it.candidate(codingHarness).key }
            .map { (_, candidateAttempts) -> row(candidateAttempts.first().candidate(codingHarness), candidateAttempts) }
            .sortedWith(order)

    private val order: Comparator<LeaderboardRow> =
        compareByDescending<LeaderboardRow> { it.meanScore ?: -1.0 }.thenBy { it.candidate.label }

    /** [rows] with each candidate's not applicable count, plus empty rows for candidates that can do no task. */
    private fun overall(rows: List<LeaderboardRow>, candidates: List<LeaderboardCandidate>): List<LeaderboardRow> {
        if (candidates.isEmpty()) return rows
        val byKey = candidates.associateBy { it.ref.key }
        val counted = rows.map { row -> byKey[row.candidate.key]?.let { row.copy(notApplicable = it.notApplicable) } ?: row }
        val present = counted.map { it.candidate.key }.toSet()
        val empty = candidates.filter { it.applicable == 0 && it.ref.key !in present }.map(::emptyRow)
        return (counted + empty).sortedWith(order)
    }

    private fun emptyRow(c: LeaderboardCandidate) = LeaderboardRow(
        modelProfileId = c.ref.modelProfileId,
        modelDisplayName = c.ref.modelDisplayName,
        provider = c.provider,
        attempts = 0,
        meanScore = null,
        passRate = null,
        meanCost = Money.ZERO,
        totalCost = Money.ZERO,
        scorePerDollar = null,
        p50LatencyMs = null,
        p95LatencyMs = null,
        stability = null,
        candidate = c.ref,
        notApplicable = c.notApplicable,
    )

    fun row(candidate: CandidateRef, attempts: List<Attempt>): LeaderboardRow {
        val first = attempts.first()
        val succeeded = attempts.filter { it.status == AttemptStatus.SUCCEEDED }
        val scores = succeeded.mapNotNull { it.score }
        val meanScore = scores.takeIf { it.isNotEmpty() }?.average()
        val passed = attempts.count { it.passed == true }
        val passRate = if (attempts.isEmpty()) null else passed.toDouble() / attempts.size
        val totalCost = Money.sum(attempts.map { it.cost })
        val meanCost = if (attempts.isEmpty()) Money.ZERO else totalCost / attempts.size
        val scorePerDollar = if (meanScore != null && !meanCost.isZero()) {
            BigDecimal.valueOf(meanScore).divide(meanCost.amount, 6, RoundingMode.HALF_UP).toDouble()
        } else {
            null
        }
        // ADR 0046: the candidate's own time; failed attempts (often seconds) and the judge would distort it
        val latencies = succeeded.mapNotNull { it.candidateLatencyMs }.sorted()
        return LeaderboardRow(
            modelProfileId = candidate.modelProfileId,
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
            latencySamples = latencies.size,
            stability = stability(succeeded),
            candidate = candidate,
            // ADR 0059: failed and rejected attempts are paid by the accepted ones
            costPerAccepted = if (passed == 0) null else totalCost / passed,
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
