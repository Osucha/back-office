package pl.mos.backoffice.projects.adapter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.security.CiTokenVerifier
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.SkillSnapshot
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.Pricing
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal Spring Boot application for projects-adapters integration tests. */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.projects"])
class ProjectsTestApplication

/** Scripted LLM: answers are taken from [answers] in order (the last one repeats); prompts are recorded. */
class FakeLlmGateway : LlmGateway {
    val prompts = CopyOnWriteArrayList<LlmRequest>()
    val answers = CopyOnWriteArrayList<(LlmRequest) -> LlmResult>()
    var configured = setOf(Provider.ANTHROPIC)

    fun answer(content: String, structured: Map<String, Any?>? = null, usage: TokenUsage = TokenUsage(1000, 100)) {
        answers += { _ ->
            LlmResult(content, structured, usage, latency = Duration.ofMillis(5), modelRef = ModelRef(Provider.ANTHROPIC, "claude-opus-5"))
        }
    }

    fun reset() {
        prompts.clear()
        answers.clear()
    }

    override fun chat(model: ModelRef, request: LlmRequest): LlmResult {
        prompts += request
        val index = (prompts.size - 1).coerceAtMost(answers.size - 1)
        if (index < 0) throw IntegrationException(model.provider.name.lowercase(), "no scripted answer")
        return answers[index](request)
    }

    override fun isConfigured(provider: Provider): Boolean = provider in configured
}

class FakeModelCatalog : ModelCatalog {
    val profiles = mutableMapOf<ModelProfileId, ModelProfileSnapshot>()

    fun add(id: ModelProfileId = ModelProfileId.new(), modelId: String = "claude-opus-5"): ModelProfileId {
        profiles[id] = ModelProfileSnapshot(id, "Profile $modelId", ModelRef(Provider.ANTHROPIC, modelId), Pricing(BigDecimal("5"), BigDecimal("25")), enabled = true)
        return id
    }

    override fun find(id: ModelProfileId): ModelProfileSnapshot? = profiles[id]
    override fun findAllEnabled(): List<ModelProfileSnapshot> = profiles.values.filter { it.enabled }
    override fun findPricing(ref: ModelRef): Pricing? = profiles.values.firstOrNull { it.ref.label == ref.label }?.pricing
}

/**
 * In-memory stand-in for the Task Library: templates are registered by key with a prompt and executor.
 * Overrides are applied the way the real resolver does (append/replace prompts, config merge, model).
 */
class FakeTaskCatalog : TaskCatalog {
    val templates = ConcurrentHashMap<String, ResolvedTask>()

    fun llm(key: String, user: String, system: String? = null, outputSchema: String? = null, confidenceField: String? = null, produces: List<String> = emptyList(), consumes: List<String> = emptyList(), interfaces: List<String> = emptyList(), ports: List<String> = listOf("success", "failure")) =
        register(ResolvedTask(key, key, TaskExecutor.LLM, null, TaskPrompt(system, user), emptyList(), emptyMap(), outputSchema, confidenceField, produces, consumes, interfaces, listOf(key), ports))

    fun register(task: ResolvedTask): ResolvedTask = task.also { templates[it.key] = it }

    /** The seeded templates the default workflows refer to, all LLM-backed so tests do not need a runner or Jira. */
    fun seedDefaults() {
        llm("fetch-issue", "fetch {{input.issueKey}}", produces = listOf("issue"))
        llm("spec-kit-specify", "specify {{input.issueKey}}\n{{feedback}}", produces = listOf("spec"), interfaces = listOf("Specifier"))
        llm("clarify-spec", "clarify {{artifacts.spec}}", outputSchema = CLARIFY_SCHEMA, confidenceField = "confidence", produces = listOf("confidence", "clarifications"), consumes = listOf("spec"), interfaces = listOf("Clarifier"))
        llm("spec-kit-plan", "plan {{artifacts.spec}}", produces = listOf("plan"), interfaces = listOf("Planner"))
        llm("spec-kit-tasks", "tasks {{artifacts.plan}}", produces = listOf("tasks"), interfaces = listOf("TaskPlanner"))
        llm("implement-opencode", "implement {{artifacts.tasks}}\n{{feedback}}", produces = listOf("diff"), interfaces = listOf("Implementer"))
        llm("code-review", "review this diff:\n{{artifacts.diff}}", system = "You are a reviewer.", outputSchema = REVIEW_SCHEMA, produces = listOf("review"), consumes = listOf("diff"), interfaces = listOf("Reviewer"))
        llm("transition-issue", "transition {{issue.key}}")
    }

    override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? {
        val base = templates[key] ?: return null
        val system = listOfNotNull(base.prompt.system, overrides.systemPromptAppend).joinToString("\n\n").ifBlank { null }
        val user = overrides.promptReplace ?: listOfNotNull(base.prompt.user, overrides.promptAppend).joinToString("\n\n").ifBlank { null }
        return base.copy(
            modelProfileId = overrides.modelProfileId ?: base.modelProfileId,
            prompt = TaskPrompt(system, user),
            skills = (base.skills + overrides.addSkills.map { SkillSnapshot(it, it, "# $it") }).filterNot { it.key in overrides.removeSkills },
            config = base.config + overrides.config,
        )
    }

    override fun palette(projectId: UUID?): List<TaskSummary> =
        templates.values.map { TaskSummary(it.key, it.name, "template ${it.key}", it.executor, it.interfaces, false, null, it.ports) }.sortedBy { it.key }

