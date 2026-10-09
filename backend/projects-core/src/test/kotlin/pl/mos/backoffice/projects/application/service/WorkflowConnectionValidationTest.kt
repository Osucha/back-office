package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.ConnectionFakes.WRITE
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

/** ADR 0030 in the designer: Jira steps without a fitting connection are errors, over-privileged ones warnings. */
class WorkflowConnectionValidationTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val workflows = mockk<WorkflowDefinitionRepository>(relaxed = true)
    private val runs = mockk<WorkflowRunRepository>(relaxed = true)
    private val connections = ConnectionFakes.InMemoryConnections()
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: java.util.UUID?, overrides: TaskOverrides): ResolvedTask? {
            val executor = when (key) {
                "fetch-issue" -> TaskExecutor.FETCH_ISSUE
                "notify-jira" -> TaskExecutor.NOTIFY
                "transition-issue" -> TaskExecutor.TRANSITION_ISSUE
                else -> TaskExecutor.LLM
            }
            return ResolvedTask(key, key, executor, null, TaskPrompt(null, null), emptyList(), overrides.config, null, null, emptyList(), emptyList(), emptyList(), listOf(key), listOf("success", "failure"))
        }
        override fun palette(projectId: java.util.UUID?) = emptyList<pl.mos.backoffice.shared.tasks.TaskSummary>()
    }
    private val expressions = object : ExpressionEvaluator {
        override fun validate(expression: String): String? = null
        override fun evaluate(expression: String, view: WorkflowStateView): Boolean = true
    }
    private val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, ProjectsPermissions.WORKFLOW_EDIT, projects = setOf(project.id.value))
    private val service = WorkflowService(
        workflows, projects, runs, WorkflowValidator(), catalog, expressions, gate, RecordingAuditPort(), Clock.fixed(Fixtures.now),
        JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), ConnectionFakes.FakeTrackers()),
    )

    private fun command(vararg config: Pair<String, Any?>) = WorkflowCommand(
        name = "jira",
        nodes = listOf(
            WorkflowNode.Start("start"),
            WorkflowNode.Task("fetch", "Fetch", task = TaskBinding("fetch-issue", TaskOverrides(config = mapOf(*config)))),
            WorkflowNode.HumanGate("gate", "OK?", gate = GateSpec("OK?")),
            WorkflowNode.End("done", "Done", end = EndSpec(RunOutcome.SUCCESS)),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", "out", "fetch"), WorkflowEdge("e2", "fetch", "success", "gate"), WorkflowEdge("e3", "gate", "approve", "done"),
        ),
    )

    @Test
    fun `no connection is a warning at save time - the workflow saves on an installation without Jira`() {
        val result = service.validate(project.id, command())

        assertThat(result.valid).isTrue()
        val issue = result.warnings.single { it.nodeId == "fetch" }
        assertThat(issue.field).isEqualTo("task.config.jiraConnection")
        assertThat(issue.message).contains("[ISSUE_READ]").contains("jira-connections").contains("runs refuse to start")
        every { workflows.save(any()) } answers { firstArg() }
        assertThat(service.create(project.id, command()).name).isEqualTo("jira")
    }

    @Test
    fun `the seeded workflows save without any Jira connection`() {
        val seeded = pl.mos.backoffice.projects.domain.service.DefaultWorkflows.all(project.id, Fixtures.now)
        seeded.forEach { definition ->
            val result = service.validate(project.id, WorkflowCommand(definition.name, definition.description, definition.nodes, definition.edges, definition.triggers))
            assertThat(result.errors).describedAs(definition.name).isEmpty()
        }
    }

    private fun notifyCommand(vararg config: Pair<String, Any?>) = WorkflowCommand(
        name = "notify",
        nodes = listOf(
            WorkflowNode.Start("start"),
            WorkflowNode.Task("tell", "Tell", task = TaskBinding("notify-jira", TaskOverrides(config = mapOf(*config)))),
            WorkflowNode.HumanGate("gate", "OK?", gate = GateSpec("OK?")),
            WorkflowNode.End("done", "Done", end = EndSpec(RunOutcome.SUCCESS)),
        ),
        edges = listOf(WorkflowEdge("e1", "start", "out", "gate"), WorkflowEdge("e2", "gate", "approve", "tell"), WorkflowEdge("e3", "tell", "success", "done")),
    )

    @Test
    fun `a write step without approval is a warning, an unknown approval value or approver role an error`() {
        connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        assertThat(service.validate(project.id, notifyCommand()).warnings.filter { it.nodeId == "tell" }).isEmpty()
        assertThat(service.validate(project.id, notifyCommand("approval" to "REQUIRED")).warnings.filter { it.nodeId == "tell" }).isEmpty()

        val off = service.validate(project.id, notifyCommand("approval" to "NOT_REQUIRED"))
        assertThat(off.valid).isTrue()
        val warning = off.warnings.single { it.nodeId == "tell" }
        assertThat(warning.field).isEqualTo("task.config.approval")
        assertThat(warning.message).contains("without anybody confirming")

        assertThat(service.validate(project.id, notifyCommand("approval" to "MAYBE")).errors.single().field).isEqualTo("task.config.approval")
        val badRole = service.validate(project.id, notifyCommand("approvers" to mapOf("projectRoles" to listOf("Owner!"))))
        assertThat(badRole.errors.single().field).isEqualTo("task.config.approvers")
    }

    @Test
    fun `an over-privileged connection is a warning, a least-privileged one is clean`() {
        connections.save(ConnectionFakes.connection("writer", WRITE))
        val warned = service.validate(project.id, command())
        assertThat(warned.valid).isTrue()
        assertThat(warned.warnings.single { it.nodeId == "fetch" }.message).contains("more rights than needed")

        connections.save(ConnectionFakes.connection("reader", READ))
        assertThat(service.validate(project.id, command()).warnings.filter { it.nodeId == "fetch" }).isEmpty()
    }

    @Test
    fun `a pin to an insufficient or unknown connection is an error`() {
        val commenter = connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        connections.save(ConnectionFakes.connection("reader", READ))

        assertThat(service.validate(project.id, command("jiraConnection" to commenter.id.toString())).errors.single().message).contains("lacks the capabilities ISSUE_READ")
        assertThat(service.validate(project.id, command("jiraConnection" to "nope")).errors.single().field).isEqualTo("task.config.jiraConnection")
    }
}
