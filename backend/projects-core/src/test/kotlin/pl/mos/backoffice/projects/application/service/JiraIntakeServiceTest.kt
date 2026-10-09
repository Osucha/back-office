package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.ConnectionFakes.WRITE
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake.IntakeCommand
import pl.mos.backoffice.projects.application.port.out.IssueTrackerUnavailableException
import pl.mos.backoffice.projects.application.port.out.JiraIntakeRepository
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.domain.model.IssueTrackerBinding
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.StatusChange
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.JiraIntakePlanner
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** ADR 0033: the Jira intake watcher reads with ISSUE_READ only, starts a run once per transition and backs off on errors. */
class JiraIntakeServiceTest {

    private var now: Instant = Fixtures.now
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val project = Fixtures.project().copy(issueTracker = IssueTrackerBinding("SHOP", "labels = ai"))
    private val workflow = Fixtures.issueWorkflow(project).copy(triggers = listOf(Trigger(TriggerType.MANUAL), Trigger(TriggerType.JIRA_TRANSITION, "Ready for AI")))
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val workflows = mockk<WorkflowDefinitionRepository> { every { findById(workflow.id) } returns workflow }
    private val connections = ConnectionFakes.InMemoryConnections()
    private val trackers = ConnectionFakes.FakeTrackers()
    private val access = JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), trackers)
    private val started = mutableListOf<Pair<String, String>>()
    private val runs = mockk<RunService> {
        every { startForJira(workflow.id, any(), any()) } answers {
            started += secondArg<String>() to thirdArg<String>()
            Fixtures.runningRun(project, workflow).copy(id = WorkflowRunId.new())
        }
    }
    private val intakes = InMemoryIntakes()
    private val queued = mutableListOf<WorkRequest>()
    private val audit = RecordingAuditPort()
    private val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value))
    private val service = JiraIntakeService(intakes, projects, workflows, runs, access, JiraIntakePlanner(), { queued += it }, owner, audit, clock)

    private fun enable() = service.update(project.id, IntakeCommand(enabled = true, pollIntervalMinutes = 5, workflowId = workflow.id))

    private fun issue(key: String, status: String, historyId: String) =
        WatchedIssue(key, status, now, statusChanges = listOf(StatusChange(historyId, now.minusSeconds(30), status)))

    @Test
    fun `configuring needs project manage, a workflow with a Jira trigger, and enqueues the first poll`() {
        assertThatThrownBy {
            JiraIntakeService(intakes, projects, workflows, runs, access, JiraIntakePlanner(), { }, FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value)), audit, clock)
                .update(project.id, IntakeCommand(true, 5, workflow.id))
        }.isInstanceOf(InsufficientPermissionException::class.java)
        val manual = Fixtures.reviewWorkflow(project)
        every { workflows.findById(manual.id) } returns manual
        assertThatThrownBy { service.update(project.id, IntakeCommand(true, 5, manual.id)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("JIRA_TRANSITION")
        assertThatThrownBy { service.update(project.id, IntakeCommand(true, 0, workflow.id)) }.isInstanceOf(ValidationException::class.java)

        val view = enable()
        assertThat(view.intake.enabled).isTrue()
        assertThat(view.triggerStatus).isEqualTo("Ready for AI")
        assertThat(view.jql).isEqualTo("project = \"SHOP\" AND (labels = ai) AND status CHANGED TO \"Ready for AI\" AFTER -7m ORDER BY updated ASC")
        assertThat(queued.single().kind).isEqualTo(WorkKinds.JIRA_INTAKE_POLL)
        assertThat(audit.operations()).containsExactly("JIRA_INTAKE_UPDATE")
    }

    @Test
    fun `without an ISSUE_READ connection the watcher refuses to poll`() {
        enable()
        assertThat(service.poll(project.id)).isZero()

        val intake = intakes.find(project.id)!!
        assertThat(intake.lastError).startsWith("refused:").contains("[ISSUE_READ]")
        assertThat(intake.consecutiveFailures).isEqualTo(1)
        assertThat(trackers.opened).isEmpty()
        assertThat(service.get(project.id).problems).anyMatch { it.contains("refuses to poll") }
    }

    @Test
    fun `the first poll takes a baseline, later polls start one run per transition with the reader connection`() {
        connections.save(ConnectionFakes.connection("reader", READ))
        connections.save(ConnectionFakes.connection("writer", WRITE))
        enable()

        trackers.watchResult = listOf(issue("SHOP-1", "Ready for AI", "10"))
        assertThat(service.poll(project.id)).isZero()
        assertThat(trackers.watchedJql).isEmpty()
        assertThat(intakes.find(project.id)!!.cursor).isEqualTo(now)

        now = now.plus(Duration.ofMinutes(5))
        assertThat(service.poll(project.id)).isEqualTo(1)
        assertThat(trackers.opened.map { it.name }.distinct()).containsExactly("reader")
        assertThat(trackers.watchedJql.single()).isEqualTo("project = \"SHOP\" AND (labels = ai) AND status CHANGED TO \"Ready for AI\" AFTER -7m ORDER BY updated ASC")
        assertThat(started).containsExactly("SHOP-1" to "T:SHOP-1:10")

        // the overlapping window returns the same transition again: no second run; a new transition of the same issue starts one
        now = now.plus(Duration.ofMinutes(5))
        trackers.watchResult = listOf(issue("SHOP-1", "Ready for AI", "10"), issue("SHOP-2", "In Progress", "11"))
        assertThat(service.poll(project.id)).isZero()
        now = now.plus(Duration.ofMinutes(5))
        trackers.watchResult = listOf(issue("SHOP-1", "Ready for AI", "15"))
        assertThat(service.poll(project.id)).isEqualTo(1)

        val intake = intakes.find(project.id)!!
        assertThat(started.map { it.second }).containsExactly("T:SHOP-1:10", "T:SHOP-1:15")
        assertThat(intake.issuesStarted).isEqualTo(2)
        assertThat(intake.cursor).isEqualTo(now)
        assertThat(intake.lastConnection).isEqualTo("reader")
        assertThat(intake.lastWarning).isNull()
        assertThat(intake.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `an over-privileged connection is used with a warning`() {
        connections.save(ConnectionFakes.connection("writer", WRITE))
        enable()
        service.poll(project.id)
        val intake = intakes.find(project.id)!!
        assertThat(intake.lastError).isNull()
        assertThat(intake.lastWarning).contains("more rights than needed")
        assertThat(service.get(project.id).connection.status.name).isEqualTo("OVER_PRIVILEGED")
    }

    @Test
    fun `rate limits and server errors back off, honouring Retry-After`() {
        connections.save(ConnectionFakes.connection("reader", READ))
        enable()
        service.poll(project.id)
        now = now.plus(Duration.ofMinutes(5))
        trackers.watchFailure = IssueTrackerUnavailableException(429, Duration.ofMinutes(20), "rate limited")

        assertThat(service.poll(project.id)).isZero()
        val intake = intakes.find(project.id)!!
        assertThat(intake.lastError).contains("HTTP 429")
        assertThat(intake.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(20)))
        assertThat(intake.cursor).isEqualTo(now.minus(Duration.ofMinutes(5)))
        assertThat(service.due()).isEmpty()
    }

    @Test
    fun `a run that cannot start releases its claim so the next poll retries`() {
        connections.save(ConnectionFakes.connection("reader", READ))
        enable()
        service.poll(project.id)
        now = now.plus(Duration.ofMinutes(5))
        trackers.watchResult = listOf(issue("SHOP-9", "Ready for AI", "90"))
        every { runs.startForJira(workflow.id, "SHOP-9", any()) } throws InvalidStateException("workflow cannot start: no Jira connection")

        assertThat(service.poll(project.id)).isZero()
        assertThat(intakes.find(project.id)!!.lastError).contains("cannot start")
        assertThat(intakes.claims.keys).isEmpty()
        verify(exactly = 1) { runs.startForJira(workflow.id, "SHOP-9", "T:SHOP-9:90") }
    }

    @Test
    fun `poll now needs project manage and an enabled watcher`() {
        assertThatThrownBy { service.pollNow(project.id) }.isInstanceOf(ValidationException::class.java)
        enable()
        queued.clear()
        service.pollNow(project.id)
        assertThat(queued.single().dedupeKey).isEqualTo(JiraIntakeService.dedupeKey(project.id))
        assertThat(audit.operations()).contains("JIRA_INTAKE_POLL_NOW")
    }

    class InMemoryIntakes : JiraIntakeRepository {
        private val rows = ConcurrentHashMap<ProjectId, JiraIntake>()
        val claims = ConcurrentHashMap<String, Instant>()
        override fun find(projectId: ProjectId) = rows[projectId]
        override fun save(intake: JiraIntake) = intake.also { rows[it.projectId] = it }
        override fun findDue(now: Instant) = rows.values.filter { it.due(now) }
        override fun claim(projectId: ProjectId, dedupeKey: String, issueKey: String, at: Instant) = claims.putIfAbsent("$projectId/$dedupeKey", at) == null
        override fun attachRun(projectId: ProjectId, dedupeKey: String, runId: WorkflowRunId) = Unit
        override fun release(projectId: ProjectId, dedupeKey: String) {
            claims.remove("$projectId/$dedupeKey")
        }
        override fun purgeClaims(before: Instant): Int {
            val old = claims.filterValues { it.isBefore(before) }.keys
            old.forEach(claims::remove)
            return old.size
        }
    }
}
