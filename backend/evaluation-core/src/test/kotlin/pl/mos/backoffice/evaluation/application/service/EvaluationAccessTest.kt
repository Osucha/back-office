package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles.ProfileCommand
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites.SuiteCommand
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites.TaskCommand
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import pl.mos.backoffice.shared.llm.ModelRef

/** ADR 0017 / 0018 for the Model Evaluation context: exact permission per use case, denial, audit and actor stamps. */
class EvaluationAccessTest {

    private val benchmarks = InMemoryBenchmarkRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val profiles = InMemoryModelProfileRepository()
    private val submitted = mutableListOf<AttemptId>()
    private val runner = mockk<AgentRunner>(relaxed = true).also { every { it.isAvailable() } returns true }
    private val gateway = mockk<LlmGateway>()
    private val clock = Clock.fixed(Fixtures.now)
    private val audit = RecordingAuditPort()

    private fun models(gate: FakeAuthorizationGate) = ModelProfileService(profiles, benchmarks, gateway, clock, gate, audit)
    private fun suites(gate: FakeAuthorizationGate) = TaskSuiteService(suites, benchmarks, clock, gate, audit)
    private fun benchmarks(gate: FakeAuthorizationGate) = BenchmarkService(benchmarks, suites, profiles, { submitted += it }, runner, clock, gate, audit)

    private val a = Fixtures.profile("A")
    private val command = ProfileCommand("C", a.ref, Pricing(BigDecimal("3"), BigDecimal("15")))

    @Test
    fun `the permission catalogue declares four platform permissions`() {
        assertThat(EvaluationPermissions.definitions.map { it.key })
            .containsExactlyInAnyOrder("models.view", "models.manage", "evaluation.view", "evaluation.run")
        assertThat(EvaluationPermissions.definitions).allSatisfy {
            assertThat(it.scope).isEqualTo(PermissionScope.PLATFORM)
            assertThat(it.module).isEqualTo("evaluation")
        }
    }

