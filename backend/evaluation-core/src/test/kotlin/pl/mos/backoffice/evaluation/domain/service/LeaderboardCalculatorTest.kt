package pl.mos.backoffice.evaluation.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.CandidateRef
import pl.mos.backoffice.evaluation.domain.model.LeaderboardCandidate
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentHarness

class LeaderboardCalculatorTest {

    private val benchmarkId = BenchmarkId.new()
    private val a = Fixtures.profile("A", Provider.ANTHROPIC)
    private val b = Fixtures.profile("B", Provider.OPENAI, "gpt-5")
    private val analysis = Fixtures.promptTask("analysis", TaskCategory.ANALYSIS, Difficulty.EASY)
    private val review = Fixtures.promptTask("review", TaskCategory.REVIEW, Difficulty.HARD)

    @Test
    fun `computes mean score, pass rate, costs, score per dollar and latency percentiles`() {
        val attempts = listOf(
            Fixtures.attempt(benchmarkId, analysis, a, score = 80.0, passed = true, cost = Money.usd("0.02"), durationMs = 1000),
            Fixtures.attempt(benchmarkId, analysis, a, repetition = 2, score = 60.0, passed = false, cost = Money.usd("0.04"), durationMs = 3000),
            Fixtures.attempt(benchmarkId, review, a, score = 100.0, passed = true, cost = Money.usd("0.06"), durationMs = 2000),
            Fixtures.attempt(benchmarkId, review, a, repetition = 2, status = AttemptStatus.FAILED, score = null, passed = null, cost = Money.usd("0.08"), durationMs = 9000),
        )

        val row = LeaderboardCalculator.rows(attempts).single()

        assertThat(row.attempts).isEqualTo(4)
        assertThat(row.meanScore).isCloseTo(80.0, within(1e-9))
        assertThat(row.passRate).isCloseTo(0.5, within(1e-9))
        assertThat(row.totalCost).isEqualTo(Money.usd("0.20"))
        assertThat(row.meanCost).isEqualTo(Money.usd("0.05"))
        assertThat(row.scorePerDollar).isCloseTo(1600.0, within(1e-6))
        assertThat(row.p50LatencyMs).isEqualTo(2000)
        // the failed attempt (9000) does not count (ADR 0046)
        assertThat(row.p95LatencyMs).isEqualTo(3000)
        assertThat(row.latencySamples).isEqualTo(3)
    }

    @Test
    fun `ADR 0065 - API figures cover failed and timed-out attempts, a subscription candidate is flagged`() {
        val attempts = listOf(
            Fixtures.attempt(benchmarkId, analysis, a, score = 80.0, passed = true, cost = Money.ZERO).copy(apiCost = Money.usd("0.30")),
            Fixtures.attempt(benchmarkId, review, a, score = 40.0, passed = false, cost = Money.ZERO).copy(apiCost = Money.usd("0.50")),
            Fixtures.attempt(benchmarkId, review, a, repetition = 2, status = AttemptStatus.TIMED_OUT, score = null, passed = null, cost = Money.ZERO)
                .copy(apiCost = Money.usd("0.40")),
            Fixtures.attempt(benchmarkId, analysis, b, score = 90.0, passed = true, cost = Money.usd("0.10")),
        )

        val rows = LeaderboardCalculator.rows(attempts, subscriptionModels = setOf(a.id)).associateBy { it.modelProfileId }

        val sub = rows.getValue(a.id)
        assertThat(sub.subscription).isTrue()
        assertThat(sub.totalCost).isEqualByComparingTo(Money.ZERO)
        assertThat(sub.costPerAccepted!!).isEqualByComparingTo(Money.ZERO)
        assertThat(sub.scorePerDollar).isNull()
        assertThat(sub.totalApiCost).isEqualTo(Money.usd("1.20"))
        assertThat(sub.meanApiCost).isEqualTo(Money.usd("0.40"))
        // one passed attempt pays for the rejected and the timed-out one
        assertThat(sub.apiCostPerAccepted).isEqualTo(Money.usd("1.20"))
        // mean score 60 (succeeded only) per 0.40 USD
        assertThat(sub.scorePerApiDollar).isCloseTo(150.0, within(1e-6))

        val api = rows.getValue(b.id)
        assertThat(api.subscription).isFalse()
        assertThat(api.totalApiCost).describedAs("an API attempt: apiCost = cost").isEqualTo(Money.usd("0.10"))
        assertThat(api.apiCostPerAccepted).isEqualTo(api.costPerAccepted)
        assertThat(api.scorePerApiDollar).isEqualTo(api.scorePerDollar)
    }

    @Test
    fun `ADR 0065 - without a passed attempt there is no API cost per accepted result`() {
        val row = LeaderboardCalculator.rows(
            listOf(Fixtures.attempt(benchmarkId, analysis, a, score = 20.0, passed = false).copy(apiCost = Money.usd("0.20"))),
        ).single()
        assertThat(row.apiCostPerAccepted).isNull()
        assertThat(row.totalApiCost).isEqualTo(Money.usd("0.20"))
        assertThat(row.subscription).isFalse()
    }

