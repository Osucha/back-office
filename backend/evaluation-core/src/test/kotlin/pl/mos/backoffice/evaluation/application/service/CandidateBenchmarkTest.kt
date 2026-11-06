package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks.CandidateInput
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkCandidate
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerTarget
import pl.mos.backoffice.shared.tasks.AgentEnvironmentSnapshot
import pl.mos.backoffice.shared.tasks.AgentEnvironmentText
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.SkillSnapshot
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.util.UUID

/**
 * ADR 0059: benchmarks compare candidates (an agent with a model): validation at create, planning of applicable pairs,
 * the derivation for benchmarks of models, the leaderboard per candidate and the agent's environment in the attempts.
 */
class CandidateBenchmarkTest {

    private val benchmarks = InMemoryBenchmarkRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val profiles = InMemoryModelProfileRepository()
    private val submitted = mutableListOf<AttemptId>()
    private val runner = mockk<AgentRunner>(relaxed = true)
    private val gateway = mockk<LlmGateway>()
    private val clock = Clock.fixed(Fixtures.now)
    private val gate = FakeAuthorizationGate.permitting(EvaluationPermissions.EVALUATION_VIEW, EvaluationPermissions.EVALUATION_RUN)

    private val judge = Fixtures.profile("Judge", modelId = "claude-fable-5-1")
    private val sonnet = Fixtures.profile("Sonnet")
    private val haiku = Fixtures.profile("Haiku", modelId = "claude-haiku-4-5")
    private val promptTask = Fixtures.promptTask("explain")
    private val codingTask = Fixtures.codingTask("implement")
    private val suite = Fixtures.suite(promptTask, codingTask)

