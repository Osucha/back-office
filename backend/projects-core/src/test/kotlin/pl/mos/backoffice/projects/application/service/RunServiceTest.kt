package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.projects.Fixtures.alice

class RunServiceTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val runs = mockk<WorkflowRunRepository>()
    private val workflows = mockk<WorkflowDefinitionRepository>()
    private val projects = mockk<ProjectRepository>()
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val progress = mockk<RecordRunProgress>()
    private val audit = RecordingAuditPort()
    private val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_START, ProjectsPermissions.RUN_CANCEL, projects = setOf(project.id.value))
    private val service = service(gate)

    private fun service(gate: FakeAuthorizationGate) =
        RunService(runs, workflows, projects, mockk<RunLogRepository>(), engine, progress, WorkflowValidator(), EventPublisher.NOOP, gate, audit, Clock.fixed(now))

    init {
        every { runs.save(any()) } answers { firstArg() }
        every { workflows.findById(definition.id) } returns definition
        every { projects.findById(project.id) } returns project
    }

    @Test
    fun `start creates a RUNNING run pinned to the workflow version and hands it to the engine`() {
        val run = service.start(definition.id, RunInput("SHOP-1"), TriggerSource.API)
        assertThat(run.status).isEqualTo(RunStatus.RUNNING)
        assertThat(run.trigger.type).isEqualTo(TriggerSource.API)
        assertThat(run.workflowVersion).isEqualTo(1)
        assertThat(run.startedBy).isEqualTo(alice)
        verify { engine.start(run) }
        assertThat(gate.checked).containsExactly("run.start@${project.id}")
        assertThat(audit.entries.single().operation).isEqualTo("RUN_START")
        assertThat(audit.entries.single().details["trigger"]).isEqualTo("API")

        every { workflows.findById(definition.id) } returns definition.copy(enabled = false)
        assertThatThrownBy { service.start(definition.id, RunInput("SHOP-1")) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `retry re-opens a FAILED run from its failing node on the same engine thread`() {
        val failed = Fixtures.runningRun(project, definition)
            .nodeStarted(definition.requireNode("fetch-issue"), now).nodeFinished("fetch-issue", "success", now)
            .nodeStarted(definition.requireNode("specify"), now).nodeFailed("specify", "runner exploded", now)
            .failed(RunFailure("specify", "runner exploded"), now)
        every { runs.findById(failed.id) } returns failed

        val retried = service.retry(failed.id)
        assertThat(retried.status).isEqualTo(RunStatus.RUNNING)
        assertThat(retried.id).isEqualTo(failed.id)
        verify { engine.retry(retried, "specify") }
        assertThat(audit.operations()).containsExactly("RUN_RETRY")

        every { runs.findById(failed.id) } returns failed.retry(now)
        assertThatThrownBy { service.retry(failed.id) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `cancel stops the engine and finishes the run as CANCELLED`() {
        val running = Fixtures.runningRun(project, definition)
        every { runs.findById(running.id) } returns running
        every { progress.finished(running.id, RunStatus.CANCELLED, null, null) } returns running.cancelled(now)
        assertThat(service.cancel(running.id).status).isEqualTo(RunStatus.CANCELLED)
        verify { engine.cancel(running.id) }
        assertThat(audit.operations()).containsExactly("RUN_CANCEL")
        every { runs.findById(running.id) } returns running.cancelled(now)
        assertThatThrownBy { service.cancel(running.id) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `starting, retrying, cancelling and reading need their own permission on the run project`() {
        val running = Fixtures.runningRun(project, definition)
        every { runs.findById(running.id) } returns running
        val viewer = service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value)))
        assertThatThrownBy { viewer.start(definition.id, RunInput("SHOP-1")) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("run.start") }
        assertThatThrownBy { viewer.cancel(running.id) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("run.cancel") }
        assertThatThrownBy { viewer.retry(running.id) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("run.start") }
        assertThat(viewer.get(running.id)).isEqualTo(running)
        val starter = service(FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_START, projects = setOf(project.id.value)))
        assertThatThrownBy { starter.get(running.id) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("project.view") }
        verify(exactly = 0) { engine.start(any()) }
        assertThat(audit.entries).isEmpty()
    }

    @Test
    fun `the run list is restricted to projects the caller can view`() {
        val viewerGate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        every { runs.find(RunFilter(), ProjectFilter.Only(setOf(project.id.value)), 0, 20) } returns PageOf(emptyList(), 0, 20, 0)
        assertThat(service(viewerGate).list(RunFilter(), 0, 20).totalElements).isZero()
        verify { runs.find(RunFilter(), ProjectFilter.Only(setOf(project.id.value)), 0, 20) }
        every { runs.find(RunFilter(), ProjectFilter.All, 0, 20) } returns PageOf(emptyList(), 0, 20, 0)
        service(FakeAuthorizationGate.permitAll()).list(RunFilter(), 0, 20)
        verify { runs.find(RunFilter(), ProjectFilter.All, 0, 20) }
    }
}
