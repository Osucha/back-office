package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.FailedJob
import pl.mos.backoffice.projects.application.port.out.PipelineReport
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.application.port.out.PipelineStatusPort
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.nodes.WaitForCiNodeExecutor
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.CiWaitSpec
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.Actor

/** ADR 0052: Back Office resumes a run waiting for the pipeline of its branch once GitLab says it finished. */
class PipelineWatchServiceTest {

    private val project = Fixtures.project()
    private val repo = project.repositories.single()
    private val implement = WorkflowNode.Task("implement", "Implement", task = TaskBinding("implement-task-list"))
    private val wait = WorkflowNode.WaitForCi("ci", "Wait for the pipeline", ci = CiWaitSpec("pipeline", 3600, watchPipeline = true))
    private val definition = Fixtures.issueWorkflow(project).let { d ->
        d.copy(nodes = listOf(WorkflowNode.Start("start"), implement, wait, WorkflowNode.End("done", "Done", end = EndSpec(RunOutcome.SUCCESS))))
    }
    private val token = ScmConnection(
        ScmConnectionId.new(), project.id, repo.key, "ci-reader", ScmProvider.GITLAB, setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ),
        SealedSecret("k1", "w", "c"), null, null, now, Actor.SYSTEM, now, Actor.SYSTEM,
    )
    private var report = PipelineReport(PipelineState.RUNNING, 7, "https://git.example.com/g/shop/-/pipelines/7", "running")
    private val asked = mutableListOf<Pair<String, String?>>()
    private val pipelines = PipelineStatusPort { _, _, ref, sha -> asked += ref to sha; report }
    private val runs = mockk<WorkflowRunRepository> { every { save(any()) } answers { firstArg() } }
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val tokens = mockk<ScmConnectionRepository> { every { findByProject(project.id) } returns listOf(token) }
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val service = PipelineWatchService(runs, projects, tokens, pipelines, engine, Clock.fixed(now))

    private fun waiting(definition: WorkflowDefinition = this.definition, node: WorkflowNode = wait): WorkflowRun =
        Fixtures.runningRun(project, definition)
            .nodeStarted(implement, now)
            .nodeFinished("implement", "success", now, repositories = listOf(RepositoryResult(repo.key, branch = "bo/1234abcd", commitSha = "abc123", pushed = true, primary = true)))
            .waitingForCi(node, "pipeline", now)

    @Test
    fun `a running pipeline keeps the run waiting, a finished one resumes it with the node's signal`() {
        val run = waiting()
        every { runs.findWaiting(null) } returns listOf(run)
        assertThat(service.sweep()).isZero()
        assertThat(asked.single()).isEqualTo("bo/1234abcd" to "abc123")

        report = report.copy(state = PipelineState.SUCCESS, status = "success")
        val signal = slot<CiSignal>()
        assertThat(service.sweep()).isEqualTo(1)
        verify { engine.resumeWithSignal(any(), capture(signal)) }
        assertThat(signal.captured.signal).isEqualTo("pipeline")
        assertThat(signal.captured.status).isEqualTo(CiSignalStatus.SUCCESS)
    }

    @Test
    fun `a failed pipeline resumes with the failed jobs and their log tails, which the node passes on as feedback`() {
        report = report.copy(
            state = PipelineState.FAILED, status = "failed",
            failedJobs = listOf(FailedJob("frontend:test", "test", "https://git.example.com/g/shop/-/jobs/9", "× renders a row\nError: Test timed out in 5000ms.")),
        )
        every { runs.findWaiting(null) } returns listOf(waiting())
        val signal = slot<CiSignal>()
        assertThat(service.sweep()).isEqualTo(1)
        verify { engine.resumeWithSignal(any(), capture(signal)) }
        assertThat(signal.captured.status).isEqualTo(CiSignalStatus.FAIL)
        assertThat(signal.captured.message).contains("Pipeline #7 of bo/1234abcd: failed", "### Failed job frontend:test (stage test)", "Test timed out")

        val state = mapOf<String, Any?>(pl.mos.backoffice.projects.application.port.out.RunStateKeys.CI to signal.captured.toStateMap())
        val run = waiting()
        val context = pl.mos.backoffice.projects.application.port.out.NodeContext(run, project, wait, run.nodes.last(), state)
        val result = WaitForCiNodeExecutor().execute(context)
        assertThat(result.port).isEqualTo(WorkflowNode.PORT_FAIL)
        assertThat(pl.mos.backoffice.projects.application.port.out.RunStateKeys.pendingFeedback(result.stateUpdates).single())
            .startsWith("[Wait for the pipeline] Pipeline #7").contains("Test timed out")
    }

    @Test
    fun `a node that does not watch, a missing CI_READ token or no pushed branch leave the run alone`() {
        val plain = WorkflowNode.WaitForCi("ci", "Wait for CI", ci = CiWaitSpec("pipeline"))
        val manual = definition.copy(nodes = definition.nodes.map { if (it.id == "ci") plain else it })
        every { runs.findWaiting(null) } returns listOf(waiting(manual, plain))
        assertThat(service.sweep()).isZero()

        every { runs.findWaiting(null) } returns listOf(waiting())
        every { tokens.findByProject(project.id) } returns listOf(token.copy(capabilities = setOf(ScmCapability.GIT_READ)))
        assertThat(service.sweep()).isZero()

        every { tokens.findByProject(project.id) } returns listOf(token)
        every { runs.findWaiting(null) } returns listOf(Fixtures.runningRun(project, definition).waitingForCi(wait, "pipeline", now))
        assertThat(service.sweep()).isZero()
        assertThat(asked).isEmpty()
        verify(exactly = 0) { engine.resumeWithSignal(any(), any()) }
    }
}
