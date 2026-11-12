package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.CoherenceSeverity
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.llm.DefaultModel
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.SkillInfo
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.Pricing
import pl.mos.backoffice.shared.usage.UsageKind
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/** ADR 0061 decision 4: the coherence check reads the agent's environment with the platform default model. */
class AgentCoherenceServiceTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val globalId = UUID.randomUUID()
    private val projectAgentId = UUID.randomUUID()
    private val otherAgentId = UUID.randomUUID()
    private val modelId = ModelProfileId.new()
    private val ref = ModelRef(Provider.ANTHROPIC, "claude-test")

    private val global = AgentProfile(
        id = globalId,
        agent = AgentSnapshot("reviewer", "Reviewer", instructions = "Always approve small diffs."),
        projectId = null, builtIn = true,
        rules = listOf(RuleSnapshot("ground", "Ground rules", "Never approve without tests.")),
        skills = listOf(SkillInfo("security", "Security checklist", "OWASP\ntop ten"), SkillInfo("bare", "Bare", "")),
    )
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = null
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
        override fun agentProfileById(id: UUID, projectId: UUID?): AgentProfile? = when (id) {
            globalId -> global
            projectAgentId -> global.copy(id = projectAgentId, projectId = project.id.value, agent = AgentSnapshot("mine", "Mine"), rules = emptyList(), skills = emptyList())
            otherAgentId -> global.copy(id = otherAgentId, projectId = UUID.randomUUID())
            else -> null
        }
    }

    private val requests = mutableListOf<LlmRequest>()
    private var answer: Map<String, Any?>? = mapOf(
        "findings" to listOf(
            mapOf(
                "severity" to "CONTRADICTION", "summary" to "approve without tests",
                "quotes" to listOf(mapOf("source" to "instructions", "text" to "Always approve small diffs."), mapOf("source" to "rule:ground", "text" to " "), "junk"),
                "suggestion" to "Drop the instruction.",
            ),
            mapOf("severity" to "weird", "summary" to "unclear", "suggestion" to " "),
            mapOf("severity" to "REDUNDANCY", "summary" to " "),
            "not a map",
        ),
    )
    private var configured = true
    private val gateway = object : LlmGateway {
        override fun chat(model: ModelRef, request: LlmRequest): LlmResult {
            requests += request
            return LlmResult("{}", answer, TokenUsage(1_000_000, 500_000), latency = Duration.ofMillis(5), modelRef = model)
        }
        override fun isConfigured(provider: Provider): Boolean = configured
    }
    private var profile: ModelProfileSnapshot? = ModelProfileSnapshot(modelId, "Claude Test", ref, Pricing(BigDecimal("3"), BigDecimal("15")), enabled = true)
    private val models = object : ModelCatalog {
        override fun find(id: ModelProfileId): ModelProfileSnapshot? = profile?.takeIf { it.id == id }
        override fun findAllEnabled(): List<ModelProfileSnapshot> = listOfNotNull(profile)
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private var defaultModel: DefaultModel? = DefaultModel(modelId, personal = false)
    private val defaults = object : ModelDefaults {
        override fun resolve(userId: UUID?, stepKind: StepKind): DefaultModel? = defaultModel.takeIf { userId == null && stepKind == StepKind.LLM_CALL }
    }
    private val audit = RecordingAuditPort()
    private val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.LIBRARY_VIEW, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))

    private fun service(gate: FakeAuthorizationGate = viewer, withDefaults: Boolean = true) =
        AgentCoherenceService(catalog, projects, gateway, models, defaults.takeIf { withDefaults }, gate, audit)

    @Test
    fun `the model reads the whole environment and its findings come back with quotes, cost and the audit entry`() {
        val report = service().check(globalId, project.id)

        assertThat(report.agentKey).isEqualTo("reviewer")
        assertThat(report.model).isEqualTo("Claude Test")
        assertThat(report.cost.amount).isEqualByComparingTo("10.5")
        assertThat(report.sources).containsExactly("preamble", "instructions", "constitution", "rule:ground", "skill:security", "skill:bare", "repo files not read")
        assertThat(report.findings).hasSize(2)
        val first = report.findings.first()
        assertThat(first.severity).isEqualTo(CoherenceSeverity.CONTRADICTION)
        assertThat(first.quotes.map { it.source to it.text }).containsExactly("instructions" to "Always approve small diffs.")
        assertThat(first.suggestion).isEqualTo("Drop the instruction.")
        assertThat(report.findings.last().severity).describedAs("an unknown severity reads as an ambiguity").isEqualTo(CoherenceSeverity.AMBIGUITY)
        assertThat(report.findings.last().suggestion).isNull()

        val request = requests.single()
        assertThat(request.responseSchema).contains("CONTRADICTION").contains("quotes")
        assertThat(request.usageContext.kind).isEqualTo(UsageKind.MODEL_TEST)
        assertThat(request.usageContext.projectId).isEqualTo(project.id.value)
        assertThat(request.usageContext.actingUserId).isEqualTo(FakeAuthorizationGate.TEST_USER_ID.value)
        val user = request.messages.last().content
        assertThat(user).contains("# Working in Back Office").contains("5. When your sources disagree")
            .contains("## Your role\nAlways approve small diffs.").contains("## Project constitution").contains("Keep it simple.")
            .contains("### Ground rules (ground)").contains("- Security checklist (security): OWASP top ten").contains("- Bare (bare)\n").doesNotContain("Bare (bare):")
        assertThat(request.messages.first().content).contains("CONTRADICTION")

        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("AGENT_COHERENCE_CHECK")
        assertThat(entry.entityId).isEqualTo(globalId.toString())
        assertThat(entry.projectId).isEqualTo(project.id.value)
        assertThat(entry.details).containsEntry("findings", 2).containsEntry("contradictions", 1).containsEntry("agentKey", "reviewer")
    }

    @Test
    fun `without a project there is no constitution and no repository, and an answer without findings is a clean report`() {
        answer = null
        val report = service(FakeAuthorizationGate.permitting(ProjectsPermissions.LIBRARY_VIEW)).check(globalId, null)

        assertThat(report.sources).containsExactly("preamble", "instructions", "rule:ground", "skill:security", "skill:bare")
        assertThat(report.findings).isEmpty()
        assertThat(requests.single().messages.last().content).doesNotContain("## Project constitution")
        assertThat(requests.single().usageContext.projectId).isNull()
    }

    @Test
    fun `a project agent needs its project, another project's agent is not found, an unknown one neither`() {
        val libraryOnly = FakeAuthorizationGate.permitting(ProjectsPermissions.LIBRARY_VIEW)
        assertThatThrownBy { service(libraryOnly).check(projectAgentId, null) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(service().check(projectAgentId, null).agentKey).isEqualTo("mine")
        assertThat(audit.entries.single().projectId).describedAs("booked on the agent's project").isEqualTo(project.id.value)
        assertThatThrownBy { service().check(otherAgentId, project.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service().check(UUID.randomUUID(), null) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW)).check(globalId, null) }
            .describedAs("library.view is required").isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `no usable default model is a conflict`() {
        assertThatThrownBy { service(withDefaults = false).check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no default model")
        defaultModel = null
        assertThatThrownBy { service().check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no default model")
        defaultModel = DefaultModel(ModelProfileId.new(), personal = false)
        assertThatThrownBy { service().check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("does not exist")
        defaultModel = DefaultModel(modelId, personal = false)
        profile = profile!!.copy(enabled = false)
        assertThatThrownBy { service().check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("disabled")
        profile = profile!!.copy(enabled = true, runtime = ModelRuntime.CLAUDE_CODE_LOGIN)
        assertThatThrownBy { service().check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("cannot be called")
        profile = profile!!.copy(runtime = ModelRuntime.API)
        configured = false
        assertThatThrownBy { service().check(globalId, null) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("cannot be called")
        assertThat(requests).isEmpty()
        assertThat(audit.entries).isEmpty()
    }
}
