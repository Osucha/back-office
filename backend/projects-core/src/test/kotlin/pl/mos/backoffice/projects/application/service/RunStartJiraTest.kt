package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.application.service.tasks.JiraStepCheck
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

/** ADR 0030 amendment: a missing Jira connection does not block saving, but a run refuses to start; Jira intake runs (ADR 0033). */
class RunStartJiraTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val runs = mockk<WorkflowRunRepository> { every { save(any()) } answers { firstArg() } }
    private val workflows = mockk<WorkflowDefinitionRepository> { every { findById(definition.id) } returns definition }
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val connections = ConnectionFakes.InMemoryConnections()
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: java.util.UUID?, overrides: TaskOverrides): ResolvedTask {
            val executor = when (key) {
                "fetch-issue" -> TaskExecutor.FETCH_ISSUE
                "transition-issue" -> TaskExecutor.TRANSITION_ISSUE
                else -> TaskExecutor.LLM
            }
            return ResolvedTask(key, key, executor, null, TaskPrompt(null, null), emptyList(), overrides.config, null, null, emptyList(), emptyList(), emptyList(), listOf(key), listOf("success", "failure"))
        }
        override fun palette(projectId: java.util.UUID?) = emptyList<pl.mos.backoffice.shared.tasks.TaskSummary>()
    }
    private val checks = JiraStepCheck(catalog, JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), ConnectionFakes.FakeTrackers()))
    private val audit = RecordingAuditPort()
    private val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_START, projects = setOf(project.id.value))
    private val service = RunService(
        runs, workflows, projects, mockk<RunLogRepository>(), engine, mockk<RecordRunProgress>(), WorkflowValidator(), EventPublisher.NOOP, gate, audit, Clock.fixed(now), checks,
    )

    @Test
    fun `a run whose Jira steps have no connection refuses to start with a pointer to the guide`() {
        assertThatThrownBy { service.start(definition.id, RunInput("SHOP-1")) }
            .isInstanceOf(InvalidStateException::class.java)
            .hasMessageContaining("fetch-issue")
            .hasMessageContaining("[ISSUE_READ]")
            .hasMessageContaining("integrations-jira#jira-connections")
        verify(exactly = 0) { engine.start(any()) }

        connections.save(ConnectionFakes.connection("reader", READ))
        assertThatThrownBy { service.start(definition.id, RunInput("SHOP-1")) }.hasMessageContaining("transition").hasMessageContaining("ISSUE_TRANSITION")

        connections.save(ConnectionFakes.connection("mover", setOf(ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ)))
        assertThat(service.start(definition.id, RunInput("SHOP-1")).issueKey).isEqualTo("SHOP-1")
    }

    @Test
    fun `the Jira intake starts runs as the watcher with trigger JIRA, audited RUN_START`() {
        connections.save(ConnectionFakes.connection("mover", setOf(ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ)))
        val run = gate.asSystem("test") { service.startForJira(definition.id, "SHOP-42", "T:SHOP-42:10") }

        assertThat(run.trigger.type).isEqualTo(TriggerSource.JIRA)
        assertThat(run.issueKey).isEqualTo("SHOP-42")
        assertThat(run.startedBy).isEqualTo(RunService.JIRA_INTAKE_ACTOR)
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("RUN_START")
        assertThat(entry.details).containsEntry("trigger", "JIRA").containsEntry("intakeDedupeKey", "T:SHOP-42:10")
    }
}
