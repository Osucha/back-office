package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.evaluation.support.SteppingClock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerDiffStat
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerVerification
import pl.mos.backoffice.shared.usage.UsageKind
import java.math.BigDecimal
import java.time.Duration
import pl.mos.backoffice.evaluation.domain.model.ModelProfile

class ExecuteAttemptServiceTest {

    private val benchmarks = InMemoryBenchmarkRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val profiles = InMemoryModelProfileRepository()
    private val gateway = mockk<LlmGateway>()
    private val runner = mockk<AgentRunner>(relaxed = true)
    private val clock = SteppingClock()
    private val service = ExecuteAttemptService(benchmarks, suites, profiles, gateway, runner, clock)

    private val judge = Fixtures.profile("Judge", modelId = "claude-fable-5-1", input = "10", output = "50")
    private val candidate = Fixtures.profile("Candidate", modelId = "claude-sonnet-5", input = "2", output = "10")
    private val promptTask = Fixtures.promptTask("analysis")
    private val codingTask = Fixtures.codingTask("implement")
    private val suite = Fixtures.suite(promptTask, codingTask)
    private lateinit var benchmark: Benchmark

    @BeforeEach
    fun setUp() {
        profiles.save(judge); profiles.save(candidate); suites.save(suite)
        benchmark = benchmarks.save(Fixtures.benchmark(suite, listOf(candidate), judge).start(Fixtures.now))
        benchmarks.saveAttempts(BenchmarkExecutionPlanner.plan(benchmark, suite, listOf(candidate), Fixtures.now))
    }

    private fun llmResult(model: ModelRef, content: String, structured: Map<String, Any?>? = null, usage: TokenUsage = TokenUsage(1000, 500)) =
        LlmResult(content = content, structured = structured, usage = usage, latency = Duration.ofSeconds(1), modelRef = model)

    private val judgeAnswer = mapOf(
        "score" to 85,
        "passed" to true,
        "criteria" to listOf(mapOf("name" to "Correctness", "score" to 9, "comment" to "right")),
        "rationale" to "Good answer.",
    )

    @Test
    fun `prompt attempt calls candidate then judge with schema and stores combined result`() {
        val requests = mutableListOf<LlmRequest>()
        every { gateway.chat(candidate.ref, capture(requests)) } answers { llmResult(candidate.ref, "The bug is X.") }
        every { gateway.chat(judge.ref, capture(requests)) } answers {
            llmResult(judge.ref, """{"score":85}""", judgeAnswer, TokenUsage(2000, 200))
        }
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        val report = service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.output).isEqualTo("The bug is X.")
        assertThat(stored.score).isEqualTo(85.0)
        assertThat(stored.passed).isTrue()
        assertThat(stored.judgeRationale).isEqualTo("Good answer.")
        assertThat(stored.judgeCriteria).hasSize(1)
        assertThat(stored.usage).isEqualTo(TokenUsage(1000, 500))
        // 1000 * 2 / 1e6 + 500 * 10 / 1e6 = 0.002 + 0.005
        assertThat(stored.cost).isEqualTo(Money.usd("0.007"))
        // judge: 2000 * 10 / 1e6 + 200 * 50 / 1e6 = 0.02 + 0.01
        assertThat(stored.judgeCost).isEqualTo(Money.usd("0.03"))
        assertThat(stored.durationMs).isNotNull().isPositive()

        val candidateRequest = requests[0]
        assertThat(candidateRequest.responseSchema).isNull()
        assertThat(candidateRequest.messages.last().content).contains("Explain the bug.")
        assertThat(candidateRequest.usageContext.kind).isEqualTo(UsageKind.BENCHMARK_ATTEMPT)
        assertThat(candidateRequest.usageContext.benchmarkId).isEqualTo(benchmark.id.value)
        assertThat(candidateRequest.usageContext.attemptId).isEqualTo(attempt.id.value)
        val judgeRequest = requests[1]
        assertThat(judgeRequest.responseSchema).contains("\"score\"")
        assertThat(judgeRequest.messages.last().content).contains("The bug is X.").contains("Correctness 0-10.")

