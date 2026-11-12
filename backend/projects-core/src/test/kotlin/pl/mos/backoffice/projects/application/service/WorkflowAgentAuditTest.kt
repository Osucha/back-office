package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.AgentAuditFindingKind
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.RequiredSkill
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.SkillInfo
import pl.mos.backoffice.shared.tasks.StepNeeds
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/** ADR 0061: `ManageWorkflows.auditAgents` resolves the draft's steps and agents through the TaskCatalog. */
class WorkflowAgentAuditTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val seenProjects = mutableListOf<UUID?>()

    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = null
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
        override fun stepNeeds(key: String, projectId: UUID?, overrides: TaskOverrides): StepNeeds? {
            seenProjects += projectId
            return when (key) {
                "broken" -> throw pl.mos.backoffice.shared.ValidationException("cycle", "extendsKey")
                "unknown" -> null
                else -> StepNeeds(overrides.agentKey ?: AgentSnapshot.MODEL_ANSWER, listOf(RequiredSkill("adr", "ADR format")), key == "plan")
            }
        }
        override fun agentProfile(key: String, projectId: UUID?): AgentProfile? = when (key) {
            "analyst" -> AgentProfile(UUID.randomUUID(), AgentSnapshot("analyst", "Analyst"), null, true, emptyList(), listOf(SkillInfo("adr", "ADR format", "")))
            AgentSnapshot.MODEL_ANSWER -> AgentProfile(null, AgentSnapshot.DEFAULT, null, true, emptyList(), emptyList())
            else -> null
        }
    }
    private val expressions = object : ExpressionEvaluator {
        override fun validate(expression: String): String? = null
        override fun evaluate(expression: String, view: WorkflowStateView): Boolean = true
    }

    private fun service(gate: FakeAuthorizationGate) = WorkflowService(
        mockk(relaxed = true), projects, mockk(relaxed = true), WorkflowValidator(), catalog, expressions, gate, RecordingAuditPort(), Clock.fixed(Fixtures.now),
    )

    private fun task(id: String, key: String, agent: String? = null) = WorkflowNode.Task(id, id, task = TaskBinding(key, TaskOverrides(agentKey = agent)))

    @Test
    fun `the audit groups the draft's task steps by agent and skips steps the validator reports`() {
        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        val nodes = listOf(
            WorkflowNode.Start("start"),
            task("story", "write-adr", "analyst"),
            task("plan", "plan"),
            task("ghost", "write-adr", "nobody"),
            task("broken", "broken"),
            task("unknown", "unknown"),
            task("blank", ""),
        )

        val report = service(viewer).auditAgents(project.id, nodes)

        assertThat(report.agents.map { it.agent.key to it.nodeIds }).containsExactly("analyst" to listOf("story"), AgentSnapshot.MODEL_ANSWER to listOf("plan"))
        assertThat(report.agents.first().missingSkills).isEmpty()
        assertThat(report.agents.last().findings.map { it.kind }).containsExactly(AgentAuditFindingKind.MISSING_SKILL, AgentAuditFindingKind.NEEDS_CODING_AGENT)
        assertThat(report.findings.single().kind).isEqualTo(AgentAuditFindingKind.UNKNOWN_AGENT)
        assertThat(seenProjects).containsOnly(project.id.value)
    }

    @Test
    fun `the audit needs project view`() {
        val stranger = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(UUID.randomUUID()))
        assertThatThrownBy { service(stranger).auditAgents(ProjectId(project.id.value), emptyList()) }.isInstanceOf(InsufficientPermissionException::class.java)
    }
}
