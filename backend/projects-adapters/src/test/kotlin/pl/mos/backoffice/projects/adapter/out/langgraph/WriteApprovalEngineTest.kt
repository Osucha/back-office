package pl.mos.backoffice.projects.adapter.out.langgraph

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.EditedArtifact
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.ExternalWriteAction
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
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import java.util.UUID

/**
 * ADR 0033 end to end on the engine (PostgreSQL checkpoints, WireMock as Jira): a Jira write step pauses BEFORE the
 * write with a write confirmation in the inbox, approve writes exactly the previewed content, reject writes nothing and
 * leaves through the step's failure port. The definitions carry no `approval` setting: REQUIRED is the default.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class WriteApprovalEngineTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var connections: ManageConnections
    @Autowired lateinit var startRun: StartRun
    @Autowired lateinit var runQueries: RunQueries
    @Autowired lateinit var approvals: ApprovalQueries
    @Autowired lateinit var decide: DecideApproval
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var engine: LangGraphWorkflowEngine
    @Autowired lateinit var testBeans: ProjectsTestBeans
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort

    private val jira = WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true)).also { it.start() }

    @AfterEach
    fun stop() = jira.stop()

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

    private fun setUp(projectKey: String): WorkflowRun {
        val project = projects.create(
            ManageProjects.ProjectCommand(
                key = projectKey, name = "Project $projectKey", repositories = listOf(GitRepository.legacy("https://git.example.com/g/$projectKey.git", "main")),
                defaults = ProjectDefaults(modelProfileId = catalog.add()),
            ),
        )
        connections.create(
            project.id,
            ManageConnections.ConnectionCommand(
                ConnectionType.JIRA, "writer", jira.baseUrl(), "writer@acme.com", "writer-token",
                setOf(ConnectionCapability.ISSUE_COMMENT, ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ),
                acknowledgeClassicToken = true,
            ),
        )
        tasks.register(task("notify-done", TaskExecutor.NOTIFY, mapOf("messageTemplate" to "Done in run {{run.short}} for {{input.issueKey}}.")))
        tasks.register(task("move-to-review", TaskExecutor.TRANSITION_ISSUE, mapOf("toStatus" to "In Review")))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "tell and move",
                nodes = listOf(
                    WorkflowNode.Start("start"),
                    WorkflowNode.Task("tell", "Tell the issue", task = TaskBinding("notify-done")),
                    WorkflowNode.Task("move", "Move to review", task = TaskBinding("move-to-review")),
                    WorkflowNode.End("ok", "Done", end = EndSpec(RunOutcome.SUCCESS)),
                    WorkflowNode.End("not-moved", "Not moved", end = EndSpec(RunOutcome.FAILURE)),
                ),
                edges = listOf(edge("start", "out", "tell"), edge("tell", "success", "move"), edge("move", "success", "ok"), edge("move", "failure", "not-moved")),
            ),
        )
        assertThat(workflow.nodes.filterIsInstance<WorkflowNode.Task>().map { it.task.overrides.config }).allMatch { "approval" !in it }
        return startRun.start(workflow.id, RunInput("$projectKey-42"))
    }

    private fun task(key: String, executor: TaskExecutor, config: Map<String, Any?>) =
        ResolvedTask(key, key, executor, null, TaskPrompt(null, null), emptyList(), config, null, null, emptyList(), emptyList(), emptyList(), listOf(key), listOf("success", "failure"))

    @Test
    fun `the engine pauses before each Jira write, approve writes exactly the preview, reject writes nothing`() {
        jira.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(404)))
        jira.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(201)))
        val projectKey = key("WRA")
        val started = setUp(projectKey)
        val issue = "$projectKey-42"

        // 1. the comment step pauses with a write confirmation; nothing reached Jira
        var run = awaitStatus(started.id, RunStatus.WAITING)
        assertThat(run.waitingOn?.kind).isEqualTo(WaitKind.WRITE_CONFIRMATION)
        assertThat(run.nodes.last { it.nodeId == "tell" }.status).isEqualTo(NodeExecutionStatus.WAITING)
        val confirmComment = approvals.get(run.openApprovalId!!)
        assertThat(confirmComment.kind).isEqualTo(ApprovalKind.WRITE_CONFIRMATION)
        assertThat(confirmComment.question).isEqualTo("Agent wants to comment on $issue")
        assertThat(confirmComment.options).containsExactly("approve", "reject")
        assertThat(confirmComment.approvers.projectRoles).containsExactly("owner", "maintainer")
        val preview = confirmComment.write!!
        assertThat(preview.action).isEqualTo(ExternalWriteAction.JIRA_COMMENT)
        assertThat(preview.comment).startsWith("Done in run ").endsWith("for $issue.")
        assertThat(approvals.list(ApprovalStatus.OPEN).map { it.id }).contains(confirmComment.id)
        jira.verify(0, postRequestedFor(urlPathEqualTo("/rest/api/3/issue/$issue/comment")))
        assertThat(testBeans.events.filter { it.type == "RunWaitingForHuman" && it.payload["approvalId"] == confirmComment.id.toString() }.single().payload["kind"])
            .isEqualTo("WRITE_CONFIRMATION")

        // the preview cannot be edited on the way
        assertThatThrownBy {
            decide.decide(confirmComment.id, DecideApproval.DecisionCommand("approve", editedArtifacts = listOf(EditedArtifact(ArtifactId.new(), "x"))))
        }.isInstanceOf(ValidationException::class.java)

        // 2. approve: exactly the previewed text is posted, then the transition step pauses for its own confirmation
        decide.decide(confirmComment.id, DecideApproval.DecisionCommand("approve"))
        run = awaitStatus(started.id, RunStatus.WAITING, RunStatus.FAILED)
        assertThat(run.status).isEqualTo(RunStatus.WAITING)
        assertThat(run.nodes.last { it.nodeId == "tell" }.status).isEqualTo(NodeExecutionStatus.SUCCEEDED)
        val posted = jira.findAll(postRequestedFor(urlPathEqualTo("/rest/api/3/issue/$issue/comment"))).single().bodyAsString
        assertThat(posted).contains("\"text\":\"${preview.comment}\"")
        val confirmMove = approvals.get(run.openApprovalId!!)
        assertThat(confirmMove.question).isEqualTo("Agent wants to move $issue to In Review")
        assertThat(confirmMove.write!!.toStatus).isEqualTo("In Review")

        // 3. reject: nothing is moved, the step leaves through its failure port
        decide.decide(confirmMove.id, DecideApproval.DecisionCommand("reject", "not yet"))
        run = awaitStatus(started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(run.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(run.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(run.nodes.last { it.nodeId == "move" }.let { it.status to it.port }).isEqualTo(NodeExecutionStatus.FAILED to "failure")
        assertThat(run.nodes.last { it.nodeId == "move" }.error).contains("write rejected by Olivia Owner").contains("not yet")
        assertThat(run.nodes.last().nodeId).isEqualTo("not-moved")
        jira.verify(0, postRequestedFor(urlPathEqualTo("/rest/api/3/issue/$issue/transitions")))

        // 4. audit: both decisions and the one write, with the digest of what was approved
        val ops = audit.entries.filter { it.details["runId"] == started.id.toString() || it.entityId == started.id.toString() }
        assertThat(ops.map { it.operation }).contains("RUN_WRITE_DECIDED", "EXTERNAL_WRITE")
        assertThat(ops.single { it.operation == "EXTERNAL_WRITE" }.details).containsEntry("contentSha256", preview.digest()).containsEntry("approvedBy", "Olivia Owner")
    }

    @Test
    fun `approval NOT_REQUIRED on the node writes without a confirmation`() {
        jira.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(404)))
        jira.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(201)))
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/issue/NRQ-1/transitions")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody("""{"transitions":[{"id":"31","name":"Review","to":{"name":"In Review"}}]}"""),
            ),
        )
        val project = projects.create(
            ManageProjects.ProjectCommand(key = key("NRQ"), name = "No approval", repositories = listOf(GitRepository.legacy("https://git.example.com/g/nrq.git", "main")), defaults = ProjectDefaults(modelProfileId = catalog.add())),
        )
        connections.create(
            project.id,
            ManageConnections.ConnectionCommand(ConnectionType.JIRA, "mover", jira.baseUrl(), "mover@acme.com", "t", setOf(ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ), acknowledgeClassicToken = true),
        )
        tasks.register(task("move-now", TaskExecutor.TRANSITION_ISSUE, mapOf("toStatus" to "In Review")))
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "move now",
                nodes = listOf(
                    WorkflowNode.Start("start"),
                    WorkflowNode.Task("move", "Move", task = TaskBinding("move-now", TaskOverrides(config = mapOf("approval" to "NOT_REQUIRED")))),
                    WorkflowNode.End("ok", "Done", end = EndSpec(RunOutcome.SUCCESS)),
                ),
                edges = listOf(edge("start", "out", "move"), edge("move", "success", "ok")),
            ),
        )
        val run = awaitStatus(startRun.start(workflow.id, RunInput("NRQ-1")).id, RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.WAITING)
        assertThat(run.status).isEqualTo(RunStatus.SUCCEEDED)
        jira.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/issue/NRQ-1/transitions")))
    }
}