    private val analyst = AgentSnapshot(
        "analyst", "Analyst", instructions = "You analyse requirements.", modelProfileId = haiku.id,
    )
    private val coder = AgentSnapshot(
        AgentSnapshot.CLAUDE_CODE, "Claude Code", AgentHarness.CLAUDE_CODE, instructions = "You write code.",
        mcpServers = listOf(AgentMcpServer("docs", "https://mcp.example.com")),
    )
    private val agents = mutableMapOf(analyst.key to analyst, coder.key to coder, AgentSnapshot.MODEL_ANSWER to AgentSnapshot.DEFAULT)
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = null
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
        override fun agent(key: String, projectId: UUID?): AgentSnapshot? = agents[key]
        override fun agentEnvironment(key: String, projectId: UUID?): AgentEnvironmentSnapshot? = agents[key]?.let {
            AgentEnvironmentSnapshot(it, listOf(RuleSnapshot("ground", "Ground rules", "Cite evidence.", automatic = true)), listOf(SkillSnapshot("adr", "ADR", "# ADR format")))
        }
    }

    private fun service(withAgents: Boolean = true) =
        BenchmarkService(benchmarks, suites, profiles, { submitted += it }, runner, clock, gate, RecordingAuditPort(), if (withAgents) catalog else null)

    private val execute = ExecuteAttemptService(benchmarks, suites, profiles, gateway, runner, clock, agents = catalog)

    @BeforeEach
    fun setUp() {
        listOf(judge, sonnet, haiku).forEach { profiles.save(it) }
        suites.save(suite)
        every { runner.executionTargets(any()) } returns listOf(ExecutionTarget(RunnerTarget.SHARED, 1, 1))
    }

    private fun command(vararg candidates: CandidateInput, models: List<pl.mos.backoffice.shared.ModelProfileId> = emptyList()) = ManageBenchmarks.BenchmarkCommand(
        name = "Agents", suiteId = suite.id, modelProfileIds = models, judgeModelProfileId = judge.id, repetitions = 2, candidates = candidates.toList(),
    )

    @Test
    fun `candidates are resolved at create, the agent's model when none is chosen, its name and harness`() {
        val created = service().create(command(CandidateInput("analyst"), CandidateInput(AgentSnapshot.CLAUDE_CODE, sonnet.id)))
        val b = created.benchmark

        assertThat(b.candidates).containsExactly(
            BenchmarkCandidate("analyst", haiku.id, "Analyst", null),
            BenchmarkCandidate(AgentSnapshot.CLAUDE_CODE, sonnet.id, "Claude Code", AgentHarness.CLAUDE_CODE),
        )
        assertThat(b.legacy).isFalse()
        assertThat(b.modelProfileIds).containsExactly(haiku.id, sonnet.id)
        assertThat(created.candidateRefs.map { it.label }).containsExactly("Analyst · Haiku", "Claude Code · Sonnet")
        assertThat(service().get(b.id).candidateRefs.map { it.label }).containsExactly("Analyst · Haiku", "Claude Code · Sonnet")
    }

    @Test
    fun `create refuses unknown agents, agents without a model, duplicates, nothing to compare and a server without agents`() {
        assertThatThrownBy { service().create(command(CandidateInput("nobody", sonnet.id))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("agent nobody does not exist")
        assertThatThrownBy { service().create(command(CandidateInput(AgentSnapshot.CLAUDE_CODE))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("has no model of its own")
        assertThatThrownBy { service().create(command(CandidateInput("analyst"), CandidateInput("analyst", haiku.id))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("listed twice")
        assertThatThrownBy { service().create(command()) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("at least one candidate")
        assertThatThrownBy { service(withAgents = false).create(command(CandidateInput("analyst"))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("agents are not available")
    }

    @Test
    fun `start plans only applicable pairs, the leaderboard counts the others as not applicable`() {
        val created = service().create(command(CandidateInput("analyst", sonnet.id), CandidateInput(AgentSnapshot.CLAUDE_CODE, sonnet.id)))
        service().start(created.benchmark.id)

        val attempts = benchmarks.findAttempts(created.benchmark.id)
        assertThat(attempts).hasSize(4)
        assertThat(attempts.filter { it.kind == TaskKind.PROMPT }.map { it.agentKey }.distinct()).containsExactly("analyst")
        assertThat(attempts.filter { it.kind == TaskKind.CODING_JOB }.map { it.harness }.distinct()).containsExactly(AgentHarness.CLAUDE_CODE)
        assertThat(BenchmarkExecutionPlanner.notApplicable(benchmarks.findById(created.benchmark.id)!!, suite.tasks).values).containsOnly(1)

        // the analyst's two attempts cost 0.10 each and one passed: the cost of an accepted result is 0.20
        attempts.filter { it.agentKey == "analyst" }.forEachIndexed { i, a ->
            benchmarks.saveAttempt(a.copy(status = AttemptStatus.SUCCEEDED, score = 80.0, passed = i == 0, cost = Money.usd("0.10")))
        }
        val board = service().leaderboard(created.benchmark.id)
        val row = board.overall.first { it.candidate.agentKey == "analyst" }
        assertThat(row.candidate.label).isEqualTo("Analyst · Sonnet")
        assertThat(row.notApplicable).isEqualTo(1)
        assertThat(row.costPerAccepted).isEqualTo(Money.usd("0.20"))
        assertThat(board.overall).describedAs("the coding candidate has no finished attempt yet").hasSize(1)
    }

    @Test
    fun `a candidate that can do none of the tasks gets an empty row, a benchmark nobody can do does not start`() {
        val promptOnly = Fixtures.suite(Fixtures.promptTask("only"))
        suites.save(promptOnly)
        val both = service().create(command(CandidateInput("analyst", sonnet.id), CandidateInput(AgentSnapshot.CLAUDE_CODE, haiku.id)).copy(suiteId = promptOnly.id))
        service().start(both.benchmark.id)
        val board = service().leaderboard(both.benchmark.id)
        val empty = board.overall.single { it.candidate.agentKey == AgentSnapshot.CLAUDE_CODE }
        assertThat(empty.attempts).isZero()
        assertThat(empty.notApplicable).isEqualTo(1)
        assertThat(empty.costPerAccepted).isNull()

        val nobody = service().create(command(CandidateInput(AgentSnapshot.CLAUDE_CODE, haiku.id)).copy(suiteId = promptOnly.id))
        assertThatThrownBy { service().start(nobody.benchmark.id) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("no candidate can do")
    }

    @Test
    fun `an agent deleted after create stops the start, a renamed one is refreshed`() {
        val created = service().create(command(CandidateInput("analyst", sonnet.id)))
        agents["analyst"] = analyst.copy(name = "Requirements analyst")
        service().start(created.benchmark.id)
        assertThat(benchmarks.findById(created.benchmark.id)!!.candidates.single().agentName).isEqualTo("Requirements analyst")
        assertThat(benchmarks.findAttempts(created.benchmark.id).map { it.agentName }.distinct()).containsExactly("Requirements analyst")

        val other = service().create(command(CandidateInput("analyst", haiku.id)))
        agents.remove("analyst")
        assertThatThrownBy { service().start(other.benchmark.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no longer exists")
    }

    @Test
    fun `a benchmark of models derives its candidates, model answer for prompt tasks, the coding harness's agent for coding tasks`() {
        val created = service().create(command(models = listOf(sonnet.id)).copy(codingHarness = AgentHarness.CLAUDE_CODE))
        assertThat(created.benchmark.legacy).isTrue()
        assertThat(created.candidateRefs.map { it.label }).containsExactly("Model answer · Sonnet", "Claude Code · Sonnet")

        service().start(created.benchmark.id)
        val attempts = benchmarks.findAttempts(created.benchmark.id)
        assertThat(attempts.map { it.kind to it.agentKey }.distinct())
            .containsExactlyInAnyOrder(TaskKind.PROMPT to AgentSnapshot.MODEL_ANSWER, TaskKind.CODING_JOB to AgentSnapshot.CLAUDE_CODE)
        val legacyAttempt = attempts.first { it.kind == TaskKind.CODING_JOB }.copy(agentKey = null, agentName = null, harness = null)
        assertThat(legacyAttempt.candidate(AgentHarness.CLAUDE_CODE).label).describedAs("an attempt stored before ADR 0059").isEqualTo("Claude Code · Sonnet")
        assertThat(service().leaderboard(created.benchmark.id).overall).isEmpty()
    }

    @Test
    fun `a model answer candidate answers with its environment as the system prompt, before the step and with its skills`() {
        val created = service().create(command(CandidateInput("analyst", sonnet.id)))
        service().start(created.benchmark.id)
        val requests = mutableListOf<LlmRequest>()
        every { gateway.chat(sonnet.ref, capture(requests)) } answers { result(sonnet.ref, "Answer.") }
        every { gateway.chat(judge.ref, any()) } answers { result(judge.ref, "{}", mapOf("criteria" to listOf(mapOf("name" to "Q", "score" to 8)))) }
        val attempt = benchmarks.findAttempts(created.benchmark.id).first()

        execute.execute(attempt.id)

        val system = requests.first().messages.first().content
        assertThat(system).startsWith(AgentEnvironmentText.PLATFORM_PREAMBLE).contains(AgentEnvironmentText.MODEL_ANSWER_PREAMBLE)
            .contains("## Your role\nYou analyse requirements.").contains("Cite evidence.").contains("# This step").contains("<skill key=\"adr\"")
        assertThat(system.indexOf("## Rules")).isLessThan(system.indexOf("# This step"))
        assertThat(benchmarks.findAttempt(attempt.id)!!.status).isEqualTo(AttemptStatus.SUCCEEDED)
    }

    @Test
    fun `a coding candidate's job runs its harness with its environment, skills and MCP servers, a benchmark of models without`() {
        val created = service().create(command(CandidateInput(AgentSnapshot.CLAUDE_CODE, sonnet.id)))
        service().start(created.benchmark.id)
        val specs = mutableListOf<RunnerJobSpec>()
        every { runner.submit(capture(specs), any()) } returns RunnerJobId.new()
        every { runner.result(any()) } returns null

        execute.execute(benchmarks.findAttempts(created.benchmark.id).first().id)

        val spec = specs.single()
        assertThat(spec.agent).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(spec.instructions).startsWith(AgentEnvironmentText.PLATFORM_PREAMBLE).contains(AgentEnvironmentText.CODING_AGENT_PREAMBLE)
            .contains("You write code.").contains("Cite evidence.")
        assertThat(spec.skills.map { it.key }).containsExactly("adr")
        assertThat(spec.mcpServers).containsExactly(AgentMcpServer("docs", "https://mcp.example.com"))

        val models = service().create(command(models = listOf(sonnet.id)))
        service().start(models.benchmark.id)
        execute.execute(benchmarks.findAttempts(models.benchmark.id).first { it.kind == TaskKind.CODING_JOB }.id)
        val legacy = specs.last()
        assertThat(legacy.agent).isEqualTo(AgentHarness.OPENCODE)
        assertThat(legacy.instructions).isNull()
        assertThat(legacy.skills).isEmpty()
        assertThat(legacy.mcpServers).isEmpty()
    }

    @Test
    fun `an attempt whose agent was deleted fails with the reason`() {
        val created = service().create(command(CandidateInput("analyst", sonnet.id)))
        service().start(created.benchmark.id)
        agents.remove("analyst")
        val attempt = benchmarks.findAttempts(created.benchmark.id).first()

        execute.execute(attempt.id)

        val stored = benchmarks.findAttempt(attempt.id)!!
        assertThat(stored.status).isEqualTo(AttemptStatus.FAILED)
        assertThat(stored.error).contains("no longer exists")
    }

    private fun result(model: ModelRef, content: String, structured: Map<String, Any?>? = null) =
        LlmResult(content = content, structured = structured, usage = TokenUsage(100, 10), latency = Duration.ofMillis(5), modelRef = model)
}
