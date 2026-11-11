package pl.mos.backoffice.projects.adapter.out.langgraph

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeLlmGateway
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.AgentQuestion
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.EndSpec
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
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.TaskOverrides
import java.util.UUID

/**
 * ADR 0060 end to end on the engine (PostgreSQL checkpoints): a model answer that replies only with
 * `{"agentQuestions": [...]}` pauses the run on an AGENT_QUESTIONS approval; `answer` runs the step again in the same
 * execution with "## Answers of the owner" in its prompt, `cancel` fails it, and over `maxQuestionRounds` the step fails.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class AgentQuestionsEngineTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var startRun: StartRun
    @Autowired lateinit var runQueries: RunQueries
    @Autowired lateinit var approvals: ApprovalQueries
    @Autowired lateinit var decide: DecideApproval
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var llm: FakeLlmGateway
    @Autowired lateinit var engine: LangGraphWorkflowEngine
    @Autowired lateinit var testBeans: ProjectsTestBeans
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

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

    private fun start(config: Map<String, Any?> = emptyMap()): WorkflowRun {
        tasks.llm("decide-design", "Decide the design of {{input.issueKey}}.")
        val projectKey = key("AQ")
        val project = projects.create(
            ManageProjects.ProjectCommand(
                key = projectKey, name = "Questions $projectKey", repositories = listOf(GitRepository.legacy("https://git.example.com/g/$projectKey.git", "main")),
                defaults = ProjectDefaults(modelProfileId = catalog.add()),
            ),
        )
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "decide",
                nodes = listOf(
                    WorkflowNode.Start("start"),
                    WorkflowNode.Task("ask", "Decide", task = TaskBinding("decide-design", TaskOverrides(config = config))),
                    WorkflowNode.End("ok", "Done", end = EndSpec(RunOutcome.SUCCESS)),
                    WorkflowNode.End("stopped", "Stopped", end = EndSpec(RunOutcome.FAILURE)),
                ),
                edges = listOf(edge("start", "out", "ask"), edge("ask", "success", "ok"), edge("ask", "failure", "stopped")),
            ),
        )
        return startRun.start(workflow.id, RunInput("$projectKey-1"))
    }

    @Test
    fun `questions pause the run, the answers run the step again with them in the prompt`() {
        llm.reset()
        llm.answer("""{"agentQuestions": [{"question": "Which database?", "why": "the schema depends on it"}, "Keep the old API?"]}""")
        llm.answer("Use PostgreSQL and keep the API.")
        val started = start()

        var run = awaitStatus(started.id, RunStatus.WAITING, RunStatus.FAILED, RunStatus.SUCCEEDED)
        assertThat(run.status).isEqualTo(RunStatus.WAITING)
        assertThat(run.waitingOn?.kind).isEqualTo(WaitKind.AGENT_QUESTIONS)
        assertThat(run.nodes.single { it.nodeId == "ask" }.status).isEqualTo(NodeExecutionStatus.WAITING)
        val approval = approvals.get(run.openApprovalId!!)
        assertThat(approval.kind).isEqualTo(ApprovalKind.AGENT_QUESTIONS)
        assertThat(approval.questions).containsExactly(AgentQuestion("Which database?", "the schema depends on it"), AgentQuestion("Keep the old API?"))
        assertThat(approval.questionRound).isEqualTo(1)
        assertThat(approval.maxQuestionRounds).isEqualTo(RunDirectory.DEFAULT_MAX_QUESTION_ROUNDS)
        // the REST contract: the run reports the kind of its wait, the approval its questions, round and limit
        val rest = pl.mos.backoffice.projects.adapter.`in`.rest.RestMapper()
        assertThat(rest.toDto(run).waitingOn!!.kind).isEqualTo("AGENT_QUESTIONS")
        val dto = rest.toDto(approval, emptyList())
        assertThat(dto.kind).isEqualTo("AGENT_QUESTIONS")
        assertThat(dto.questions!!.map { it.question to it.why }).containsExactly("Which database?" to "the schema depends on it", "Keep the old API?" to null)
        assertThat(dto.questionRound to dto.maxQuestionRounds).isEqualTo(1 to 2)
        assertThat(approval.options).containsExactly("answer", "cancel")
        assertThat(approval.approvers.accountable).isEqualTo("owner")
        assertThat(approval.question).isEqualTo("The agent asks 2 questions (round 1)")
        assertThat(testBeans.events.single { it.type == "RunWaitingForHuman" && it.payload["approvalId"] == approval.id.toString() }.payload["kind"])
            .isEqualTo("AGENT_QUESTIONS")
        assertThat(llm.prompts.first().messages.first().content).contains("Questions to the owner").contains("agentQuestions")

        // answering needs the answers
        assertThatThrownBy { decide.decide(approval.id, DecideApproval.DecisionCommand("answer", " ")) }.isInstanceOf(ValidationException::class.java)

        decide.decide(approval.id, DecideApproval.DecisionCommand("answer", "PostgreSQL 16; yes, keep it."))
        run = awaitStatus(started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.outcome).isEqualTo(RunOutcome.SUCCESS)
        val step = run.nodes.single { it.nodeId == "ask" }
        assertThat(step.status).describedAs("one execution: answering runs the same step again").isEqualTo(NodeExecutionStatus.SUCCEEDED)
        assertThat(step.usage).describedAs("asking is part of the step's cost").isEqualTo(TokenUsage(2000, 200))
        assertThat(llm.prompts).hasSize(2)
        val again = llm.prompts[1].messages.last().content
        assertThat(again).startsWith("Decide the design of").contains("## Answers of the owner").contains("Which database?").contains("PostgreSQL 16; yes, keep it.")
        assertThat(audit.entries.single { it.operation == "RUN_AGENT_QUESTIONS_ANSWERED" && it.details["runId"] == started.id.toString() }.details)
            .containsEntry("option", "answer").containsEntry("questionRound", 1)
    }

    @Test
    fun `cancel fails the step through its failure port`() {
        llm.reset()
        llm.answer("""{"agentQuestions": ["What is the scope?"]}""")
        val started = start()
        var run = awaitStatus(started.id, RunStatus.WAITING)
        decide.decide(run.openApprovalId!!, DecideApproval.DecisionCommand("cancel", "not now"))
        run = awaitStatus(started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.outcome).isEqualTo(RunOutcome.FAILURE)
        val step = run.nodes.single { it.nodeId == "ask" }
        assertThat(step.status to step.port).isEqualTo(NodeExecutionStatus.FAILED to "failure")
        assertThat(step.error).contains("cancelled the step at the agent's questions").contains("not now")
        assertThat(llm.prompts).hasSize(1)
    }

    @Test
    fun `over the limit the step fails, and without questions allowed the answer is the result`() {
        llm.reset()
        llm.answer("""{"agentQuestions": ["Again?"]}""")
        val limited = start(mapOf(RunDirectory.MAX_QUESTION_ROUNDS to 1))
        var run = awaitStatus(limited.id, RunStatus.WAITING)
        decide.decide(run.openApprovalId!!, DecideApproval.DecisionCommand("answer", "yes"))
        run = awaitStatus(limited.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(run.nodes.single { it.nodeId == "ask" }.error).isEqualTo("the agent asked more than 1 rounds of questions")
        assertThat(llm.prompts[1].messages.first().content).describedAs("no rounds left: the preamble no longer invites questions").doesNotContain("Questions to the owner")

        llm.reset()
        llm.answer("""{"agentQuestions": ["Anything?"]}""")
        val closed = start(mapOf(RunDirectory.ALLOW_QUESTIONS to false))
        run = awaitStatus(closed.id, RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.WAITING)
        assertThat(run.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(run.outcome).isEqualTo(RunOutcome.SUCCESS)
        assertThat(llm.prompts.single().messages.first().content).contains("You cannot ask the owner")
    }
}
