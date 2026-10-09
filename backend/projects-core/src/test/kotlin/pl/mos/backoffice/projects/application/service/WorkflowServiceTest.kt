package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.alice
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

class WorkflowServiceTest {

    private val project = Fixtures.project()
    private val store = mutableMapOf<WorkflowDefinitionId, WorkflowDefinition>()
    private val workflows = mockk<WorkflowDefinitionRepository>()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val runs = mockk<WorkflowRunRepository> { every { existsActiveByWorkflow(any()) } returns false }
    private val catalog = mockk<TaskCatalog>(relaxed = true)
    private val expressions = object : ExpressionEvaluator {
        override fun validate(expression: String): String? = null
        override fun evaluate(expression: String, view: WorkflowStateView): Boolean = true
    }
    private val audit = RecordingAuditPort()
    private val editor = FakeAuthorizationGate.permitting(ProjectsPermissions.WORKFLOW_EDIT, projects = setOf(project.id.value))
    private val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))

    init {
        every { workflows.save(any()) } answers { firstArg<WorkflowDefinition>().also { store[it.id] = it } }
        every { workflows.findById(any()) } answers { store[id(firstArg())] }
        every { workflows.findByProjectId(project.id) } answers { store.values.toList() }
        every { workflows.delete(any()) } answers { store.remove(id(firstArg())); Unit }
    }

    private fun id(arg: Any): WorkflowDefinitionId = arg as? WorkflowDefinitionId ?: WorkflowDefinitionId(arg as java.util.UUID)

    private fun service(gate: FakeAuthorizationGate) = WorkflowService(workflows, projects, runs, WorkflowValidator(), catalog, expressions, gate, audit, Clock.fixed(now))

    private val command = WorkflowCommand(
        name = "minimal",
        nodes = listOf(WorkflowNode.Start("start"), WorkflowNode.End("done", "Done", end = EndSpec(RunOutcome.SUCCESS))),
        edges = listOf(WorkflowEdge("e1", "start", "out", "done")),
    )

    @Test
    fun `editing workflows needs workflow edit, stamps updatedBy and is audited`() {
        val created = service(editor).create(project.id, command)
        assertThat(editor.checked.first()).isEqualTo("workflow.edit@${project.id}")
        assertThat(created.updatedBy).isEqualTo(alice)

        val updated = service(editor).update(created.id, command.copy(name = "renamed", enabled = false))
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.updatedBy).isEqualTo(alice)
        val copy = service(editor).duplicate(created.id)
        service(editor).delete(copy.id)

        assertThat(audit.operations()).containsExactly("WORKFLOW_CREATE", "WORKFLOW_UPDATE", "WORKFLOW_DUPLICATE", "WORKFLOW_DELETE")
        assertThat(audit.entries[1].details).containsOnlyKeys("name", "enabled", "version")
        assertThat(audit.entries[1].details["version"]).isEqualTo(mapOf("old" to 1, "new" to 2))
        assertThat(audit.entries.map { it.projectId }).containsOnly(project.id.value)
    }

    @Test
    fun `viewers can read, validate and see the palette but not edit`() {
        val created = service(editor).create(project.id, command)
        assertThat(service(viewer).get(created.id)).isEqualTo(created)
        assertThat(service(viewer).listByProject(project.id)).containsExactly(created)
        assertThat(service(viewer).validate(project.id, command).valid).isTrue()
        assertThat(service(viewer).palette(project.id).controlNodes).isNotEmpty
        assertThat(service(FakeAuthorizationGate.denyAll()).validateExpression("true").valid).isTrue()

        listOf<() -> Unit>(
            { service(viewer).create(project.id, command) },
            { service(viewer).update(created.id, command) },
            { service(viewer).delete(created.id) },
            { service(viewer).duplicate(created.id) },
        ).forEach { call ->
            assertThatThrownBy { call() }.isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("workflow.edit") }
        }
        assertThatThrownBy { service(editor).get(created.id) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("project.view") }
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).palette(project.id) }.isInstanceOf(InsufficientPermissionException::class.java)
    }
}
