package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.usage.UsageKind
import java.time.Duration
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget

class BenchmarkServiceTest {

    private val benchmarks = InMemoryBenchmarkRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val profiles = InMemoryModelProfileRepository()
    private val submitted = mutableListOf<AttemptId>()
    private val runner = mockk<AgentRunner>(relaxed = true)
    private val clock = Clock.fixed(Fixtures.now)
    private val gate = FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_VIEW, EvaluationPermissions.EVALUATION_RUN)
    private val audit = RecordingAuditPort()
    private val service = BenchmarkService(benchmarks, suites, profiles, { submitted += it }, runner, clock, gate, audit)

    private val judge = Fixtures.profile("Judge")
    private val a = Fixtures.profile("A")
    private val b = Fixtures.profile("B", modelId = "claude-haiku-4-5")
    private val suite = Fixtures.suite(Fixtures.promptTask("t1"), Fixtures.codingTask("t2"))

    @BeforeEach
    fun setUp() {
        listOf(judge, a, b).forEach { profiles.save(it) }
        suites.save(suite)
        targets(shared = 1, personal = 0)
    }

    private fun targets(shared: Int, personal: Int) {
        every { runner.executionTargets(any()) } returns listOf(
            ExecutionTarget(RunnerTarget.SHARED, shared, 0),
            ExecutionTarget(RunnerTarget.PERSONAL, personal, 0),
        )
    }

    private fun command(repetitions: Int = 1) = ManageBenchmarks.BenchmarkCommand(
        name = "Compare", suiteId = suite.id, modelProfileIds = listOf(a.id, b.id), judgeModelProfileId = judge.id, repetitions = repetitions,
    )

    @Test
    fun `create makes a draft and start persists the matrix before submitting`() {
        val draft = service.create(command(repetitions = 2))
        assertThat(draft.benchmark.status).isEqualTo(BenchmarkStatus.DRAFT)
        assertThat(draft.suiteName).isEqualTo("Suite")
        assertThat(draft.models.map { it.displayName }).containsExactly("A", "B")
        assertThat(draft.judge!!.displayName).isEqualTo("Judge")

        val started = service.start(draft.benchmark.id)

        assertThat(started.benchmark.status).isEqualTo(BenchmarkStatus.RUNNING)
        assertThat(started.progress.total).isEqualTo(2 * 2 * 2)
        assertThat(submitted).hasSize(8)
        assertThat(benchmarks.findAttempts(draft.benchmark.id)).allSatisfy { assertThat(it.status).isEqualTo(AttemptStatus.PENDING) }
        assertThatThrownBy { service.start(draft.benchmark.id) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `start refuses coding tasks without a runner pool of the chosen target and disabled models`() {
        targets(shared = 0, personal = 1)
        val draft = service.create(command())
        assertThatThrownBy { service.start(draft.benchmark.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no shared runner pool")
        val personal = service.create(command().copy(runnerTarget = RunnerTarget.PERSONAL))
        assertThat(personal.benchmark.runnerTarget).isEqualTo(RunnerTarget.PERSONAL)
        targets(shared = 1, personal = 0)
        assertThatThrownBy { service.start(personal.benchmark.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no personal runner")
        assertThat(service.executionTargets().map { it.target }).containsExactly(RunnerTarget.SHARED, RunnerTarget.PERSONAL)

        profiles.save(a.copy(enabled = false))
        assertThatThrownBy { service.start(draft.benchmark.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("disabled")
    }

    @Test
    fun `cancel marks the benchmark cancelled and cancels running runner jobs`() {
        val started = service.start(service.create(command()).benchmark.id)
        val running = benchmarks.findAttempts(started.benchmark.id).first().start(Fixtures.now).withRunnerJob(RunnerJobId.new())
        benchmarks.saveAttempt(running)

        val cancelled = service.cancel(started.benchmark.id)

        assertThat(cancelled.benchmark.status).isEqualTo(BenchmarkStatus.CANCELLED)
        verify { runner.cancel(running.runnerJobId!!) }
        assertThatCode { service.delete(started.benchmark.id) }.doesNotThrowAnyException()
        assertThat(benchmarks.findById(started.benchmark.id)).isNull()
    }

    @Test
    fun `delete refuses running benchmarks`() {
        val started = service.start(service.create(command()).benchmark.id)
        assertThatThrownBy { service.delete(started.benchmark.id) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `recover requeues in-flight attempts of running benchmarks only`() {
        val started = service.start(service.create(command()).benchmark.id)
        val attempts = benchmarks.findAttempts(started.benchmark.id)
        benchmarks.saveAttempt(attempts[0].start(Fixtures.now))
        benchmarks.saveAttempt(attempts[1].start(Fixtures.now).fail("x", Fixtures.now))
        val other = service.create(command())
        submitted.clear()

        val requeued = service.recover()

        assertThat(requeued).isEqualTo(3)
        assertThat(submitted).hasSize(3)
        assertThat(benchmarks.findAttempt(attempts[0].id)!!.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(benchmarks.findAttempt(attempts[1].id)!!.status).isEqualTo(AttemptStatus.FAILED)
        assertThat(benchmarks.findAttempts(other.benchmark.id)).isEmpty()
    }

    @Test
    fun `dashboard query reports running count and recent summaries`() {
        service.start(service.create(command()).benchmark.id)
        service.create(command())

        assertThat(service.runningBenchmarks()).isEqualTo(1)
        val recent = service.recentBenchmarks(10)
        assertThat(recent).hasSize(2)
        assertThat(recent.first().models.map { it.displayName }).containsExactly("A", "B")
    }

    @Test
    fun `model profile test books a MODEL_TEST call and never throws`() {
        val gateway = mockk<LlmGateway>()
        val modelService = ModelProfileService(profiles, benchmarks, gateway, clock, FakeAuthorizationGate.permitting(EvaluationPermissions.MODELS_MANAGE), audit)
        every { gateway.chat(a.ref, any()) } answers {
            LlmResult("OK", null, TokenUsage(10, 2), latency = Duration.ofMillis(321), modelRef = a.ref)
        }
        every { gateway.chat(b.ref, any()) } throws IntegrationException("openai", "401 unauthorized")

        val ok = modelService.test(a.id)
        assertThat(ok.ok).isTrue()
        assertThat(ok.latencyMs).isEqualTo(321)
        assertThat(ok.sample).isEqualTo("OK")
        assertThat(ok.cost).isEqualTo(a.pricing.costOf(TokenUsage(10, 2)))
        verify { gateway.chat(a.ref, match { it.usageContext.kind == UsageKind.MODEL_TEST }) }

        val failed = modelService.test(b.id)
        assertThat(failed.ok).isFalse()
        assertThat(failed.error).contains("401")

        service.create(command())
        assertThatThrownBy { modelService.delete(a.id) }.isInstanceOf(InvalidStateException::class.java)
        val fresh = modelService.create(ManageModelProfiles.ProfileCommand("C", a.ref, a.pricing))
        modelService.delete(fresh.id)
        assertThat(profiles.findById(fresh.id)).isNull()
    }

    @Test
    fun `preflight checks every model of a benchmark once with evaluation run (ADR 0046)`() {
        val gateway = mockk<LlmGateway>()
        every { gateway.chat(a.ref, any()) } answers { LlmResult("OK", null, TokenUsage(10, 2), latency = Duration.ofMillis(5), modelRef = a.ref) }
        every { gateway.chat(b.ref, any()) } throws IntegrationException("openai_compatible", "HTTP 429 (quota used up)", persistent = true)
        val runner = ModelProfileService(profiles, benchmarks, gateway, clock, FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_RUN), audit)

        val results = runner.preflight(listOf(a.id, b.id, a.id))

        assertThat(results.map { it.first.displayName to it.second.ok }).containsExactly("A" to true, "B" to false)
        assertThat(results.last().second.error).contains("quota")
        verify(exactly = 1) { gateway.chat(a.ref, any()) }
        val viewer = ModelProfileService(profiles, benchmarks, gateway, clock, FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_VIEW), audit)
        assertThatThrownBy { viewer.preflight(listOf(a.id)) }.isInstanceOf(pl.mos.backoffice.shared.InsufficientPermissionException::class.java)
    }

    private val login = Fixtures.profile("Opus (my login)", modelId = "opus").copy(runtime = ModelRuntime.CLAUDE_CODE_LOGIN)

    @Test
    fun `Claude Code login models run coding tasks on the personal runner with Claude Code and never judge (ADR 0048)`() {
        profiles.save(login)
        val coding = suite.tasks.single { it.kind == pl.mos.backoffice.evaluation.domain.model.TaskKind.CODING_JOB }
        val ok = command().copy(
            modelProfileIds = listOf(login.id, a.id), taskIds = listOf(coding.id),
            codingHarness = AgentHarness.CLAUDE_CODE, runnerTarget = RunnerTarget.PERSONAL,
        )
        fun refused(c: ManageBenchmarks.BenchmarkCommand, field: String) =
            assertThatThrownBy { service.create(c) }.isInstanceOfSatisfying(ValidationException::class.java) { e ->
                assertThat(e.errors.map { it.field }).contains(field)
            }
        refused(ok.copy(judgeModelProfileId = login.id), "judgeModelProfileId")
        refused(ok.copy(runnerTarget = RunnerTarget.SHARED), "runnerTarget")
        refused(ok.copy(codingHarness = AgentHarness.OPENCODE), "codingHarness")
        refused(ok.copy(taskIds = emptyList()), "taskIds")

        val draft = service.create(ok)
        every { runner.executionTargets(any()) } returns listOf(ExecutionTarget(RunnerTarget.PERSONAL, 1, 1, localLogin = false))
        assertThatThrownBy { service.start(draft.benchmark.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("own login")
        every { runner.executionTargets(any()) } returns listOf(ExecutionTarget(RunnerTarget.PERSONAL, 1, 1, localLogin = true))
        assertThat(service.start(draft.benchmark.id).benchmark.status).isEqualTo(BenchmarkStatus.RUNNING)

        assertThatThrownBy { login.copy(ref = ModelRef(Provider.OPENAI, "gpt-5")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a Claude Code login model is checked on the personal runner, not called (ADR 0048)`() {
        profiles.save(login)
        val gateway = mockk<LlmGateway>()
        val agents = mockk<AgentRunner>()
        val models = ModelProfileService(profiles, benchmarks, gateway, clock, FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_RUN), audit, agents)
        fun check(vararg targets: ExecutionTarget): String? {
            every { agents.executionTargets(any()) } returns targets.toList()
            return models.preflight(listOf(login.id)).single().second.let { if (it.ok) null else it.error }
        }
        assertThat(check(ExecutionTarget(RunnerTarget.SHARED, 1, 1))).contains("no personal runner")
        assertThat(check(ExecutionTarget(RunnerTarget.PERSONAL, 1, 1))).contains("own Claude Code login")
        assertThat(check(ExecutionTarget(RunnerTarget.PERSONAL, 1, 0, localLogin = true))).contains("offline")
        assertThat(check(ExecutionTarget(RunnerTarget.PERSONAL, 1, 1, localLogin = true))).isNull()
        verify(exactly = 0) { gateway.chat(any(), any()) }
        assertThat(ModelProfileService(profiles, benchmarks, gateway, clock, FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_RUN), audit)
            .preflight(listOf(login.id)).single().second.ok).isFalse()
    }
}