    @Test
    fun `stability is the standard deviation of scores across repetitions averaged over tasks`() {
        val attempts = listOf(
            Fixtures.attempt(benchmarkId, analysis, a, repetition = 1, score = 70.0),
            Fixtures.attempt(benchmarkId, analysis, a, repetition = 2, score = 90.0),
            Fixtures.attempt(benchmarkId, review, a, repetition = 1, score = 50.0),
            Fixtures.attempt(benchmarkId, review, a, repetition = 2, score = 50.0),
        )
        val row = LeaderboardCalculator.rows(attempts).single()
        // task 1: stddev of {70, 90} = 10; task 2: 0; mean = 5
        assertThat(row.stability).isCloseTo(5.0, within(1e-9))
    }

    @Test
    fun `stability is null without repetitions and score metrics are null without successes`() {
        val single = LeaderboardCalculator.rows(listOf(Fixtures.attempt(benchmarkId, analysis, a))).single()
        assertThat(single.stability).isNull()

        val failedOnly = LeaderboardCalculator.rows(
            listOf(Fixtures.attempt(benchmarkId, analysis, a, status = AttemptStatus.FAILED, score = null, passed = null)),
        ).single()
        assertThat(failedOnly.meanScore).isNull()
        assertThat(failedOnly.scorePerDollar).isNull()
        assertThat(failedOnly.passRate).isEqualTo(0.0)
    }

    @Test
    fun `builds breakdowns by category, difficulty and task and sorts rows by mean score`() {
        val attempts = listOf(
            Fixtures.attempt(benchmarkId, analysis, a, score = 60.0),
            Fixtures.attempt(benchmarkId, analysis, b, score = 90.0),
            Fixtures.attempt(benchmarkId, review, a, score = 70.0),
            Fixtures.attempt(benchmarkId, review, b, status = AttemptStatus.PENDING, score = null, passed = null, durationMs = null),
        )

        val board = LeaderboardCalculator.leaderboard(benchmarkId, attempts)

        assertThat(board.overall.map { it.modelDisplayName }).containsExactly("B", "A")
        assertThat(board.overall.first { it.modelDisplayName == "A" }.meanScore).isCloseTo(65.0, within(1e-9))
        assertThat(board.byCategory.keys).containsExactly(TaskCategory.ANALYSIS, TaskCategory.REVIEW)
        assertThat(board.byCategory[TaskCategory.REVIEW]!!.map { it.modelDisplayName }).containsExactly("A")
        assertThat(board.byDifficulty[Difficulty.EASY]!!.first().modelDisplayName).isEqualTo("B")
        assertThat(board.byTask.map { it.taskName }).containsExactly("analysis", "review")
        assertThat(board.byTask.first { it.taskName == "analysis" }.rows).hasSize(2)
    }

    @Test
    fun `percentile uses nearest rank`() {
        val sorted = listOf(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L)
        assertThat(LeaderboardCalculator.percentile(sorted, 0.5)).isEqualTo(500L)
        assertThat(LeaderboardCalculator.percentile(sorted, 0.95)).isEqualTo(1000L)
        assertThat(LeaderboardCalculator.percentile(listOf(42L), 0.95)).isEqualTo(42L)
        assertThat(LeaderboardCalculator.percentile(emptyList(), 0.5)).isNull()
    }

    @Test
    fun `ADR 0059 one row per agent and model with the cost of an accepted result and the not applicable tasks`() {
        val coding = Fixtures.codingTask("implement")
        val attempts = listOf(
            Fixtures.attempt(benchmarkId, analysis, a, passed = true, cost = Money.usd("0.10")),
            Fixtures.attempt(benchmarkId, analysis, a, repetition = 2, passed = false, cost = Money.usd("0.30")),
            Fixtures.attempt(benchmarkId, coding, a, passed = false, cost = Money.usd("1.00")),
            Fixtures.attempt(benchmarkId, analysis, a, cost = Money.usd("0.05")).copy(agentKey = "analyst", agentName = "Analyst"),
        )
        val idle = CandidateRef("reviewer", "Reviewer", AgentHarness.CLAUDE_CODE, b.id, "B")
        val candidates = listOf(
            LeaderboardCandidate(CandidateRef("analyst", "Analyst", null, a.id, "A"), Provider.ANTHROPIC, applicable = 1, notApplicable = 1),
            LeaderboardCandidate(idle, Provider.OPENAI, applicable = 0, notApplicable = 2),
        )

        val board = LeaderboardCalculator.leaderboard(benchmarkId, attempts, AgentHarness.CLAUDE_CODE, candidates)

        assertThat(board.overall.map { it.candidate.label }).containsExactly("Analyst · A", "Claude Code · A", "Model answer · A", "Reviewer · B")
        val answer = board.overall.single { it.candidate.agentKey == "model-answer" }
        assertThat(answer.attempts).isEqualTo(2)
        assertThat(answer.costPerAccepted).describedAs("0.40 for one passed attempt").isEqualTo(Money.usd("0.40"))
        assertThat(board.overall.single { it.candidate.agentKey == "claude-code" }.costPerAccepted).isNull()
        assertThat(board.overall.single { it.candidate.agentKey == "analyst" }.notApplicable).isEqualTo(1)
        val empty = board.overall.last()
        assertThat(empty.attempts).isZero()
        assertThat(empty.notApplicable).isEqualTo(2)
        assertThat(empty.provider).isEqualTo(Provider.OPENAI)
        assertThat(board.byTask.first { it.taskName == "analysis" }.rows).hasSize(2)
    }
}
