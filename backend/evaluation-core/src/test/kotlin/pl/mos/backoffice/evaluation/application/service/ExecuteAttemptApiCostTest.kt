package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.evaluation.support.SteppingClock
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.OwnLoginLlm
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerDiffStat
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerVerification
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration

/**
 * ADR 0065: every attempt has its cost at API prices next to the spent cost; subscription work (Claude Code login) is
 * spent-free but worth the harness's estimate, else its tokens at the API prices of its Claude family; the candidate's
 * time excludes queueing for a runner.
 */
class ExecuteAttemptApiCostTest {

    private val benchmarks = InMemoryBenchmarkRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val profiles = InMemoryModelProfileRepository()
    private val gateway = mockk<LlmGateway>()
    private val runner = mockk<AgentRunner>(relaxed = true)
    private val ownLogin = mockk<OwnLoginLlm>()
    private val service = ExecuteAttemptService(benchmarks, suites, profiles, gateway, runner, SteppingClock(), ownLogin)

    /** The enabled API profile of the Sonnet family: its prices value a Sonnet login model's tokens. */
    private val sonnetApi = Fixtures.profile("Sonnet API", modelId = "claude-sonnet-5", input = "3", output = "15")
    private val sonnetLogin = login("Sonnet (my login)", "claude-sonnet-5")
    private val opusLogin = login("Opus (my login)", "claude-opus-5")
    private val apiJudge = Fixtures.profile("Judge", modelId = "gpt-judge", provider = Provider.OPENAI, input = "10", output = "50")
    private val promptTask = Fixtures.promptTask("analysis")
    private val codingTask = Fixtures.codingTask("implement")
    private val suite = Fixtures.suite(promptTask, codingTask)

    private fun login(name: String, modelId: String) = ModelProfile.create(
        displayName = name, ref = ModelRef(Provider.ANTHROPIC, modelId), pricing = Pricing.FREE, now = Fixtures.now, runtime = ModelRuntime.CLAUDE_CODE_LOGIN,
    )

    private fun start(candidate: ModelProfile, judge: ModelProfile): Benchmark {
        listOf(sonnetApi, sonnetLogin, opusLogin, apiJudge).forEach { profiles.save(it) }
        suites.save(suite)
        val benchmark = benchmarks.save(Fixtures.benchmark(suite, listOf(candidate), judge).start(Fixtures.now))
        benchmarks.saveAttempts(BenchmarkExecutionPlanner.plan(benchmark, suite, listOf(candidate), Fixtures.now))
        return benchmark
    }

    private val verdict = mapOf("criteria" to listOf(mapOf("name" to "Quality", "score" to 8, "comment" to "ok")))

    private fun answer(model: ModelRef, content: String, usage: TokenUsage, reported: String?, structured: Map<String, Any?>? = null, latency: Duration = Duration.ofSeconds(2)) =
        LlmResult(content = content, structured = structured, usage = usage, latency = latency, modelRef = model, reportedCostUsd = reported?.let(::BigDecimal))

    private fun codingResult(status: RunnerJobStatus, usage: TokenUsage, reported: String?, startedSecondsBefore: Long? = 300) = RunnerResult(
        status = status, summary = "done", branch = "eval/x", commitSha = "abc", diffStat = RunnerDiffStat(1, 2, 0),
        verification = if (status == RunnerJobStatus.SUCCEEDED) RunnerVerification("./gradlew test", 0, "ok") else null,
        artifacts = emptyList(), diff = "diff --git a/A.kt b/A.kt\n+x\n", usage = usage, reportedCostUsd = reported?.let(::BigDecimal),
        error = null, finishedAt = Fixtures.now.plusSeconds(7200), subscription = true,
        startedAt = startedSecondsBefore?.let { Fixtures.now.plusSeconds(7200 - it) },
    )

    private fun runCoding(benchmark: Benchmark, result: RunnerResult) = benchmarks.findAttempts(benchmark.id).first { it.taskId == codingTask.id }.also { attempt ->
        val jobId = RunnerJobId.new()
        every { runner.submit(any(), any()) } returns jobId
        every { runner.result(jobId) } returns result
        service.execute(attempt.id)
        service.execute(attempt.id)
    }.let { benchmarks.findAttempt(it.id)!! }

