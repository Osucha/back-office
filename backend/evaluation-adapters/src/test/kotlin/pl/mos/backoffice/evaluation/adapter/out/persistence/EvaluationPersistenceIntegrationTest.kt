package pl.mos.backoffice.evaluation.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.evaluation.PostgresTestSupport
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.AttemptVerification
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.JudgeCriterion
import pl.mos.backoffice.evaluation.domain.model.JudgeVerdict
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(properties = ["backoffice.evaluation.seed-starter-content=false", "backoffice.evaluation.recover-on-startup=false"])
@Import(PostgresTestSupport::class)
class EvaluationPersistenceIntegrationTest {

    @Autowired lateinit var profiles: ModelProfileRepository
    @Autowired lateinit var suites: TaskSuiteRepository
    @Autowired lateinit var benchmarks: BenchmarkRepository
    @Autowired lateinit var catalog: ModelCatalog
    @Autowired lateinit var defaults: pl.mos.backoffice.evaluation.application.port.out.ModelDefaultRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val actor = Actor(PrincipalKind.USER, UUID.randomUUID(), "Eve Evaluator")

    private fun profile(name: String, modelId: String = "claude-sonnet-5") = ModelProfile.create(
        displayName = name,
        ref = ModelRef(Provider.ANTHROPIC, modelId, ModelOptions(temperature = 0.3, maxOutputTokens = 4096, reasoning = Reasoning.HIGH, extra = mapOf("topP" to 0.9))),
        pricing = Pricing(BigDecimal("2"), BigDecimal("10"), BigDecimal("0.2"), null),
        capabilities = setOf(Capability.CODING, Capability.TOOLS),
        contextWindow = 200_000,
        notes = "seeded by test",
        actor = actor,
        now = now,
    )

    @Test
    fun `model profile round trips options, pricing and capabilities and feeds the catalogue`() {
        val saved = profiles.save(profile("Sonnet", "claude-sonnet-5-rt"))

        val loaded = profiles.findById(saved.id)!!
        assertThat(loaded).isEqualTo(saved)
        assertThat(loaded.ref.options.extra["topP"]).isEqualTo(0.9)
        assertThat(profiles.findAll(enabled = true)).contains(loaded)
        assertThat(profiles.findAll(enabled = false)).doesNotContain(loaded)

        assertThat(catalog.find(saved.id)).isEqualTo(saved.toSnapshot())
        assertThat(catalog.findPricing(ModelRef(Provider.ANTHROPIC, "claude-sonnet-5-rt"))).isEqualTo(saved.pricing)
        assertThat(catalog.findPricing(ModelRef(Provider.OPENAI, "claude-sonnet-5-rt"))).isNull()
        assertThat(catalog.findAllEnabled().map { it.id }).contains(saved.id)

        profiles.delete(saved.id)
        assertThat(profiles.findById(saved.id)).isNull()
    }

    @Test
    fun `task suite round trips tasks in order and reconciles removed tasks`() {
        val prompt = EvalTask(EvalTaskId.new(), "prompt", TaskCategory.ANALYSIS, Difficulty.EASY, TaskKind.PROMPT, "Explain", "ctx", "rubric", "ref", timeout = Duration.ofMinutes(5), weight = 2.0)
        val coding = EvalTask(
            EvalTaskId.new(), "coding", TaskCategory.CODING, Difficulty.HARD, TaskKind.CODING_JOB, "Implement", null, "rubric",
            repository = TaskRepository("https://git.example.com/a.git", "develop", "./gradlew test", "./gradlew build -x test"),
        )
        val suite = suites.save(TaskSuite.create("Suite", "desc", CodingScoreWeights(0.5, 0.5), listOf(prompt, coding), now, actor = actor))

        val loaded = suites.findById(suite.id)!!
        assertThat(loaded).isEqualTo(suite)
        assertThat(loaded.tasks.map { it.name }).containsExactly("prompt", "coding")
        assertThat(loaded.tasks[1].repository).isEqualTo(coding.repository)

        val trimmed = suites.save(loaded.removeTask(prompt.id, now).addTask(prompt.copy(name = "prompt-2"), now))
        val reloaded = suites.findById(trimmed.id)!!
        assertThat(reloaded.tasks.map { it.name }).containsExactly("coding", "prompt-2")
        assertThat(suites.findAll().map { it.id }).contains(suite.id)

        suites.delete(suite.id)
        assertThat(suites.findById(suite.id)).isNull()
    }

