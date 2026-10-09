package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.SignalRun.SignalCommand
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.CiSignalReceived
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.CiSignalMatcher
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

class SignalServiceTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val ciNode = definition.requireNode("ci-tests")
    private val runs = mockk<WorkflowRunRepository>()
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val events = mutableListOf<DomainEvent>()
    private val audit = RecordingAuditPort()
    private val gate = Fixtures.ciGate(project.id, project.key)
    private val service = service(gate)

    private fun service(gate: FakeAuthorizationGate) = SignalService(runs, engine, EventPublisher { events += it }, gate, audit, Clock.fixed(now))

    private fun waiting(branch: String?, createdAt: java.time.Instant = now): WorkflowRun {
        var run = Fixtures.runningRun(project, definition).copy(createdAt = createdAt)
        branch?.let { run = run.withMergeRequest(MergeRequestSnapshot("https://gl/mr/1", it, "main")) }
        return run.waitingForCi(ciNode, "tests", now)
    }

    init {
        every { runs.save(any()) } answers { firstArg() }
    }

    @Test
    fun `a CI token signal with an explicit run id resumes that run and is audited`() {
        val run = waiting("feature/a")
        every { runs.findById(run.id) } returns run
        val result = service.signal(SignalCommand(project.id, "tests", CiSignalStatus.SUCCESS, runId = run.id))
        assertThat(result.resumed).isTrue()
        assertThat(result.runId).isEqualTo(run.id)
        verify { engine.resumeWithSignal(match { it.status == RunStatus.RUNNING && it.nodes.single().port == "success" }, match { it.signal == "tests" }) }
        assertThat(gate.checked).containsExactly("ci.signal@${project.id}")
        assertThat(audit.entries.single().operation).isEqualTo("CI_SIGNAL")
        assertThat(audit.entries.single().details["resumed"]).isEqualTo(true)
    }

    @Test
    fun `an unmatched signal is recorded but resumes nothing`() {
        every { runs.findWaiting(project.id) } returns emptyList()
        val result = service.signal(SignalCommand(project.id, "tests", CiSignalStatus.SUCCESS))
        assertThat(result.resumed).isFalse()
        assertThat(result.runId).isNull()
        assertThat(events.filterIsInstance<CiSignalReceived>().single().resumed).isFalse()
        verify(exactly = 0) { engine.resumeWithSignal(any(), any()) }
    }

    @Test
    fun `CI signals need ci signal on the project, manual signals need run signal`() {
        val run = waiting("feature/a")
        every { runs.findById(run.id) } returns run
        val developer = service(FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_SIGNAL, projects = setOf(project.id.value)))
        assertThatThrownBy { developer.signal(SignalCommand(project.id, "tests", CiSignalStatus.SUCCESS, runId = run.id)) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("ci.signal") }
        assertThatThrownBy { service.signalRun(run.id, "tests", CiSignalStatus.SUCCESS, null) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("run.signal") }
        assertThat(audit.entries).isEmpty()

        developer.signalRun(run.id, "tests", CiSignalStatus.FAIL, "red")
        verify { engine.resumeWithSignal(any(), match { it.status == CiSignalStatus.FAIL }) }
        assertThat(audit.operations()).containsExactly("RUN_SIGNAL_MANUAL")
    }

    @Test
    fun `matching by run id checks project and signal`() {
        val run = waiting("feature/a")
        assertThat(CiSignalMatcher.match(project.id, "tests", run.id, null, run, emptyList()).run).isEqualTo(run)
        assertThat(CiSignalMatcher.match(project.id, "lint", run.id, null, run, emptyList()).run).isNull()
        assertThat(CiSignalMatcher.match(Fixtures.project("OTHER").id, "tests", run.id, null, run, emptyList()).run).isNull()
        assertThat(CiSignalMatcher.match(project.id, "tests", WorkflowRunId.new(), null, null, emptyList()).message).contains("not found")
    }

    @Test
    fun `without a run id the branch decides, else the only candidate`() {
        val a = waiting("feature/a", now.minusSeconds(100))
        val b = waiting("feature/b", now)
        assertThat(CiSignalMatcher.match(project.id, "tests", null, "feature/a", null, listOf(b, a)).run?.id).isEqualTo(a.id)
        assertThat(CiSignalMatcher.match(project.id, "tests", null, null, null, listOf(b, a)).run).isNull()
        assertThat(CiSignalMatcher.match(project.id, "tests", null, "feature/zzz", null, listOf(b, a)).run).isNull()
        assertThat(CiSignalMatcher.match(project.id, "tests", null, "feature/zzz", null, listOf(a)).run?.id).isEqualTo(a.id)
        assertThat(CiSignalMatcher.match(project.id, "other", null, null, null, listOf(a)).run).isNull()
    }
}
