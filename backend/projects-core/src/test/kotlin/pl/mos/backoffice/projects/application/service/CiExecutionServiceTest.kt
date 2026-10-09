package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.StartCiExecution.CiExecutionCommand
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.event.CiExecutionFinished
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionStatus
import pl.mos.backoffice.projects.domain.model.CiMergeRequest
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.GateResult
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.NotFoundException
import org.assertj.core.api.Assertions.assertThatThrownBy
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.PageOf

class CiExecutionServiceTest {

    private val baseProject: pl.mos.backoffice.projects.domain.model.Project = Fixtures.project()
    private val review: pl.mos.backoffice.projects.domain.model.WorkflowDefinition = Fixtures.reviewWorkflow(baseProject)
    private val project: pl.mos.backoffice.projects.domain.model.Project = baseProject.withEntrypoints(listOf(CiEntrypoint("code-review", review.id, CiMode.BLOCKING)), now)
    private val executions = mockk<CiExecutionRepository>()
    private val projects = mockk<ProjectRepository>()
    private val runService = mockk<RunService>()
    private val events = mutableListOf<DomainEvent>()
    private val gate = Fixtures.ciGate(project.id, project.key)
    private val audit = RecordingAuditPort()
    private val service = CiExecutionService(executions, projects, runService, EventPublisher { events += it }, gate, audit, Clock.fixed(now))
    private val completion = CiExecutionCompletionService(executions, EventPublisher { events += it }, Clock.fixed(now))

    private val context = CiContext(commitSha = "abc123", mergeRequest = CiMergeRequest(iid = "7", sourceBranch = "feature/x", targetBranch = "main"))

    private fun ciRun(diff: Boolean = true): WorkflowRun {
        var run = WorkflowRun.create(project, review, RunTrigger(TriggerSource.CI), RunInput(ci = context), now).start(now)
        if (diff) run = run.addArtifact(Artifact(ArtifactId.new(), "start", "diff", ArtifactKind.DIFF, "text/x-diff", "diff --git", createdAt = now))
        return run
    }

    init {
        every { projects.findById(project.id) } returns project
        every { executions.save(any()) } answers { firstArg() }
    }

    @Test
    fun `starts the entrypoint workflow with the CI context and the diff as artefact`() {
        every { executions.findRecentByKey(any(), any()) } returns null
        val artifacts = slot<List<NewArtifact>>()
        val input = slot<RunInput>()
        val run = ciRun()
        every { runService.startForCi(review.id, capture(input), any(), capture(artifacts)) } returns run

        val execution = service.start(project.id, CiExecutionCommand("code-review", context, diff = "diff --git", variables = mapOf("issueKey" to "SHOP-9")))

        assertThat(execution.status).isEqualTo(CiExecutionStatus.RUNNING)
        assertThat(execution.gateResult).isEqualTo(GateResult.PENDING)
        assertThat(execution.runId).isEqualTo(run.id)
        assertThat(execution.diffArtifactId).isEqualTo(run.artifactNamed("diff")!!.id)
        assertThat(artifacts.captured.single().name).isEqualTo("diff")
        assertThat(input.captured.ci).isEqualTo(context)
        assertThat(input.captured.issueKey).isEqualTo("SHOP-9")
        assertThat(input.captured.parameters["entrypoint"]).isEqualTo("code-review")
        assertThat(gate.checked.first()).isEqualTo("ci.execute@${project.id}")
        assertThat(audit.entries.single().operation).isEqualTo("CI_EXECUTION_START")
        assertThat(audit.entries.single().details["runId"]).isEqualTo(run.id.toString())
    }

