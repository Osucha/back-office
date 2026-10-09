package pl.mos.backoffice.projects.adapter.out.langgraph

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.FakeLlmGateway
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.RetryRun
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.SignalRun
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.CiWaitSpec
import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.DecisionSpec
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.Money
import java.util.UUID

/**
 * Full engine test on PostgreSQL checkpoints: START -> TASK (LLM, mocked) -> DECISION -> HUMAN_GATE
 * (interrupt, revise loop, approve) -> WAIT_FOR_CI (interrupt, resume by signal) -> END(SUCCESS);
 * plus a failing node with retry from that node and a reject that cancels.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class LangGraphWorkflowEngineTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var startRun: StartRun
    @Autowired lateinit var retryRun: RetryRun
    @Autowired lateinit var signalRun: SignalRun
    @Autowired lateinit var runQueries: RunQueries
    @Autowired lateinit var approvals: ApprovalQueries
    @Autowired lateinit var decide: DecideApproval
    @Autowired lateinit var llm: FakeLlmGateway
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var engine: LangGraphWorkflowEngine
    @Autowired lateinit var testBeans: ProjectsTestBeans

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun newProject(key: String) = projects.create(
        ManageProjects.ProjectCommand(
            key = key, name = "Project $key", repositories = listOf(GitRepository.legacy("https://git.example.com/g/$key.git", "main")),
            constitution = "Keep it simple.", defaults = ProjectDefaults(modelProfileId = catalog.add()),
        ),
    )

    private fun awaitStatus(runId: WorkflowRunId, vararg expected: RunStatus, timeoutMillis: Long = 30_000): WorkflowRun {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val run = runQueries.get(runId)
            if (run.status in expected) {
                engine.awaitIdle(runId)
                return runQueries.get(runId)
            }
            Thread.sleep(100)
        }
        throw AssertionError("run $runId did not reach ${expected.toList()} in time; current ${runQueries.get(runId).status}")
    }

    private fun edge(from: String, port: String, to: String) = WorkflowEdge("$from-$port-$to", from, port, to)

    @Test
    fun `gated workflow interrupts at the gate, loops on revise, waits for CI and ends with SUCCESS`() {
        val project = newProject(key("ENG"))
        tasks.llm("draft", "Draft {{input.issueKey}}. Feedback: {{feedback}}", outputSchema = FakeTaskCatalog.CLARIFY_SCHEMA, confidenceField = "confidence", produces = listOf("confidence", "draft"))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "gated",
                nodes = listOf(
                    WorkflowNode.Start("start"),
                    WorkflowNode.Task("draft", "Draft", task = TaskBinding("draft")),
                    WorkflowNode.Decision("sure", "Sure?", decision = DecisionSpec(listOf(DecisionBranch("yes", "confident", "confidence >= 0.8")), defaultBranch = "no")),
                    WorkflowNode.HumanGate("gate", "Review draft", gate = GateSpec("Draft for {{input.issueKey}} ok? {{artifacts.draft}}", showArtifacts = listOf("draft"))),
                    WorkflowNode.WaitForCi("tests", "Wait for tests", ci = CiWaitSpec("tests")),
                    WorkflowNode.End("ok", "Done", end = EndSpec(RunOutcome.SUCCESS)),
                    WorkflowNode.End("ko", "Tests failed", end = EndSpec(RunOutcome.FAILURE)),
                ),
                edges = listOf(
                    edge("start", "out", "draft"), edge("draft", "success", "sure"), edge("sure", "yes", "tests"), edge("sure", "no", "gate"),
                    edge("gate", "approve", "tests"), edge("gate", "revise", "draft"), edge("tests", "success", "ok"), edge("tests", "fail", "ko"),
                ),
            ),
        )
        llm.reset()
        llm.answer("first draft", structured = mapOf("confidence" to 0.4, "questions" to listOf("q1")))
        llm.answer("second draft", structured = mapOf("confidence" to 0.5, "questions" to emptyList<String>()))

        val started = startRun.start(workflow.id, RunInput("ENG-1"))
        assertThat(started.status).isEqualTo(RunStatus.RUNNING)

        // 1. low confidence -> decision takes the default branch -> pauses before the gate
        var run = awaitStatus(started.id, RunStatus.WAITING)
        assertThat(run.waitingOn?.kind).isEqualTo(WaitKind.HUMAN_GATE)
        assertThat(run.nodes.map { it.nodeId to it.status }).containsExactly(
            "start" to NodeExecutionStatus.SUCCEEDED, "draft" to NodeExecutionStatus.SUCCEEDED, "sure" to NodeExecutionStatus.SUCCEEDED, "gate" to NodeExecutionStatus.WAITING,
        )
        assertThat(run.nodes.first { it.nodeId == "sure" }.port).isEqualTo("no")
        assertThat(run.nodes.first { it.nodeId == "draft" }.confidence).isEqualTo(0.4)
        assertThat(run.nodes.first { it.nodeId == "draft" }.cost).isEqualTo(Money.usd(0.0075))
        val approval1 = approvals.get(run.openApprovalId!!)
        assertThat(approval1.question).startsWith("Draft for ENG-1 ok?")
        assertThat(approval1.context.artifactIds).containsExactly(run.artifactNamed("draft")!!.id)

        // 2. REVISE loops back to draft with the comment as feedback, then pauses again
        decide.decide(approval1.id, DecideApproval.DecisionCommand("revise", "add acceptance criteria"))
        run = awaitStatus(started.id, RunStatus.WAITING)
        assertThat(llm.prompts).hasSize(2)
        assertThat(llm.prompts[1].messages.last().content).contains("add acceptance criteria")
        assertThat(run.nodes.map { it.nodeId }).containsExactly("start", "draft", "sure", "gate", "draft", "sure", "gate")
        assertThat(run.nodes.filter { it.nodeId == "draft" }.last().attempt).isEqualTo(2)
        assertThat(run.nodes.filter { it.nodeId == "gate" }.first().port).isEqualTo("revise")
        val approval2 = approvals.get(run.openApprovalId!!)
        assertThat(approval2.id).isNotEqualTo(approval1.id)
        assertThat(approvals.get(approval1.id).status).isEqualTo(ApprovalStatus.DECIDED)

        // 3. APPROVE passes the gate and the run waits for the CI signal
        decide.decide(approval2.id, DecideApproval.DecisionCommand("APPROVE"))
        run = awaitStatus(started.id, RunStatus.WAITING)
        assertThat(run.waitingOn?.kind).isEqualTo(WaitKind.WAIT_FOR_CI)
        assertThat(run.waitingOn?.signal).isEqualTo("tests")
        assertThat(run.waitingOn?.nodeId).isEqualTo("tests")

        // 4. the CI signal resumes the run through the success port and the END node sets the outcome
        val result = signalRun.signal(SignalRun.SignalCommand(project.id, "tests", CiSignalStatus.SUCCESS, message = "42 tests passed"))
        assertThat(result.resumed).isTrue()
        assertThat(result.runId).isEqualTo(started.id)
        run = awaitStatus(started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(run.outcome).isEqualTo(RunOutcome.SUCCESS)
        assertThat(run.nodes.last().nodeId).isEqualTo("ok")
        assertThat(run.nodes.first { it.nodeId == "tests" }.port).isEqualTo("success")
        assertThat(run.lastSignal?.message).isEqualTo("42 tests passed")
        assertThat(run.nodes.count { it.status == NodeExecutionStatus.SUCCEEDED }).isEqualTo(run.nodes.size)
        assertThat(run.cost).isEqualTo(Money.usd(0.015))
        assertThat(run.waitingOn).isNull()
        assertThat(testBeans.events.map { it.type }).contains(
            "WorkflowRunStarted", "NodeStarted", "NodeCompleted", "RunWaitingForHuman", "ApprovalDecided", "RunWaitingForCi", "CiSignalReceived", "WorkflowRunFinished",
        )
    }

    @Test
    fun `an unconnected reject port cancels the run`() {
        val project = newProject(key("REJ"))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "reject",
                nodes = listOf(WorkflowNode.Start("start"), WorkflowNode.HumanGate("gate", "Go?", gate = GateSpec("go?")), WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS))),
                edges = listOf(edge("start", "out", "gate"), edge("gate", "approve", "ok")),
            ),
        )
        val started = startRun.start(workflow.id, RunInput("REJ-1"))
        var run = awaitStatus(started.id, RunStatus.WAITING)
        decide.decide(run.openApprovalId!!, DecideApproval.DecisionCommand("REJECT", "not now"))
        run = awaitStatus(started.id, RunStatus.CANCELLED, RunStatus.FAILED, RunStatus.SUCCEEDED)
        assertThat(run.status).isEqualTo(RunStatus.CANCELLED)
        assertThat(run.nodes.last { it.nodeId == "gate" }.port).isEqualTo("reject")
    }

    @Test
    fun `a failing task with an unconnected failure port fails the run and retry continues from that node`() {
        val project = newProject(key("FAI"))
        tasks.llm("one", "one", produces = listOf("one"))
        tasks.llm("two", "two", produces = listOf("two"))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "failing",
                nodes = listOf(
                    WorkflowNode.Start("start"), WorkflowNode.Task("first", "First", task = TaskBinding("one")),
                    WorkflowNode.Task("second", "Second", task = TaskBinding("two")), WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS)),
                ),
                edges = listOf(edge("start", "out", "first"), edge("first", "success", "second"), edge("second", "success", "ok")),
            ),
        )
        llm.reset()
        llm.answer("ok")
        llm.answers += { throw pl.mos.backoffice.shared.IntegrationException("anthropic", "rate limited", retryable = true) }

        val started = startRun.start(workflow.id, RunInput("FAI-1"))
        var run = awaitStatus(started.id, RunStatus.FAILED, RunStatus.SUCCEEDED)
        assertThat(run.status).isEqualTo(RunStatus.FAILED)
        assertThat(run.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(run.failure?.nodeId).isEqualTo("second")
        assertThat(run.failure?.message).contains("rate limited")
        assertThat(run.nodes.map { it.nodeId to it.status }).containsExactly(
            "start" to NodeExecutionStatus.SUCCEEDED, "first" to NodeExecutionStatus.SUCCEEDED, "second" to NodeExecutionStatus.FAILED,
        )

        llm.reset()
        llm.answer("recovered")
        retryRun.retry(started.id)
        run = awaitStatus(started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(llm.prompts).hasSize(1)
        assertThat(llm.prompts.single().messages.last().content).isEqualTo("two")
        assertThat(run.nodes.map { it.nodeId }).containsExactly("start", "first", "second", "second", "ok")
        assertThat(run.nodes[3].attempt).isEqualTo(2)
        assertThat(run.artifactNamed("two")?.content).isEqualTo("recovered")
        assertThat(run.failure).isNull()
    }

    @Test
    fun `the loop guard stops a workflow that revises forever`() {
        val project = newProject(key("LOO"))
        tasks.llm("spin", "spin", produces = listOf("spin"))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "loop",
                nodes = listOf(
                    WorkflowNode.Start("start"), WorkflowNode.Task("spin", "Spin", maxVisits = 2, task = TaskBinding("spin")),
                    WorkflowNode.Decision("again", "Again?", decision = DecisionSpec(listOf(DecisionBranch("yes", "always", "true")))),
                    WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS)),
                ),
                edges = listOf(edge("start", "out", "spin"), edge("spin", "success", "again"), edge("again", "yes", "spin")),
            ),
        )
        llm.reset()
        llm.answer("spinning")
        val started = startRun.start(workflow.id, RunInput("LOO-1"))
        val run = awaitStatus(started.id, RunStatus.FAILED, RunStatus.SUCCEEDED)
        assertThat(run.status).isEqualTo(RunStatus.FAILED)
        assertThat(run.failure?.message).contains("maxVisits")
        assertThat(run.nodes.count { it.nodeId == "spin" }).isEqualTo(2)
    }
}
