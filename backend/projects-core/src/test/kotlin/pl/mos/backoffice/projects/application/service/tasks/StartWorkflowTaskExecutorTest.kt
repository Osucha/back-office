package pl.mos.backoffice.projects.application.service.tasks

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.StartRunOnBehalf
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import java.util.UUID

/** ADR 0052 step 2: an agent proposes, the owner picks at a gate, the step starts one run per picked proposal. */
class StartWorkflowTaskExecutorTest {

    private val project = Fixtures.project()
    private val watcher = Fixtures.issueWorkflow(project)
    private val target = Fixtures.issueWorkflow(project).copy(id = pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId.new(), name = "Change to MR")
    private val workflows = mockk<WorkflowDefinitionRepository> {
        every { findById(any()) } returns null
        every { findById(target.id) } returns target
        every { findByProjectId(project.id) } returns listOf(watcher, target)
    }
    private val proposals = listOf(
        mapOf("title" to "Cache Gradle in the runner", "idea" to "..."),
        mapOf("title" to "Raise the runner memory", "idea" to "..."),
        mapOf("title" to "Pin dompurify", "idea" to "...", "nested" to mapOf("x" to 1)),
    )
    private val json = mockk<JsonCodec> {
        every { parseObject(any()) } returns mapOf("value" to proposals)
        every { toJson(any()) } returns "[]"
    }
    private val started = mutableListOf<RunInput>()
    private val startRun = mockk<StartRunOnBehalf> {
        val input = slot<RunInput>()
        every { startOnBehalf(target.id, capture(input), TriggerSource.API, DECIDER) } answers { started += input.captured; Fixtures.runningRun(project, target) }
    }
    private val executor = StartWorkflowTaskExecutor({ startRun }, workflows, json)
    private val gate = WorkflowNode.HumanGate("pick", "Pick proposals", gate = GateSpec("Which proposals?", listOf("do", "skip")))
    private val node = WorkflowNode.Task("start", "Start", task = TaskBinding("start-workflow"))

    private fun task(config: Map<String, Any?>) = ResolvedTask(
        key = "start-workflow", name = "Start workflow", executor = TaskExecutor.START_WORKFLOW, modelProfileId = null, prompt = TaskPrompt(null, null),
        skills = emptyList(), config = config, outputSchema = null, confidenceField = null, produces = emptyList(), consumes = emptyList(),
        interfaces = emptyList(), lineage = listOf("start-workflow"), ports = listOf("success", "failure"),
    )

    private fun context(comment: String, afterGate: Boolean = true): NodeContext {
        var run: WorkflowRun = Fixtures.runningRun(project, watcher)
            .addArtifact(Artifact(ArtifactId.new(), "retro", "proposals", ArtifactKind.JSON, "application/json", "[...]", createdAt = now))
        if (afterGate) run = run.nodeStarted(gate, now).nodeFinished("pick", "do", now)
        run = run.nodeStarted(node, now)
        return NodeContext(run, project, node, run.nodes.last(), mapOf(RunStateKeys.LAST_DECISION to mapOf("option" to "do", "comment" to comment, "decidedByUserId" to DECIDER.toString())))
    }

    @Test
    fun `the proposals the owner numbered are started with their fields as run parameters`() {
        val result = executor.execute(context("zrób 1 i 3, dwójkę później"), task(mapOf("workflow" to "change to mr", "parametersFrom" to "proposals", "selection" to "DECISION")))
        assertThat(started.map { it.parameters["title"] }).containsExactly("Cache Gradle in the runner", "Pin dompurify")
        assertThat(started[1].parameters).containsEntry("proposal", 3).doesNotContainKey("nested").containsKey("startedByRun")
        assertThat(result.summary).contains("started 2 run(s) of Change to MR")
        @Suppress("UNCHECKED_CAST")
        assertThat((result.stateUpdates[RunStateKeys.STARTED_RUNS] as List<Map<String, Any?>>).map { it["entry"] }).containsExactly(1, 3)

        started.clear()
        executor.execute(context("all"), task(mapOf("workflow" to target.id.toString(), "parametersFrom" to "proposals", "selection" to "DECISION")))
        assertThat(started).hasSize(3)
        started.clear()
        assertThat(executor.execute(context("nic"), task(mapOf("workflow" to target.id.toString(), "parametersFrom" to "proposals", "selection" to "DECISION"))).summary)
            .isEqualTo("nothing selected, no workflow started")
        assertThat(started).isEmpty()
    }

    @Test
    fun `only right after a gate, only a workflow of the project and never itself`() {
        val config = mapOf("workflow" to target.id.toString(), "parametersFrom" to "proposals")
        assertThatThrownBy { executor.execute(context("1", afterGate = false), task(config)) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("after a human gate")
        assertThatThrownBy { executor.execute(context("1"), task(config + ("workflow" to "unknown"))) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { executor.execute(context("1"), task(config + ("workflow" to watcher.name))) }.hasMessageContaining("cannot start itself")
        assertThatThrownBy { executor.execute(context("1"), task(config + ("parametersFrom" to "missing"))) }.hasMessageContaining("missing")
        verify(exactly = 0) { startRun.startOnBehalf(any(), any(), any(), any()) }
    }

    private companion object {
        val DECIDER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    }
}