    @Test
    fun `model profiles are read with models view and changed with models manage, with an audited pricing diff`() {
        val manager = FakeAuthorizationGate.permitting("models.manage")
        val viewer = FakeAuthorizationGate.permitting("models.view")

        val created = models(manager).create(command)
        assertThat(manager.checked).containsExactly("models.manage")
        assertThat(created.updatedBy!!.displayName).isEqualTo("Alice Test")
        models(manager).update(created.id, command.copy(pricing = Pricing(BigDecimal("3"), BigDecimal("12"))))

        assertThat(models(viewer).list().map { it.displayName }).containsExactly("C")
        assertThat(models(viewer).get(created.id).displayName).isEqualTo("C")
        assertThat(viewer.checked).containsOnly("models.view")
        assertThat(ProviderService(gateway, emptyMap(), FakeAuthorizationGate.denyAll()).runCatching { list() }.exceptionOrNull())
            .isInstanceOf(InsufficientPermissionException::class.java)

        assertThatThrownBy { models(viewer).create(command) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { models(viewer).update(created.id, command) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { models(viewer).delete(created.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { models(viewer).test(created.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { models(manager).list() }.describedAs("manage does not imply view").isInstanceOf(InsufficientPermissionException::class.java)

        models(manager).delete(created.id)
        assertThat(audit.operations()).containsExactly("MODEL_PROFILE_CREATE", "MODEL_PROFILE_UPDATE", "MODEL_PROFILE_DELETE")
        assertThat(audit.entries[0].details["pricing"]).isEqualTo(
            mapOf("inputPerMTok" to "3", "outputPerMTok" to "15", "cacheReadPerMTok" to null, "cacheWritePerMTok" to null, "currency" to "USD"),
        )
        assertThat(audit.entries[1].details).containsOnlyKeys("pricing.outputPerMTok")
        assertThat(audit.entries[1].details["pricing.outputPerMTok"]).isEqualTo(mapOf("old" to "15", "new" to "12"))
    }

    @Test
    fun `a model test needs models manage and is audited with its outcome`() {
        val manager = FakeAuthorizationGate.permitting("models.manage")
        val profile = models(manager).create(command)
        every { gateway.chat(profile.ref, any()) } returns LlmResult("OK", null, TokenUsage(1, 1), latency = Duration.ofMillis(5), modelRef = profile.ref)

        assertThat(models(manager).test(profile.id).ok).isTrue()
        every { gateway.chat(profile.ref, any()) } throws RuntimeException("down")
        assertThat(models(manager).test(profile.id).ok).isFalse()

        val tests = audit.entries.filter { it.operation == "MODEL_TEST" }
        assertThat(tests.map { it.result }).containsExactly(AuditResult.SUCCESS, AuditResult.FAILURE)
        assertThat(tests[1].details["error"]).isEqualTo("down")
    }

    @Test
    fun `providers are listed with models view`() {
        every { gateway.isConfigured(any<Provider>()) } returns false
        every { gateway.isConfigured(any<ModelRef>()) } returns false
        val viewer = FakeAuthorizationGate.permitting("models.view")
        assertThat(ProviderService(gateway, emptyMap(), viewer).list().map { it.provider }).containsExactlyElementsOf(Provider.entries)
        assertThat(viewer.checked).containsExactly("models.view")
    }

    @Test
    fun `suites and tasks are read with evaluation view and changed with evaluation run, all audited`() {
        val runnerUser = FakeAuthorizationGate.permitting("evaluation.run")
        val viewer = FakeAuthorizationGate.permitting("evaluation.view")
        val task = TaskCommand("t", TaskCategory.ANALYSIS, Difficulty.EASY, TaskKind.PROMPT, "do it", rubric = "0-10")

        val suite = suites(runnerUser).create(SuiteCommand("S"))
        assertThat(runnerUser.checked).containsExactly("evaluation.run")
        assertThat(suite.updatedBy!!.displayName).isEqualTo("Alice Test")
        val added = suites(runnerUser).addTask(suite.id, task)
        suites(runnerUser).updateTask(suite.id, added.id, task.copy(name = "t2"))
        suites(runnerUser).update(suite.id, SuiteCommand("S2"))

        assertThat(suites(viewer).list().map { it.name }).containsExactly("S2")
        assertThat(suites(viewer).get(suite.id).tasks.map { it.name }).containsExactly("t2")
        assertThat(viewer.checked).containsOnly("evaluation.view")
        assertThatThrownBy { suites(viewer).create(SuiteCommand("x")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { suites(viewer).addTask(suite.id, task) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { suites(viewer).deleteTask(suite.id, added.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { suites(viewer).delete(suite.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        suites(runnerUser).deleteTask(suite.id, added.id)
        suites(runnerUser).delete(suite.id)
        assertThat(audit.operations()).containsExactly(
            "SUITE_CREATE", "EVAL_TASK_CREATE", "EVAL_TASK_UPDATE", "SUITE_UPDATE", "EVAL_TASK_DELETE", "SUITE_DELETE",
        )
        assertThat(audit.entries[2].details).containsEntry("name", mapOf("old" to "t", "new" to "t2")).containsEntry("suiteId", suite.id.toString())
    }

    @Test
    fun `benchmarks need evaluation run to create, start, cancel and delete, evaluation view to read, and stamp the creator`() {
        listOf(a, Fixtures.profile("Judge")).forEach { profiles.save(it) }
        val judge = profiles.findAll().first { it.displayName == "Judge" }
        val suite = suites.save(Fixtures.suite(Fixtures.promptTask("t1")))
        val runnerUser = FakeAuthorizationGate.permitting("evaluation.run")
        val viewer = FakeAuthorizationGate.permitting("evaluation.view")
        val cmd = ManageBenchmarks.BenchmarkCommand("B", suite.id, listOf(a.id), judge.id)

        val draft = benchmarks(runnerUser).create(cmd)
        assertThat(draft.benchmark.createdBy!!.displayName).isEqualTo("Alice Test")
        benchmarks(runnerUser).start(draft.benchmark.id)
        assertThat(runnerUser.checked).containsExactly("evaluation.run", "evaluation.run")

        val id = draft.benchmark.id
        assertThat(benchmarks(viewer).get(id).benchmark.name).isEqualTo("B")
        assertThat(benchmarks(viewer).list()).hasSize(1)
        assertThat(benchmarks(viewer).attempts(id)).hasSize(1)
        assertThat(benchmarks(viewer).leaderboard(id).benchmarkId).isEqualTo(id)
        assertThat(benchmarks(viewer).recentBenchmarks(5)).hasSize(1)
        assertThat(benchmarks(viewer).runningBenchmarks()).isEqualTo(1)
        assertThat(viewer.checked).containsOnly("evaluation.view")

        assertThatThrownBy { benchmarks(viewer).create(cmd) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { benchmarks(viewer).cancel(id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { benchmarks(viewer).delete(id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { benchmarks(runnerUser).get(id) }.describedAs("run does not imply view").isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { benchmarks(FakeAuthorizationGate.denyAll()).leaderboard(id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(benchmarks(FakeAuthorizationGate.denyAll()).runningBenchmarks()).describedAs("dashboard tile degrades to 0").isZero()

        benchmarks(runnerUser).cancel(id)
        benchmarks(runnerUser).delete(id)
        assertThat(audit.operations()).containsExactly("BENCHMARK_CREATE", "BENCHMARK_START", "BENCHMARK_CANCEL", "BENCHMARK_DELETE")
        assertThat(audit.entries.map { it.entityId }).containsOnly(id.toString())
        assertThat(audit.entries[1].details["attempts"]).isEqualTo(1)
    }

    @Test
    fun `recovery after a restart runs as the system without a user`() {
        assertThat(benchmarks(FakeAuthorizationGate.denyAll()).recover()).isZero()
    }
}
