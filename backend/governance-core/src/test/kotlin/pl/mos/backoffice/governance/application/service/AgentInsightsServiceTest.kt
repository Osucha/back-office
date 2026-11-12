package pl.mos.backoffice.governance.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.application.port.out.AgentInsightsReadModel
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.governance.domain.model.StepAgent
import pl.mos.backoffice.governance.domain.model.TaskStepExecution
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** ADR 0059: access, window and template names of the agent insights use case. */
class AgentInsightsServiceTest {

    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val clock = Clock.fixed(now)
    private val alpha = UUID.randomUUID()
    private val beta = UUID.randomUUID()
    private val asked = mutableListOf<Triple<ProjectFilter, Instant, Instant>>()
    private val step = TaskStepExecution(
        UUID.randomUUID(), true, "draft", 1, "agent-task", 1, "SUCCEEDED", StepAgent("claude-code", "Claude Code", "CLAUDE_CODE"), Money.usd("1"), Money.usd("1"), 5.0,
    )
    private val readModel = AgentInsightsReadModel { projects, from, to -> asked += Triple(projects, from, to); listOf(step) }
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = null
        override fun palette(projectId: UUID?): List<TaskSummary> =
            listOf(TaskSummary("agent-task", "Agent task", "", null, false, null, emptyList()))
    }

    private fun service(gate: FakeAuthorizationGate, tasks: TaskCatalog? = catalog) = AgentInsightsService(readModel, gate, clock, tasks)

    @Test
    fun `one project needs project view on it, the window defaults to the last 30 days`() {
        val gate = FakeAuthorizationGate.permitting(GovernancePermissions.PROJECT_VIEW, projects = setOf(alpha))
        val insights = service(gate).get(alpha, null, null)

        assertThat(gate.checked).containsExactly("${GovernancePermissions.PROJECT_VIEW}@$alpha")
        assertThat(asked.single()).isEqualTo(Triple(ProjectFilter.Only(setOf(alpha)), now.minus(Duration.ofDays(30)), now))
        assertThat(insights.projectId).isEqualTo(alpha)
        assertThat(insights.byAgent.single().agentKey).isEqualTo("claude-code")
        assertThat(insights.byTemplate.single().templateName).isEqualTo("Agent task")

        assertThatThrownBy { service(gate).get(beta, null, null) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `without a project the visible projects count, none visible reads nothing`() {
        val gate = FakeAuthorizationGate.permitting(GovernancePermissions.PROJECT_VIEW, projects = setOf(alpha, beta))
        service(gate, tasks = null).get(null, null, null).also { assertThat(it.byTemplate.single().templateName).isNull() }
        assertThat(asked.single().first).isEqualTo(ProjectFilter.Only(setOf(alpha, beta)))

        val nobody = service(FakeAuthorizationGate.denyAll()).get(null, null, null)
        assertThat(nobody.byAgent).isEmpty()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun `the window is at most 366 days and from must be before to`() {
        val gate = FakeAuthorizationGate.permitAll()
        assertThatThrownBy { service(gate).get(null, now.minus(Duration.ofDays(400)), now) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(gate).get(null, now, now) }.isInstanceOf(ValidationException::class.java)
        assertThat(service(gate).get(null, now.minus(Duration.ofDays(366)), now).from).isEqualTo(now.minus(Duration.ofDays(366)))
    }
}
