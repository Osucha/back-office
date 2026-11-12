package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.mos.backoffice.projects.application.port.`in`.CheckAgentCoherence
import pl.mos.backoffice.projects.application.port.`in`.CoherenceFinding
import pl.mos.backoffice.projects.application.port.`in`.CoherenceQuote
import pl.mos.backoffice.projects.application.port.`in`.CoherenceReport
import pl.mos.backoffice.projects.application.port.`in`.CoherenceSeverity
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.AgentAuditEntry
import pl.mos.backoffice.projects.domain.service.AgentAuditFinding
import pl.mos.backoffice.projects.domain.service.AgentAuditFindingKind
import pl.mos.backoffice.projects.domain.service.AgentAuditReport
import pl.mos.backoffice.projects.domain.service.AgentAuditSeverity
import pl.mos.backoffice.projects.domain.service.AuditRequiredSkill
import pl.mos.backoffice.projects.domain.service.AuditRule
import pl.mos.backoffice.projects.domain.service.AuditSkill
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import java.math.BigDecimal
import java.util.UUID

/** ADR 0061: `POST /workflows/agent-audit` and `POST /agents/{agentId}/coherence-check` in the shapes of openapi.yaml. */
class AgentAuditControllerTest {

    private var report: AgentAuditReport? = null
    private val audited = mutableListOf<List<Any?>>()

    /** ADR 0061: `auditAgents` answers [report] and records its arguments (the project id arrives unboxed). */
    private val workflows = Mockito.mock(ManageWorkflows::class.java) { invocation ->
        if (invocation.method.name.startsWith("auditAgents")) report.also { audited += invocation.arguments.toList() } else null
    }
    private val project = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val checked = mutableListOf<Pair<UUID, ProjectId?>>()
    private val coherence = object : CheckAgentCoherence {
        override fun check(agentId: UUID, projectId: ProjectId?): CoherenceReport {
            checked += agentId to projectId
            return CoherenceReport(
                "reviewer", "Claude Test", Money(BigDecimal("0.012")), listOf("preamble", "rule:ground"),
                listOf(CoherenceFinding(CoherenceSeverity.CONTRADICTION, "approve vs tests", listOf(CoherenceQuote("rule:ground", "Never")), "Drop it")),
            )
        }
    }
    private val mvc = MockMvcBuilders.standaloneSetup(
        WorkflowController(workflows, Mockito.mock(StartRun::class.java), RestMapper(), Mockito.mock(ManageWorkflowLibrary::class.java)),
        AgentCoherenceController(coherence),
    ).build()

    @Test
    fun `the agent audit maps the draft's nodes and returns entries and findings`() {
        report = AgentAuditReport(
            agents = listOf(
                AgentAuditEntry(
                    agent = AgentSnapshot("coder", "Coder", AgentHarness.CLAUDE_CODE, skills = listOf("fmt")),
                    agentId = agentId, projectId = project, nodeIds = listOf("impl"),
                    rules = listOf(AuditRule("ground", "Ground rules", automatic = true)), skills = listOf(AuditSkill("fmt", "Formatting")),
                    requiredSkills = listOf(AuditRequiredSkill("adr", null, listOf("impl"))), missingSkills = listOf("adr"),
                    findings = listOf(AgentAuditFinding(AgentAuditFindingKind.MISSING_SKILL, AgentAuditSeverity.WARNING, "lacks adr", listOf("impl"), "adr")),
                ),
                AgentAuditEntry(AgentSnapshot.DEFAULT, null, null, listOf("ask"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            ),
            findings = listOf(AgentAuditFinding(AgentAuditFindingKind.UNKNOWN_AGENT, AgentAuditSeverity.ERROR, "agent ghost does not exist", listOf("x"), "ghost")),
        )

        val body = """{"projectId":"$project","nodes":[{"id":"impl","kind":"TASK","name":"Implement","task":{"templateKey":"implement-opencode","overrides":{"agentKey":"coder"}}}]}"""
        mvc.perform(post("/api/v1/workflows/agent-audit").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agents.length()").value(2))
            .andExpect(jsonPath("$.agents[0].agent.key").value("coder"))
            .andExpect(jsonPath("$.agents[0].agent.harness").value("CLAUDE_CODE"))
            .andExpect(jsonPath("$.agents[0].agentId").value(agentId.toString()))
            .andExpect(jsonPath("$.agents[0].scope.type").value("PROJECT"))
            .andExpect(jsonPath("$.agents[0].scope.projectId").value(project.toString()))
            .andExpect(jsonPath("$.agents[0].nodeIds[0]").value("impl"))
            .andExpect(jsonPath("$.agents[0].rules[0].automatic").value(true))
            .andExpect(jsonPath("$.agents[0].skills[0].name").value("Formatting"))
            .andExpect(jsonPath("$.agents[0].requiredSkills[0].key").value("adr"))
            .andExpect(jsonPath("$.agents[0].missingSkills[0]").value("adr"))
            .andExpect(jsonPath("$.agents[0].findings[0].kind").value("MISSING_SKILL"))
            .andExpect(jsonPath("$.agents[0].findings[0].severity").value("WARNING"))
            .andExpect(jsonPath("$.agents[1].scope.type").value("GLOBAL"))
            .andExpect(jsonPath("$.findings[0].kind").value("UNKNOWN_AGENT"))
            .andExpect(jsonPath("$.findings[0].key").value("ghost"))

        val (projectArg, nodesArg) = audited.single()
        assertThat(projectArg).isIn(project, ProjectId(project))
        val node = (nodesArg as List<*>).single() as WorkflowNode.Task
        assertThat(node.id).isEqualTo("impl")
        assertThat(node.task.templateKey).isEqualTo("implement-opencode")
        assertThat(node.task.overrides.agentKey).isEqualTo("coder")
    }

    @Test
    fun `the agent audit without a project is a bad request`() {
        mvc.perform(post("/api/v1/workflows/agent-audit").contentType(MediaType.APPLICATION_JSON).content("""{"nodes":[]}"""))
            .andExpect(status().isBadRequest)
        assertThat(audited).isEmpty()
    }

    @Test
    fun `the coherence check passes the agent and the optional project and returns the report`() {
        mvc.perform(post("/api/v1/agents/$agentId/coherence-check").param("projectId", project.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agentKey").value("reviewer"))
            .andExpect(jsonPath("$.model").value("Claude Test"))
            .andExpect(jsonPath("$.cost.amount").value(0.012))
            .andExpect(jsonPath("$.cost.currency").value("USD"))
            .andExpect(jsonPath("$.sources[1]").value("rule:ground"))
            .andExpect(jsonPath("$.findings[0].severity").value("CONTRADICTION"))
            .andExpect(jsonPath("$.findings[0].quotes[0].source").value("rule:ground"))
            .andExpect(jsonPath("$.findings[0].suggestion").value("Drop it"))
        mvc.perform(post("/api/v1/agents/$agentId/coherence-check")).andExpect(status().isOk)

        assertThat(checked).containsExactly(agentId to ProjectId(project), agentId to null)
    }
}