    @Test
    fun `the execution is mapped to the project repository by URL and an entrypoint may be restricted to one repository`() {
        val docs = pl.mos.backoffice.projects.domain.model.GitRepository(
            "docs", "git@git.example.com:g/docs.git", purpose = pl.mos.backoffice.projects.domain.model.RepositoryPurpose.DOCS,
        )
        val multi = project.copy(repositories = project.repositories + docs)
            .withEntrypoints(listOf(CiEntrypoint("code-review", review.id, CiMode.BLOCKING), CiEntrypoint("docs-review", review.id, CiMode.ADVISORY, repository = "docs")), now)
        every { projects.findById(project.id) } returns multi
        every { executions.findRecentByKey(any(), any()) } returns null
        val input = slot<RunInput>()
        every { runService.startForCi(review.id, capture(input), any(), any()) } returns ciRun()

        val fromDocs = context.copy(repositoryUrl = "https://git.example.com/g/docs")
        val execution = service.start(project.id, CiExecutionCommand("docs-review", fromDocs))
        assertThat(execution.repositoryKey).isEqualTo("docs")
        assertThat(input.captured.parameters["repository"]).isEqualTo("docs")
        assertThat(audit.entries.single().details["repository"]).isEqualTo("docs")

        // the unrestricted entrypoint from the main repository
        assertThat(service.start(project.id, CiExecutionCommand("code-review", context.copy(repositoryUrl = "https://git.example.com/g/shop.git"))).repositoryKey).isEqualTo("main")

        // restricted entrypoint from another repository, unknown repository, old client without URL
        assertThatThrownBy { service.start(project.id, CiExecutionCommand("docs-review", context.copy(repositoryUrl = "https://git.example.com/g/shop.git"))) }
            .isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java).hasMessageContaining("may only be started from repository docs")
        assertThatThrownBy { service.start(project.id, CiExecutionCommand("code-review", context.copy(repositoryUrl = "https://git.example.com/g/web.git"))) }
            .isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java)
            .hasMessageContaining("main: https://git.example.com/g/shop.git, docs: git@git.example.com:g/docs.git")
        assertThatThrownBy { service.start(project.id, CiExecutionCommand("docs-review", context)) }
            .isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java).hasMessageContaining("update backoffice-ci.sh")
        assertThat(service.start(project.id, CiExecutionCommand("code-review", context)).repositoryKey).isNull()
    }

    @Test
    fun `only the CI token of the project may start executions`() {
        val owner = CiExecutionService(executions, projects, runService, EventPublisher.NOOP, FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value)), audit, Clock.fixed(now))
        assertThatThrownBy { owner.start(project.id, CiExecutionCommand("code-review", context)) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("ci.execute") }
        val foreign = CiExecutionService(executions, projects, runService, EventPublisher.NOOP, Fixtures.ciGate(pl.mos.backoffice.projects.domain.model.ProjectId.new()), audit, Clock.fixed(now))
        assertThatThrownBy { foreign.start(project.id, CiExecutionCommand("code-review", context)) }.isInstanceOf(InsufficientPermissionException::class.java)
        verify(exactly = 0) { runService.startForCi(any(), any(), any(), any()) }
        assertThat(audit.entries).isEmpty()
    }

    @Test
    fun `get needs the CI token of the project or project view, and list is filtered by project view`() {
        val execution = CiExecution(pl.mos.backoffice.projects.domain.model.CiExecutionId.new(), project.id, project.key, "code-review", CiMode.BLOCKING, context = context, createdAt = now)
        every { executions.findById(execution.id) } returns execution
        assertThat(service.get(execution.id)).isEqualTo(execution)
        assertThat(gate.checked.last()).isEqualTo("ci.execute@${project.id}")

        val viewerGate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        val viewer = CiExecutionService(executions, projects, runService, EventPublisher.NOOP, viewerGate, audit, Clock.fixed(now))
        assertThat(viewer.get(execution.id)).isEqualTo(execution)
        assertThat(viewerGate.checked.last()).isEqualTo("project.view@${project.id}")

        val foreignToken = CiExecutionService(executions, projects, runService, EventPublisher.NOOP, Fixtures.ciGate(pl.mos.backoffice.projects.domain.model.ProjectId.new()), audit, Clock.fixed(now))
        assertThatThrownBy { foreignToken.get(execution.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        val stranger = CiExecutionService(executions, projects, runService, EventPublisher.NOOP, FakeAuthorizationGate.denyAll(), audit, Clock.fixed(now))
        assertThatThrownBy { stranger.get(execution.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        every { executions.find(null, ProjectFilter.Only(setOf(project.id.value)), 0, 20) } returns PageOf(listOf(execution), 0, 20, 1)
        assertThat(viewer.list(null, 0, 20).content).containsExactly(execution)
        every { executions.find(null, ProjectFilter.Only(emptySet()), 0, 20) } returns PageOf(emptyList(), 0, 20, 0)
        assertThat(stranger.list(null, 0, 20).totalElements).isZero()
    }

    @Test
    fun `an execution for the same project, entrypoint, commit and MR within 24h is returned unless forced`() {
        val existing = CiExecution(pl.mos.backoffice.projects.domain.model.CiExecutionId.new(), project.id, project.key, "code-review", CiMode.BLOCKING, context = context, createdAt = now.minusSeconds(60))
        val key = CiExecution.idempotencyKey(project.id, "code-review", "abc123", "7")
        every { executions.findRecentByKey(key, now.minusSeconds(24 * 3600)) } returns existing

        assertThat(service.start(project.id, CiExecutionCommand("code-review", context))).isSameAs(existing)
        verify(exactly = 0) { runService.startForCi(any(), any(), any(), any()) }

        every { runService.startForCi(any(), any(), any(), any()) } returns ciRun()
        val forced = service.start(project.id, CiExecutionCommand("code-review", context, variables = mapOf("force" to "true")))
        assertThat(forced.id).isNotEqualTo(existing.id)
    }

    @Test
    fun `unknown entrypoints are not found and a start failure marks the execution FAILED`() {
        every { executions.findRecentByKey(any(), any()) } returns null
        assertThatThrownBy { service.start(project.id, CiExecutionCommand("nope", context)) }.isInstanceOf(NotFoundException::class.java)

        every { runService.startForCi(any(), any(), any(), any()) } throws pl.mos.backoffice.shared.InvalidStateException("workflow is disabled")
        val failed = service.start(project.id, CiExecutionCommand("code-review", context))
        assertThat(failed.status).isEqualTo(CiExecutionStatus.FAILED)
        assertThat(failed.gateResult).isEqualTo(GateResult.FAIL)
        assertThat(failed.error).contains("disabled")
        assertThat(events.filterIsInstance<CiExecutionFinished>()).hasSize(1)
    }

    @Test
    fun `gate result is derived from the run outcome and the mode`() {
        val execution = CiExecution(pl.mos.backoffice.projects.domain.model.CiExecutionId.new(), project.id, project.key, "code-review", CiMode.BLOCKING, context = context, createdAt = now)
        val run = ciRun().withReview(ReviewReport(Verdict.REQUEST_CHANGES, 40, "needs work"))
        val runWithTrigger = run.copy(trigger = RunTrigger(TriggerSource.CI, execution.id))
        every { executions.findById(execution.id) } returns execution.running(run.id, null)

        completion.runFinished(runWithTrigger.succeeded(RunOutcome.FAILURE, now))
        val blockingFail = events.filterIsInstance<CiExecutionFinished>().single()
        assertThat(blockingFail.gateResult).isEqualTo("FAIL")
        assertThat(blockingFail.status).isEqualTo("SUCCEEDED")

        val saved = slot<CiExecution>()
        every { executions.save(capture(saved)) } answers { firstArg() }
        completion.runFinished(runWithTrigger.succeeded(RunOutcome.SUCCESS, now))
        assertThat(saved.captured.gateResult).isEqualTo(GateResult.PASS)
        assertThat(saved.captured.report?.verdict).isEqualTo(Verdict.REQUEST_CHANGES)
        assertThat(saved.captured.summary).isEqualTo("needs work")

        every { executions.findById(execution.id) } returns execution.copy(mode = CiMode.ADVISORY).running(run.id, null)
        completion.runFinished(runWithTrigger.failed(RunFailure("code-review", "llm down"), now))
        assertThat(saved.captured.status).isEqualTo(CiExecutionStatus.FAILED)
        assertThat(saved.captured.gateResult).isEqualTo(GateResult.PASS)
        assertThat(saved.captured.error).isEqualTo("llm down")

        completion.runFinished(runWithTrigger.copy(trigger = RunTrigger(TriggerSource.MANUAL)).succeeded(RunOutcome.SUCCESS, now))
        verify(exactly = 3) { executions.findById(execution.id) }
    }
}