    companion object {
        const val CLARIFY_SCHEMA = """{"type":"object","properties":{"confidence":{"type":"number"},"questions":{"type":"array","items":{"type":"string"}}},"required":["confidence","questions"]}"""
        const val REVIEW_SCHEMA = """{"type":"object","properties":{"summary":{"type":"string"},"findings":{"type":"array"}},"required":["summary","findings"]}"""
    }
}

class RecordingUsageLedger : UsageLedger {
    val records = CopyOnWriteArrayList<UsageRecord>()
    override fun record(record: UsageRecord) { records += record }
    override fun summary(query: UsageQuery, groupBy: UsageGroupBy) = UsageSummary(query.from, query.to, groupBy, Money.ZERO, TokenUsage.ZERO, emptyList())
    override fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> = PageOf(emptyList(), page, size, 0)
    override fun totalCost(query: UsageQuery): Money = Money.ZERO
}

class UnavailableAgentRunner : AgentRunner {
    override fun start(spec: RunnerJobSpec): RunnerJobId = throw IntegrationException("runner", "runner disabled in tests")
    override fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult = throw IntegrationException("runner", "runner disabled in tests")
    override fun status(jobId: RunnerJobId): RunnerJobStatus = RunnerJobStatus.FAILED
    override fun cancel(jobId: RunnerJobId) = Unit
    override fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine> = emptyList()
    override fun isAvailable(): Boolean = false
}

/**
 * Runner v2 stand-in (non-blocking): records every `submit` with its continuation; a test finishes a job with
 * [finish], which stores the result and enqueues the continuation the way the runner control plane does.
 */
class RecordingAgentRunner : AgentRunner {
    data class Submission(val jobId: RunnerJobId, val spec: RunnerJobSpec, val continuation: WorkRequest)

    val submissions = CopyOnWriteArrayList<Submission>()
    val results = ConcurrentHashMap<RunnerJobId, RunnerResult>()
    val cancelled = CopyOnWriteArrayList<RunnerJobId>()
    @Volatile var queue: WorkQueue? = null

    override fun submit(spec: RunnerJobSpec, continuation: WorkRequest): RunnerJobId {
        submissions.firstOrNull { it.spec.jobLabel == spec.jobLabel }?.let { return it.jobId }
        return RunnerJobId.new().also { submissions += Submission(it, spec, continuation) }
    }

    override fun result(jobId: RunnerJobId): RunnerResult? = results[jobId]

    fun finish(jobId: RunnerJobId, result: RunnerResult) {
        results[jobId] = result
        val submission = submissions.first { it.jobId == jobId }
        val continuation = submission.continuation
        queue!!.enqueue(continuation.copy(payload = continuation.payload + mapOf("jobId" to jobId.toString(), "status" to result.status.name)))
    }

    override fun start(spec: RunnerJobSpec): RunnerJobId = throw IntegrationException("runner", "blocking start is not used any more")
    override fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult = throw IntegrationException("runner", "blocking await is not used any more")
    override fun status(jobId: RunnerJobId): RunnerJobStatus = results[jobId]?.status ?: RunnerJobStatus.RUNNING
    override fun cancel(jobId: RunnerJobId) { cancelled += jobId }
    override fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine> = emptyList()
    override fun isAvailable(): Boolean = true
}

/** Shared kernel beans normally provided by other modules, plus a throw-away PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
class ProjectsTestBeans : ProjectsStandIns() {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
}

/** The stand-ins without a database (the two-replica tests point several contexts at one container). */
open class ProjectsStandIns {

    val events = CopyOnWriteArrayList<DomainEvent>()

    @Bean fun clock(): Clock = Clock.SYSTEM

    @Bean @Primary fun eventPublisher(): EventPublisher = EventPublisher { events += it }

    @Bean fun llmGateway(): FakeLlmGateway = FakeLlmGateway()

    @Bean fun modelCatalog(): FakeModelCatalog = FakeModelCatalog()

    @Bean fun taskCatalog(): FakeTaskCatalog = FakeTaskCatalog().also { it.seedDefaults() }

    @Bean fun usageLedger(): RecordingUsageLedger = RecordingUsageLedger()

    @Bean fun agentRunner(): RecordingAgentRunner = RecordingAgentRunner()

    /** Artefacts above 64 kB go here (the app wires the S3 or filesystem store from blob-storage). */
    @Bean fun blobStore(): pl.mos.backoffice.shared.testing.InMemoryBlobStore = pl.mos.backoffice.shared.testing.InMemoryBlobStore()

    // ---- identity context stand-ins (see TestSecurity.kt)

    @Bean fun authorizationGate(): TestAuthorizationGate = TestAuthorizationGate()

    @Bean fun auditPort(): ConcurrentRecordingAuditPort = ConcurrentRecordingAuditPort()

    @Bean fun projectAccessProvisioner(): RecordingProjectAccessProvisioner = RecordingProjectAccessProvisioner()

    @Bean fun projectAccessDirectory(): FakeProjectAccessDirectory = FakeProjectAccessDirectory()

    @Bean
    fun testAuthenticationFilter(gate: TestAuthorizationGate, verifier: CiTokenVerifier): FilterRegistrationBean<TestAuthenticationFilter> =
        FilterRegistrationBean(TestAuthenticationFilter(gate, verifier)).apply { addUrlPatterns("/api/*", "/ci/*") }
}