    @Test
    fun `a subscription coding job spends nothing, is worth Claude Code's estimate and took the job's own time`() {
        val benchmark = start(sonnetLogin, apiJudge)
        every { gateway.chat(apiJudge.ref, any()) } returns answer(apiJudge.ref, "{}", TokenUsage(2000, 200), null, verdict)

        // the attempt was submitted at about Fixtures.now and the job waited almost two hours for a runner slot
        val stored = runCoding(benchmark, codingResult(RunnerJobStatus.SUCCEEDED, TokenUsage(1_000, 100), "0.42"))

        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.cost).isEqualByComparingTo(Money.ZERO)
        assertThat(stored.apiCost).isEqualTo(Money.usd("0.42"))
        assertThat(stored.candidateLatencyMs).describedAs("from the job's start on the runner, queueing excluded").isEqualTo(300_000L)
        // an API judge: both amounts are its pricing (2000 * 10 + 200 * 50) / 1e6
        assertThat(stored.judgeCost).isEqualTo(Money.usd("0.03"))
        assertThat(stored.judgeApiCost).isEqualTo(stored.judgeCost)
    }

    @Test
    fun `without an estimate the tokens are priced like the API profile of the same Claude family`() {
        val benchmark = start(sonnetLogin, apiJudge)
        every { gateway.chat(apiJudge.ref, any()) } returns answer(apiJudge.ref, "{}", TokenUsage(0, 0), null, verdict)

        val stored = runCoding(benchmark, codingResult(RunnerJobStatus.SUCCEEDED, TokenUsage(100_000, 10_000), null, startedSecondsBefore = null))

        assertThat(stored.cost).isEqualByComparingTo(Money.ZERO)
        // 100 000 * 3 / 1e6 + 10 000 * 15 / 1e6 = 0.30 + 0.15
        assertThat(stored.apiCost).isEqualTo(Money.usd("0.45"))
        assertThat(stored.candidateLatencyMs).describedAs("no start reported: from submitting the job").isPositive()
    }

    @Test
    fun `a timed-out subscription job keeps its usage and estimate`() {
        val benchmark = start(sonnetLogin, apiJudge)

        val stored = runCoding(benchmark, codingResult(RunnerJobStatus.TIMED_OUT, TokenUsage(40_000, 4_000), "1.25"))

        assertThat(stored.status).isEqualTo(AttemptStatus.TIMED_OUT)
        assertThat(stored.usage).isEqualTo(TokenUsage(40_000, 4_000))
        assertThat(stored.cost).isEqualByComparingTo(Money.ZERO)
        assertThat(stored.apiCost).isEqualTo(Money.usd("1.25"))
    }

    @Test
    fun `a failed job without verification keeps its tokens at API prices`() {
        val benchmark = start(sonnetLogin, apiJudge)

        val stored = runCoding(benchmark, codingResult(RunnerJobStatus.FAILED, TokenUsage(10_000, 1_000), null))

        assertThat(stored.status).isEqualTo(AttemptStatus.FAILED)
        // 10 000 * 3 / 1e6 + 1 000 * 15 / 1e6
        assertThat(stored.apiCost).isEqualTo(Money.usd("0.045"))
    }

    @Test
    fun `a login candidate and a login judge on a prompt task spend nothing and carry their estimates`() {
        val benchmark = start(sonnetLogin, opusLogin)
        every { ownLogin.chat(sonnetLogin.ref, any()) } returns answer(sonnetLogin.ref, "The bug is X.", TokenUsage(1000, 500), "0.05", latency = Duration.ofSeconds(12))
        every { ownLogin.chat(opusLogin.ref, any()) } returns answer(opusLogin.ref, "{}", TokenUsage(3000, 300), "0.02", verdict)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.cost).isEqualByComparingTo(Money.ZERO)
        assertThat(stored.apiCost).isEqualTo(Money.usd("0.05"))
        assertThat(stored.judgeCost).isEqualByComparingTo(Money.ZERO)
        assertThat(stored.judgeApiCost).isEqualTo(Money.usd("0.02"))
        assertThat(stored.candidateLatencyMs).isEqualTo(12_000L)
    }

    @Test
    fun `a login judge without an estimate and no API profile of its family is priced by its own pricing`() {
        val benchmark = start(sonnetLogin, opusLogin)
        every { ownLogin.chat(sonnetLogin.ref, any()) } returns answer(sonnetLogin.ref, "The bug is X.", TokenUsage(1000, 500), null)
        every { ownLogin.chat(opusLogin.ref, any()) } returns answer(opusLogin.ref, "{}", TokenUsage(3000, 300), null, verdict)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        // the candidate: 1000 * 3 / 1e6 + 500 * 15 / 1e6 (Sonnet API); the judge: no Opus API profile, its own (free) pricing
        assertThat(stored.apiCost).isEqualTo(Money.usd("0.0105"))
        assertThat(stored.judgeApiCost).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `a gateway candidate has the same amount spent and at API prices`() {
        val benchmark = start(sonnetApi, apiJudge)
        every { gateway.chat(sonnetApi.ref, any()) } returns answer(sonnetApi.ref, "The bug is X.", TokenUsage(1000, 500), null)
        every { gateway.chat(apiJudge.ref, any()) } returns answer(apiJudge.ref, "{}", TokenUsage(2000, 200), null, verdict)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.cost).isEqualTo(Money.usd("0.0105"))
        assertThat(stored.apiCost).isEqualTo(stored.cost)
        assertThat(stored.judgeApiCost).isEqualTo(stored.judgeCost)
    }
}
