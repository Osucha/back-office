package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects.ProjectCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.tasks.SpecKitStepCheck
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.service.CiSnippetRenderer
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.PlatformFeatures
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.UsageLedger

/** ADR 0036: Spec Kit is optional per project (and allowed or not by the platform). */
class SpecKitGatingTest {

    /** Templates `spec-kit-*` and `my-spec` (a library template extending one) resolve to SPEC_KIT; everything else to LLM. */
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: java.util.UUID?, overrides: TaskOverrides): ResolvedTask {
            val executor = if (key.startsWith("spec-kit") || key == "my-spec") TaskExecutor.SPEC_KIT else TaskExecutor.LLM
            return ResolvedTask(key, key, executor, null, TaskPrompt(null, null), emptyList(), overrides.config, null, null, emptyList(), emptyList(), emptyList(), listOf(key), listOf("success", "failure"))
        }
        override fun palette(projectId: java.util.UUID?) = listOf(
            TaskSummary("spec-kit-specify", "Spec Kit: specify", "", TaskExecutor.SPEC_KIT, emptyList(), false, null, listOf("success", "failure")),
            TaskSummary("my-spec", "My spec (extends Spec Kit)", "", TaskExecutor.SPEC_KIT, emptyList(), false, null, listOf("success", "failure")),
            TaskSummary("llm-task", "LLM task", "", TaskExecutor.LLM, emptyList(), false, null, listOf("success", "failure")),
        )
    }

    private var allowed = true
    private val features = PlatformFeatures { allowed }
    private val check = SpecKitStepCheck(catalog, features)
    private val off: Project = Fixtures.project(specKit = false)
    private val on: Project = Fixtures.project(key = "WEB", specKit = true)
    private val issueWorkflow: WorkflowDefinition = DefaultWorkflows.issueToReviewedMr(off.id, now)

    @Test
    fun `Spec Kit steps are validator errors and run blockers when the project or the platform turns it off`() {
        assertThat(check.designIssues(off, issueWorkflow.nodes)).extracting<String> { it.nodeId }.contains("specify", "plan", "tasks")
        assertThat(check.designIssues(off, issueWorkflow.nodes).first().message).contains("off for this project").contains("projects#spec-kit")
        assertThat(check.designIssues(on, issueWorkflow.nodes)).isEmpty()
        assertThat(check.runBlockers(off, issueWorkflow.nodes)).isNotEmpty()

        allowed = false
        assertThat(check.designIssues(on, issueWorkflow.nodes).first().message).contains("disabled for the whole platform")
        assertThat(check.effective(on)).isFalse()
    }

    @Test
    fun `the designer palette hides Spec Kit templates, also those extending them, and saving keeps such a workflow as a draft`() {
        val projects = mockk<ProjectRepository> { every { findById(off.id) } returns off; every { findById(on.id) } returns on }
        val expressions = mockk<ExpressionEvaluator> { every { validate(any()) } returns null }
        val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, ProjectsPermissions.WORKFLOW_EDIT, projects = setOf(off.id.value, on.id.value))
        val saving = mockk<WorkflowDefinitionRepository> { every { save(any()) } answers { firstArg() } }
        val service = WorkflowService(saving, projects, mockk(), WorkflowValidator(), catalog, expressions, gate, RecordingAuditPort(), Clock.fixed(now), null, check)

        assertThat(service.palette(off.id).tasks.map { it.key }).containsExactly("llm-task")
        assertThat(service.palette(on.id).tasks.map { it.key }).containsExactly("spec-kit-specify", "my-spec", "llm-task")

        val command = WorkflowCommand(issueWorkflow.name, "", issueWorkflow.nodes, issueWorkflow.edges, issueWorkflow.triggers, true)
        val result = service.validate(off.id, command)
        assertThat(result.errors).anyMatch { it.nodeId == "specify" && it.message.contains("Spec Kit") }
        // kept as a draft: saved, but no run starts from it
        assertThat(service.create(off.id, command).draft).isTrue()
    }

    @Test
    fun `runs refuse to start Spec Kit steps when Spec Kit is off`() {
        val workflows = mockk<WorkflowDefinitionRepository> { every { findById(issueWorkflow.id) } returns issueWorkflow }
        val projects = mockk<ProjectRepository> { every { findById(off.id) } returns off }
        val engine = mockk<WorkflowEngine>(relaxed = true)
        val service = RunService(
            mockk(), workflows, projects, mockk<RunLogRepository>(), engine, mockk<RecordRunProgress>(), WorkflowValidator(), EventPublisher.NOOP,
            FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_START, projects = setOf(off.id.value)), RecordingAuditPort(), Clock.fixed(now),
            specKitSteps = check,
        )
        assertThatThrownBy { service.start(issueWorkflow.id, RunInput("SHOP-1")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("Spec Kit").hasMessageContaining("specify")
        verify(exactly = 0) { engine.start(any()) }
    }

    @Test
    fun `new projects have Spec Kit off and get only the SDLC workflows without it, Add Spec Kit workflows seeds the rest`() {
        var stored: Project? = null
        val saved = mutableListOf<WorkflowDefinition>()
        val projects = mockk<ProjectRepository> {
            every { findByKey(any()) } returns null
            val captured = slot<Project>()
            every { save(capture(captured)) } answers { captured.captured.also { stored = it } }
            every { findById(any()) } answers { stored }
        }
        val workflows = mockk<WorkflowDefinitionRepository> {
            every { findByProjectId(any()) } answers { saved.toList() }
            every { save(any()) } answers { firstArg<WorkflowDefinition>().also { saved += it } }
        }
        val gate = FakeAuthorizationGate.permitAll(ProjectsPermissions.PROJECTS_CREATE)
        fun service() = ProjectService(
            projects, workflows, mockk<WorkflowRunRepository>(), mockk<ApprovalRequestRepository>(), mockk<CiExecutionRepository>(), mockk<ProjectCiTokenRepository>(),
            mockk<UsageLedger>(), mockk<CiSnippetRenderer>(), "http://localhost", gate, mockk<ProjectAccessProvisioner>(relaxed = true), RecordingAuditPort(),
            Clock.fixed(now), features,
        )
        val command = ProjectCommand(key = "NEW", name = "New", repositories = listOf(GitRepository.legacy("https://git.example.com/g/new.git", null)))

        val created = service().create(command)
        assertThat(created.specKit).isFalse()
        assertThat(saved.map { it.name }).containsExactly(DefaultWorkflows.CI_CODE_REVIEW, DefaultWorkflows.CHANGE_REQUEST)
        assertThat(service().specKitStatus(created.id).missingWorkflows).containsExactly(DefaultWorkflows.ISSUE_TO_REVIEWED_MR)
        assertThatThrownBy { service().addSpecKitWorkflows(created.id) }.isInstanceOf(InvalidStateException::class.java)

        // turning Spec Kit on later does not seed by itself; "Add Spec Kit workflows" does, once
        val turnedOn = service().update(created.id, command.copy(specKit = true))
        assertThat(turnedOn.specKit).isTrue()
        assertThat(saved).hasSize(2)
        assertThat(service().addSpecKitWorkflows(created.id).map { it.name }).containsExactly(DefaultWorkflows.ISSUE_TO_REVIEWED_MR)
        assertThat(service().addSpecKitWorkflows(created.id)).isEmpty()
        assertThat(service().specKitStatus(created.id).missingWorkflows).isEmpty()

        // a platform that disallows Spec Kit refuses turning it on
        allowed = false
        assertThatThrownBy { service().create(command.copy(key = "NEXT", specKit = true)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("disabled for the whole platform")
    }
}
