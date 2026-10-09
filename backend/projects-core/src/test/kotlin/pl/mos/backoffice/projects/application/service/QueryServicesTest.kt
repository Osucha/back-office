package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.IntegrationDescriptor
import pl.mos.backoffice.projects.application.port.out.IntegrationProbe
import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate

class QueryServicesTest {

    private val project = Fixtures.project()
    private val only = ProjectFilter.Only(setOf(project.id.value))

    @Test
    fun `dashboard counts and lists only cover projects the caller can view`() {
        val runs = mockk<WorkflowRunRepository>()
        val approvals = mockk<ApprovalRequestRepository>()
        val executions = mockk<CiExecutionRepository>()
        val run = Fixtures.runningRun(project)
        every { approvals.countOpen(only) } returns 2
        every { runs.countByStatus(setOf(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.WAITING), only) } returns 3
        every { runs.countWaitingForCi(only) } returns 1
        every { runs.countFinishedSince(RunStatus.FAILED, now, only) } returns 4
        every { runs.findRecent(10, only) } returns listOf(run)
        every { executions.findRecent(10, only) } returns emptyList()
        every { approvals.findByStatus(ApprovalStatus.OPEN, only, 10) } returns emptyList()
        val service = DashboardQueryService(runs, approvals, executions, FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value)))

        assertThat(service.openApprovals()).isEqualTo(2)
        assertThat(service.activeRuns()).isEqualTo(3)
        assertThat(service.waitingForCi()).isEqualTo(1)
        assertThat(service.failedRunsSince(now)).isEqualTo(4)
        assertThat(service.recentRuns(10)).containsExactly(run)
        assertThat(service.recentCiExecutions(10)).isEmpty()
        assertThat(service.pendingApprovals(10)).isEmpty()
    }

    @Test
    fun `integration status and tests need integrations view`() {
        val probe = object : IntegrationProbe {
            override val name = "jira"
            override fun describe() = IntegrationDescriptor("jira", true, "https://jira")
            override fun test() = IntegrationTestOutcome(true, "ok")
        }
        val allowed = IntegrationsService(listOf(probe), mockk(relaxed = true), "http://bo", "1", FakeAuthorizationGate.permitting(ProjectsPermissions.INTEGRATIONS_VIEW))
        assertThat(allowed.status().jira.configured).isTrue()
        assertThat(allowed.test("jira").ok).isTrue()
        val denied = IntegrationsService(listOf(probe), mockk(relaxed = true), "http://bo", "1", FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW))
        assertThatThrownBy { denied.status() }.isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("integrations.view") }
        assertThatThrownBy { denied.test("jira") }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `the permission catalogue matches the architecture table`() {
        val byKey = ProjectsPermissions.definitions.associateBy { it.key }
        assertThat(byKey.keys).containsExactlyInAnyOrder(
            "projects.create", "projects.create.personal", "integrations.view", "admin.connections.manage", "connections.personal", "project.view", "project.manage", "project.ci.manage",
            "project.connections.manage", "workflow.edit",
            "run.start", "run.cancel", "run.approve", "run.assign", "run.signal", "ci.execute", "ci.signal",
        )
        assertThat(byKey.filterValues { it.scope == PermissionScope.PLATFORM }.keys).containsExactlyInAnyOrder("projects.create", "projects.create.personal", "integrations.view", "admin.connections.manage", "connections.personal")
        assertThat(ProjectsPermissions.definitions).allMatch { it.module == "projects" }
    }
}