        assertThat(report.progress!!.counts.done).isEqualTo(1)
        assertThat(report.progress!!.counts.total).isEqualTo(2)
        assertThat(report.finished).isNull()
    }

    @Test
    fun `coding attempt runs the agent job, judges the diff and finishes the benchmark when it is the last attempt`() {
        every { gateway.chat(any(), any()) } answers { llmResult(judge.ref, "{}", mapOf("score" to 50, "passed" to true, "rationale" to "ok")) }
        val specSlot = slot<RunnerJobSpec>()
        val jobId = RunnerJobId.new()
        every { runner.submit(capture(specSlot), any()) } returns jobId
        every { runner.result(jobId) } returns RunnerResult(
            status = RunnerJobStatus.SUCCEEDED,
            summary = "Added endpoint",
            branch = "eval/x",
            commitSha = "abc",
            diffStat = RunnerDiffStat(3, 40, 5),
            verification = RunnerVerification("./gradlew test", 0, "BUILD SUCCESSFUL"),
            artifacts = emptyList(),
            usage = TokenUsage(50_000, 8_000),
            reportedCostUsd = BigDecimal("0.42"),
            error = null,
            finishedAt = Fixtures.now,
        )
        // make the prompt attempt terminal first so the coding attempt is the last one
        val promptAttempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }
        benchmarks.saveAttempt(promptAttempt.start(Fixtures.now).fail("boom", Fixtures.now))
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == codingTask.id }

        assertThat(service.execute(attempt.id).waiting).describedAs("submitted, not awaited").isTrue()
        assertThat(benchmarks.findAttempt(attempt.id)!!.status).isEqualTo(AttemptStatus.RUNNING)
        val report = service.execute(attempt.id)

        val spec = specSlot.captured
        assertThat(spec.mode).isEqualTo(RunnerMode.EVAL_CODING)
        assertThat(spec.agent).isEqualTo(AgentHarness.OPENCODE)
        assertThat(spec.model).isEqualTo(candidate.ref)
        assertThat(spec.repository!!.url).isEqualTo("https://git.example.com/sample.git")
        assertThat(spec.repository!!.branch).isEqualTo("eval/${attempt.id.short}")
        assertThat(spec.repository!!.push).isFalse()
        assertThat(spec.verifyCommand).isEqualTo("./gradlew test")
        assertThat(spec.collectArtifacts).containsExactly("**/*.md")
        assertThat(spec.jobLabel).isEqualTo("eval-${attempt.id.short}-1")
        assertThat(spec.usageContext.attemptId).isEqualTo(attempt.id.value)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.runnerJobId).isEqualTo(jobId)
        assertThat(stored.passed).isTrue()
        assertThat(stored.verification!!.exitCode).isEqualTo(0)
        // 0.6 * 100 + 0.4 * 50
        assertThat(stored.score).isCloseTo(80.0, within(1e-9))
        assertThat(stored.cost).isEqualTo(Money.usd("0.42"))
        assertThat(stored.usage).isEqualTo(TokenUsage(50_000, 8_000))
        assertThat(stored.output).contains("Added endpoint")

        val judgeRequest = slot<LlmRequest>()
        verify { gateway.chat(judge.ref, capture(judgeRequest)) }
        assertThat(judgeRequest.captured.messages.last().content).contains("3 files changed").contains("BUILD SUCCESSFUL")

        assertThat(report.finished).isNotNull()
        assertThat(report.finished!!.status).isEqualTo(BenchmarkStatus.COMPLETED)
        assertThat(benchmarks.findById(benchmark.id)!!.status).isEqualTo(BenchmarkStatus.COMPLETED)
        assertThat(report.progress!!.counts).satisfies({ assertThat(it.done).isEqualTo(2) }, { assertThat(it.failed).isEqualTo(1) })
    }

    @Test
    fun `coding attempt with failed verification is not passed but still judged`() {
        every { gateway.chat(any(), any()) } answers { llmResult(judge.ref, "{}", mapOf("score" to 70, "passed" to false)) }
        every { runner.submit(any(), any()) } returns RunnerJobId.new()
        every { runner.result(any()) } returns RunnerResult(
            status = RunnerJobStatus.FAILED, summary = "tests red", branch = null, commitSha = null, diffStat = null,
            verification = RunnerVerification("./gradlew test", 1, "FAILED"), artifacts = emptyList(),
            usage = TokenUsage(10, 10), reportedCostUsd = null, error = "verification failed", finishedAt = Fixtures.now,
        )
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == codingTask.id }

        service.execute(attempt.id)
        service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.passed).isFalse()
        assertThat(stored.score).isCloseTo(28.0, within(1e-9))
        assertThat(stored.cost).isEqualTo(candidate.pricing.costOf(TokenUsage(10, 10)))
    }

    @Test
    fun `runner timeout marks the attempt TIMED_OUT and a runner crash marks it FAILED`() {
        every { runner.submit(any(), any()) } returns RunnerJobId.new()
        every { runner.result(any()) } returns RunnerResult(
            status = RunnerJobStatus.TIMED_OUT, summary = null, branch = null, commitSha = null, diffStat = null,
            verification = null, artifacts = emptyList(), usage = TokenUsage.ZERO, reportedCostUsd = null,
            error = null, finishedAt = Fixtures.now,
        )
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == codingTask.id }
        service.execute(attempt.id)
        service.execute(attempt.id)
        assertThat(benchmarks.findAttempt(attempt.id)!!.status).isEqualTo(AttemptStatus.TIMED_OUT)

        every { runner.result(any()) } returns RunnerResult(
            status = RunnerJobStatus.FAILED, summary = null, branch = null, commitSha = null, diffStat = null,
            verification = null, artifacts = emptyList(), usage = TokenUsage.ZERO, reportedCostUsd = null,
            error = "image pull failed", finishedAt = Fixtures.now,
        )
        benchmarks.saveAttempt(attempt.requeue())
        service.execute(attempt.id)
        service.execute(attempt.id)
        val failed = benchmarks.findAttempt(attempt.id)!!
        assertThat(failed.status).isEqualTo(AttemptStatus.FAILED)
        assertThat(failed.error).isEqualTo("image pull failed")
    }

    @Test
    fun `gateway failure without retry budget marks the attempt FAILED with the error and the benchmark keeps running`() {
        every { gateway.chat(candidate.ref, any()) } throws IntegrationException("anthropic", "429 rate limited", retryable = true)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        val report = service.execute(attempt.id, allowRetry = false)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(report.retry).isFalse()
        assertThat(stored.status).isEqualTo(AttemptStatus.FAILED)
        assertThat(stored.error).contains("429 rate limited")
        assertThat(benchmarks.findById(benchmark.id)!!.status).isEqualTo(BenchmarkStatus.RUNNING)
    }

    @Test
    fun `a retryable gateway failure puts the attempt back to PENDING and reports a retry, a permanent one fails it`() {
        every { gateway.chat(candidate.ref, any()) } throws IntegrationException("anthropic", "rate limit reached, retry in 12s", retryable = true)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        val report = service.execute(attempt.id)

        assertThat(report.retry).isTrue()
        assertThat(report.retryReason).contains("rate limit")
        assertThat(report.progress).isNull()
        assertThat(report.finished).isNull()
        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(stored.error).isNull()

        every { gateway.chat(candidate.ref, any()) } throws IntegrationException("anthropic", "HTTP 401 (authentication failed)")
        val second = service.execute(attempt.id)
        assertThat(second.retry).isFalse()
        assertThat(benchmarks.findAttempt(attempt.id)!!.status).isEqualTo(AttemptStatus.FAILED)
    }

    @Test
    fun `benchmark fails when every attempt failed`() {
        every { gateway.chat(any(), any()) } throws IntegrationException("anthropic", "boom")
        every { runner.submit(any(), any()) } throws IllegalStateException("runner disabled")

        benchmarks.findAttempts(benchmark.id).forEach { service.execute(it.id) }

        assertThat(benchmarks.findById(benchmark.id)!!.status).isEqualTo(BenchmarkStatus.FAILED)
        assertThat(benchmarks.findAttempts(benchmark.id)).allSatisfy { assertThat(it.status).isEqualTo(AttemptStatus.FAILED) }
    }

    @Test
    fun `a retry after the judge failed asks only the judge again (ADR 0046)`() {
        every { gateway.chat(candidate.ref, any()) } answers { llmResult(candidate.ref, "The bug is X.") }
        every { gateway.chat(judge.ref, any()) } throws IntegrationException("anthropic", "429 rate limited", retryable = true)
        val attempt = benchmarks.findAttempts(benchmark.id).first { it.taskId == promptTask.id }

        assertThat(service.execute(attempt.id).retry).isTrue()
        val requeued = benchmarks.findAttempt(attempt.id)!!
        assertThat(requeued.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(requeued.output).isEqualTo("The bug is X.")

        every { gateway.chat(judge.ref, any()) } answers { llmResult(judge.ref, "{}", judgeAnswer, TokenUsage(2000, 200)) }
        service.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.SUCCEEDED)
        assertThat(stored.output).isEqualTo("The bug is X.")
        assertThat(stored.usage).isEqualTo(TokenUsage(1000, 500))
        verify(exactly = 1) { gateway.chat(candidate.ref, any()) }
    }

    @Test
    fun `a persistent failure skips the waiting attempts of that model, a judge failure all of them (ADR 0046)`() {
        val other = Fixtures.profile("Other", modelId = "other-model", input = "1", output = "1")
        profiles.save(other)
        val prompts = suite.copy(tasks = listOf(promptTask))
        val twoModels = benchmarks.save(Fixtures.benchmark(prompts, listOf(candidate, other), judge, repetitions = 2).start(Fixtures.now))
        benchmarks.saveAttempts(BenchmarkExecutionPlanner.plan(twoModels, prompts, listOf(candidate, other), Fixtures.now))
        fun of(model: ModelProfile) = benchmarks.findAttempts(twoModels.id).filter { it.modelProfileId == model.id }.sortedBy { it.repetition }
        every { gateway.chat(candidate.ref, any()) } throws IntegrationException("openai_compatible", "HTTP 403 (forbidden)", persistent = true)

        service.execute(of(candidate).first().id)
        assertThat(of(candidate).map { it.status }).containsOnly(AttemptStatus.FAILED)
        assertThat(of(candidate).last().error).startsWith("skipped, Candidate failed")
        assertThat(of(other).map { it.status }).describedAs("another model is not affected").containsOnly(AttemptStatus.PENDING)

        // the judge's quota is gone: everything still waiting ends at once and the benchmark finishes
        every { gateway.chat(other.ref, any()) } answers { llmResult(other.ref, "answer") }
        every { gateway.chat(judge.ref, any()) } throws IntegrationException("anthropic", "HTTP 429 (quota used up)", persistent = true)
        val report = service.execute(of(other).first().id)

        assertThat(of(other).first().error).startsWith("judge: ")
        assertThat(of(other).last().error).startsWith("skipped, the judge failed")
        assertThat(benchmarks.findAttempts(twoModels.id)).allSatisfy { assertThat(it.status).isEqualTo(AttemptStatus.FAILED) }
        assertThat(report.finished).isNotNull()
    }

    @Test
    fun `attempts of a cancelled benchmark and non-pending attempts are skipped`() {
        benchmarks.save(benchmark.cancel(Fixtures.now))
        val attempt = benchmarks.findAttempts(benchmark.id).first()

        val report = service.execute(attempt.id)

        assertThat(report.attempt).isNull()
        assertThat(benchmarks.findAttempt(attempt.id)!!.status).isEqualTo(AttemptStatus.PENDING)
        verify(exactly = 0) { gateway.chat(any(), any()) }
    }
}
