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
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/** ADR 0053: REST starts check the declared run inputs; intake, CI, MCP and other system starters do not. */
class RunStartInputTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val runs = mockk<WorkflowRunRepository> { every { save(any()) } answers { firstArg() } }
    private val workflows = mockk<WorkflowDefinitionRepository> { every { findById(definition.id) } returns definition }
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val engine = mockk<WorkflowEngine>(relaxed = true)

    /** fetch-issue asks for the issue key, specify for a required audience, plan for optional hints. */
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask {
            val inputs = when (key) {
                DefaultWorkflows.TEMPLATE_FETCH_ISSUE -> listOf(RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true))
                DefaultWorkflows.TEMPLATE_SPECIFY -> listOf(RunInputField("audience", "Audience", required = true))
                DefaultWorkflows.TEMPLATE_PLAN -> listOf(RunInputField("techHints", "Technical hints", RunInputType.MULTILINE))
                else -> emptyList()
            }
            return ResolvedTask(
                key, key, TaskExecutor.LLM, null, TaskPrompt(null, null), emptyList(), emptyMap(), null, null, emptyList(), emptyList(),
                emptyList(), listOf(key), listOf("success", "failure"), runInputs = inputs,
            )
        }

        override fun palette(projectId: UUID?) = emptyList<pl.mos.backoffice.shared.tasks.TaskSummary>()
    }
    private val audit = RecordingAuditPort()
    private val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_START, ProjectsPermissions.CI_EXECUTE, projects = setOf(project.id.value))

    private fun service(gate: FakeAuthorizationGate = this.gate) = RunService(
        runs, workflows, projects, mockk<RunLogRepository>(), engine, mockk<RecordRunProgress>(), WorkflowValidator(), EventPublisher.NOOP, gate, audit,
        Clock.fixed(now), runInputs = catalog,
    )

    private fun errors(block: () -> Unit): List<ValidationException.FieldError> {
        val thrown = runCatching(block).exceptionOrNull()
        assertThat(thrown).isInstanceOf(ValidationException::class.java)
        verify(exactly = 0) { engine.start(any()) }
        return (thrown as ValidationException).errors
    }

    @Test
    fun `a missing required field is refused and the error names the field`() {
        val problems = errors { service().startChecked(definition.id, RunInput("SHOP-1")) }

        assertThat(problems.map { it.field }).containsExactly("parameters.audience")
        assertThat(problems.single().message).contains("Audience").contains("required")
    }

    @Test
    fun `a blank required field is refused like a missing one, the issue key included`() {
        val problems = errors { service().startChecked(definition.id, RunInput(" ", mapOf("audience" to "  ", "issueKey" to ""))) }

        assertThat(problems.map { it.field }).containsExactlyInAnyOrder("parameters.issueKey", "parameters.audience")
    }

    @Test
    fun `values of up to 20 000 characters pass, a longer value of any declared field is refused`() {
        val longest = "x".repeat(20_000)
        val problems = errors { service().startChecked(definition.id, RunInput("SHOP-1", mapOf("audience" to "devs", "techHints" to longest + "x"))) }
        assertThat(problems.map { it.field }).containsExactly("parameters.techHints")
        assertThat(problems.single().message).contains("20000")

        val run = service().startChecked(definition.id, RunInput("SHOP-1", mapOf("audience" to longest, "techHints" to longest)))
        assertThat(run.input.parameters["audience"]).isEqualTo(longest)
    }

    @Test
    fun `the value of an ISSUE_KEY field becomes the run issue key unless the request names one`() {
        val fromField = service().startChecked(definition.id, RunInput(null, mapOf("issueKey" to " SHOP-7 ", "audience" to "devs")))
        assertThat(fromField.issueKey).isEqualTo("SHOP-7")
        assertThat(audit.entries.last().details).containsEntry("issueKey", "SHOP-7")

        val fromRequest = service().startChecked(definition.id, RunInput("SHOP-8", mapOf("audience" to "devs")))
        assertThat(fromRequest.issueKey).isEqualTo("SHOP-8")
    }

    @Test
    fun `an ISSUE_KEY value must look like an issue key, from the parameter or from the request`() {
        listOf(
            RunInput(null, mapOf("issueKey" to "../x ~^:..", "audience" to "devs")),
            RunInput("SHOP-1; rm -rf", mapOf("audience" to "devs")),
            RunInput(null, mapOf("issueKey" to "SHOP", "audience" to "devs")),
        ).forEach { input ->
            val problems = errors { service().startChecked(definition.id, input) }
            assertThat(problems.map { it.field }).describedAs(input.toString()).containsExactly("parameters.issueKey")
            assertThat(problems.single().message).contains("issue key").contains("ABC-123")
        }

        val lower = service().startChecked(definition.id, RunInput(null, mapOf("issueKey" to "shop-12", "audience" to "devs")))
        assertThat(lower.issueKey).isEqualTo("shop-12")
    }

    @Test
    fun `undeclared parameters are kept`() {
        val run = service().startChecked(definition.id, RunInput("SHOP-1", mapOf("audience" to "devs", "extra" to "kept", "depth" to 3)))

        assertThat(run.input.parameters).containsEntry("extra", "kept").containsEntry("depth", 3).containsEntry("audience", "devs")
    }

    @Test
    fun `the permission check runs before the input check`() {
        val stranger = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))

        assertThatThrownBy { service(stranger).startChecked(definition.id, RunInput()) }.isInstanceOf(InsufficientPermissionException::class.java)
        verify(exactly = 0) { engine.start(any()) }
    }

    @Test
    fun `intake, MCP, CI and Jira intake starts are not checked`() {
        val service = service()

        assertThat(service.start(definition.id, RunInput("SHOP-1"), TriggerSource.MANUAL).issueKey).isEqualTo("SHOP-1")
        assertThat(service.start(definition.id, RunInput(), TriggerSource.API).issueKey).isNull()
        assertThat(service.startForCi(definition.id, RunInput(), CiExecutionId.new(), emptyList()).trigger.type).isEqualTo(TriggerSource.CI)
        assertThat(gate.asSystem("test") { service.startForJira(definition.id, "SHOP-42", "T:SHOP-42:1") }.issueKey).isEqualTo("SHOP-42")
        verify(exactly = 4) { engine.start(any()) }
    }
}