    @Test
    fun `benchmark and attempts round trip json columns and support status queries`() {
        val judge = profiles.save(profile("Judge", "claude-fable-5-1"))
        val model = profiles.save(profile("Candidate", "claude-haiku-4-5"))
        val task = EvalTask(EvalTaskId.new(), "t", TaskCategory.REVIEW, Difficulty.MEDIUM, TaskKind.PROMPT, "i", null, "r")
        val suite = suites.save(TaskSuite.create("S", tasks = listOf(task), now = now))
        val benchmark = benchmarks.save(
            Benchmark.create("B", suite.id, listOf(task.id), listOf(model.id), judge.id, 2, AgentHarness.CLAUDE_CODE, now, actor = actor).start(now, Actor.SYSTEM),
        )

        assertThat(benchmarks.findById(benchmark.id)).isEqualTo(benchmark)
        assertThat(benchmarks.findByStatus(BenchmarkStatus.RUNNING).map { it.id }).contains(benchmark.id)
        assertThat(benchmarks.countByStatus(BenchmarkStatus.RUNNING)).isGreaterThanOrEqualTo(1)
        assertThat(benchmarks.existsReferencingModel(model.id)).isTrue()
        assertThat(benchmarks.existsReferencingModel(judge.id)).isTrue()
        assertThat(benchmarks.existsReferencingModel(ModelProfileId.new())).isFalse()
        assertThat(benchmarks.existsForSuiteWithStatus(suite.id, BenchmarkStatus.RUNNING)).isTrue()

        val base = Attempt(
            id = AttemptId.new(), benchmarkId = benchmark.id, taskId = task.id, taskName = task.name, category = task.category,
            difficulty = task.difficulty, kind = task.kind, modelProfileId = model.id, modelDisplayName = model.displayName,
            provider = Provider.ANTHROPIC, repetition = 1, createdAt = now,
        )
        val succeeded = base.start(now).withRunnerJob(RunnerJobId.new())
            .judging("output", AttemptVerification("./gradlew test", 0, "OK"), TokenUsage(100, 20, 5, 1), Money.usd("0.0123"))
            .succeed(88.0, true, JudgeVerdict(88.0, true, listOf(JudgeCriterion("Correctness", 9.0, "good")), "solid"), Money.usd("0.02"), now.plusSeconds(3))
        val pending = base.copy(id = AttemptId.new(), repetition = 2)
        benchmarks.saveAttempts(listOf(succeeded, pending))

        val loaded = benchmarks.findAttempt(succeeded.id)!!
        assertThat(loaded).isEqualTo(succeeded)
        assertThat(loaded.verification!!.exitCode).isEqualTo(0)
        assertThat(loaded.judgeCriteria).containsExactly(JudgeCriterion("Correctness", 9.0, "good"))
        assertThat(loaded.durationMs).isEqualTo(3000)

        assertThat(benchmarks.findAttempts(benchmark.id)).hasSize(2)
        assertThat(benchmarks.findAttempts(benchmark.id, taskId = task.id, modelProfileId = model.id)).hasSize(2)
        assertThat(benchmarks.findAttempts(benchmark.id, modelProfileId = judge.id)).isEmpty()
        assertThat(benchmarks.findAttemptsByStatus(benchmark.id, setOf(AttemptStatus.PENDING)).map { it.id }).containsExactly(pending.id)
        assertThat(benchmarks.countAttemptsNotIn(benchmark.id, setOf(AttemptStatus.SUCCEEDED, AttemptStatus.FAILED, AttemptStatus.TIMED_OUT))).isEqualTo(1)

        benchmarks.delete(benchmark.id)
        assertThat(benchmarks.findById(benchmark.id)).isNull()
        assertThat(benchmarks.findAttempt(succeeded.id)).isNull()
    }

    @Test
    fun `a personal login profile keeps its runtime and owner, and defaults are stored per owner and kind (ADR 0050)`() {
        val owner = UUID.randomUUID()
        val login = profiles.save(
            ModelProfile.create(
                "Opus (my login)", ModelRef(Provider.ANTHROPIC, "opus"), Pricing.FREE, now = now,
                runtime = pl.mos.backoffice.shared.llm.ModelRuntime.CLAUDE_CODE_LOGIN, ownerUserId = owner,
            ),
        )
        val read = profiles.findById(login.id)!!
        assertThat(read.runtime).isEqualTo(pl.mos.backoffice.shared.llm.ModelRuntime.CLAUDE_CODE_LOGIN)
        assertThat(read.ownerUserId).isEqualTo(owner)
        assertThat(catalog.find(login.id)!!.let { it.runtime to it.ownerUserId }).isEqualTo(pl.mos.backoffice.shared.llm.ModelRuntime.CLAUDE_CODE_LOGIN to owner)

        val platform = profiles.save(ModelProfile.create("Platform", ModelRef(Provider.ANTHROPIC, "claude-sonnet-5"), Pricing.FREE, now = now))
        val kind = pl.mos.backoffice.shared.llm.StepKind.CODING_AGENT
        defaults.save(pl.mos.backoffice.evaluation.domain.model.ModelDefault(null, kind, platform.id, now, actor))
        defaults.save(pl.mos.backoffice.evaluation.domain.model.ModelDefault(owner, kind, platform.id, now, actor))
        defaults.save(pl.mos.backoffice.evaluation.domain.model.ModelDefault(owner, kind, login.id, now, actor))
        assertThat(defaults.find(owner, kind)!!.profileId).describedAs("replaced, not duplicated").isEqualTo(login.id)
        assertThat(defaults.findFor(owner)).hasSize(1)
        assertThat(defaults.find(null, kind)!!.let { it.profileId to it.updatedBy?.displayName }).isEqualTo(platform.id to "Eve Evaluator")
        defaults.delete(owner, kind)
        assertThat(defaults.find(owner, kind)).isNull()
        assertThat(defaults.find(null, kind)).isNotNull()
        defaults.delete(null, kind)
    }

}
