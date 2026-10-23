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
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.out.FailedJob
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.PipelineReport
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.application.port.out.PipelineStatusPort
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.tasks.ProjectDigestTaskExecutor
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Duration

/** ADR 0052 step 3: the watcher's schedule and the digest of what happened in the project. */
class WatcherBuildingBlocksTest {

    private val project = Fixtures.project()
    private val watcher = Fixtures.issueWorkflow(project).copy(
        id = WorkflowDefinitionId.new(), name = "Watcher", triggers = listOf(Trigger(TriggerType.SCHEDULE, intervalMinutes = 60)),
    )
    private val change = Fixtures.issueWorkflow(project)
    private val runs = mockk<WorkflowRunRepository>()

    @Test
    fun `a schedule starts its workflow when due, never while a run is active, and passes since`() {
        val workflows = mockk<WorkflowDefinitionRepository> { every { findByProjectId(project.id) } returns listOf(watcher, change) }
        val projects = mockk<ProjectRepository> { every { findAll(any()) } returns listOf(project) }
        val input = slot<RunInput>()
        val startRun = mockk<StartRun> { every { start(watcher.id, capture(input), TriggerSource.SCHEDULE) } returns Fixtures.runningRun(project, watcher) }
        val previous = Fixtures.runningRun(project, watcher).copy(createdAt = now.minus(Duration.ofMinutes(61)))
        every { runs.existsActiveByWorkflow(watcher.id) } returns false
        every { runs.find(any(), any(), 0, 1) } returns PageOf(listOf(previous), 0, 1, 1)
        val service = ScheduledRunService(projects, workflows, runs, { startRun }, Clock.fixed(now))

        assertThat(service.sweep()).isEqualTo(1)
        assertThat(input.captured.parameters["since"]).isEqualTo(previous.createdAt.toString())

        every { runs.find(any(), any(), 0, 1) } returns PageOf(listOf(previous.copy(createdAt = now.minus(Duration.ofMinutes(10)))), 0, 1, 1)
        assertThat(service.sweep()).isZero()
        every { runs.find(any(), any(), 0, 1) } returns PageOf(emptyList(), 0, 1, 0)
        every { runs.existsActiveByWorkflow(watcher.id) } returns true
        assertThat(service.sweep()).isZero()
        verify(exactly = 1) { startRun.start(any(), any(), any()) }

        assertThatThrownBy { Trigger(TriggerType.SCHEDULE, intervalMinutes = 1) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the digest names repeated and failed steps, gate comments and failed pipeline jobs of other workflows`() {
        val implement = WorkflowNode.Task("implement", "Implement", task = TaskBinding("implement-task-list"))
        val gate = WorkflowNode.HumanGate("merge-gate", "Merge?", gate = GateSpec("Merge?"))
        val busy = Fixtures.runningRun(project, change, RunInput(null, mapOf("title" to "Fix the CI")))
            .nodeStarted(implement, now).nodeFailed("implement", "verifyCommand exited with code 1", now)
            .nodeStarted(implement, now).nodeFinished("implement", "success", now)
            .nodeStarted(gate, now).nodeFinished("merge-gate", "revise", now, summary = "Decision: REVISE - fix the findings")
            .failed(RunFailure("implement", "node implement exceeded maxVisits=8"), now)
        val own = Fixtures.runningRun(project, watcher)
        every { runs.find(any(), any(), 0, any()) } returns PageOf(listOf(busy, own), 0, 2, 2)
        val token = ScmConnection(
            ScmConnectionId.new(), project.id, project.repositories.single().key, "ci", ScmProvider.GITLAB, setOf(ScmCapability.CI_READ),
            SealedSecret("k1", "w", "c"), null, null, now, Actor.SYSTEM, now, Actor.SYSTEM,
        )
        val connections = mockk<ScmConnectionRepository> { every { findByProject(project.id) } returns listOf(token) }
        val pipelines = PipelineStatusPort { _, _, ref, _ ->
            if (ref == "main") PipelineReport(PipelineState.FAILED, 9, null, "failed", listOf(FailedJob("security:dependencies", "test", null, "dompurify 3.4.16 CRITICAL")))
            else PipelineReport(PipelineState.SUCCESS, 8, null, "success")
        }
        val executor = ProjectDigestTaskExecutor(runs, connections, pipelines, Clock.fixed(now))
        val task = ResolvedTask(
            key = "project-digest", name = "Digest", executor = TaskExecutor.PROJECT_DIGEST, modelProfileId = null, prompt = TaskPrompt(null, null),
            skills = emptyList(), config = mapOf("pipelineRefs" to "develop,main"), outputSchema = null, confidenceField = null,
            produces = emptyList(), consumes = emptyList(), interfaces = emptyList(), lineage = listOf("project-digest"), ports = listOf("success"),
        )
        val node = WorkflowNode.Task("digest", "Digest", task = TaskBinding("project-digest"))
        val run = own.nodeStarted(node, now)
        val digest = executor.execute(NodeContext(run, project, node, run.nodes.last(), emptyMap()), task).artifacts.single().content

        assertThat(digest).contains("1 run(s) of other workflows", "Title: Fix the CI", "Failed at implement: node implement exceeded maxVisits=8")
        assertThat(digest).contains("Step implement ran 2 times", "Step implement failed: verifyCommand exited with code 1", "Gate merge-gate: Decision: REVISE - fix the findings")
        assertThat(digest).contains("- develop: success", "- main: failed", "failed job security:dependencies", "dompurify 3.4.16")
        assertThat(digest).doesNotContain("## Watcher")
    }
}
