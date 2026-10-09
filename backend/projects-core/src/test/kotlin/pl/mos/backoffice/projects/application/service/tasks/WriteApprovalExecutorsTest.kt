package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ExternalWriteAction
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/** ADR 0033: Jira write steps prepare the exact write, ask for confirmation (default) and write exactly that after approval. */
class WriteApprovalExecutorsTest {

    private val project = Fixtures.project()
    private val connections = ConnectionFakes.InMemoryConnections()
    private val trackers = ConnectionFakes.FakeTrackers()
    private val access = JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), trackers)
    private val audit = RecordingAuditPort()
    private val notify = NotifyTaskExecutor(access, TemplateRenderer(), TemplateModelBuilder(), audit)
    private val transition = TransitionIssueTaskExecutor(access, audit)

    private fun task(executor: TaskExecutor, config: Map<String, Any?>) =
        ResolvedTask("t", "t", executor, null, TaskPrompt(null, null), emptyList(), config, null, null, emptyList(), emptyList(), emptyList(), listOf("t"), listOf("success", "failure"))

    private fun context(state: Map<String, Any?> = emptyMap()): NodeContext {
        val node = WorkflowNode.Task("tell", "Tell the issue", task = TaskBinding("t"))
        val run = Fixtures.runningRun(project).nodeStarted(node, Fixtures.now)
        return NodeContext(run, project, node, run.nodes.last(), state)
    }

    @Test
    fun `a comment is rendered once and waits for confirmation by default, nothing is written yet`() {
        connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        val result = notify.execute(context(), task(TaskExecutor.NOTIFY, mapOf("messageTemplate" to "Run {{run.short}} done for {{input.issueKey}}")))

        val write = result.pendingWrite!!
        assertThat(write.action).isEqualTo(ExternalWriteAction.JIRA_COMMENT)
        assertThat(write.issueKey).isEqualTo("SHOP-1")
        assertThat(write.comment).startsWith("Run ").endsWith("done for SHOP-1")
        assertThat(write.connectionName).isEqualTo("commenter")
        assertThat(write.question()).isEqualTo("Agent wants to comment on SHOP-1")
        assertThat(result.writeApprovers).isEqualTo(WriteApprovalConfig.DEFAULT_APPROVERS)
        assertThat(trackers.comments).isEmpty()
        assertThat(audit.entries).isEmpty()
    }

    @Test
    fun `after approval exactly the previewed text is written and audited with its digest`() {
        connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        val config = mapOf<String, Any?>("messageTemplate" to "Hello {{input.issueKey}}")
        val write = notify.execute(context(), task(TaskExecutor.NOTIFY, config)).pendingWrite!!

        val decision = mapOf("option" to "approve", "decidedBy" to "Olivia Owner")
        val result = notify.commit(context(mapOf(RunStateKeys.DECISION to decision)), task(TaskExecutor.NOTIFY, mapOf("messageTemplate" to "CHANGED {{input.issueKey}}")), write)

        assertThat(result.failed).isFalse()
        assertThat(trackers.comments).containsExactly("SHOP-1" to write.comment!!)
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("EXTERNAL_WRITE")
        assertThat(entry.details).containsEntry("contentSha256", write.digest()).containsEntry("approvedBy", "Olivia Owner").containsEntry("result", "WRITTEN")
    }

    @Test
    fun `approval NOT_REQUIRED writes right away with a warning`() {
        connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        val warnings = mutableListOf<String>()
        val node = WorkflowNode.Task("tell", "Tell", task = TaskBinding("t"))
        val run = Fixtures.runningRun(project).nodeStarted(node, Fixtures.now)
        val ctx = NodeContext(run, project, node, run.nodes.last(), emptyMap(), { warnings += it })

        val result = notify.execute(ctx, task(TaskExecutor.NOTIFY, mapOf("messageTemplate" to "fixed text", "approval" to "NOT_REQUIRED")))

        assertThat(result.pendingWrite).isNull()
        assertThat(trackers.comments).containsExactly("SHOP-1" to "fixed text")
        assertThat(warnings).anyMatch { it.contains("approval NOT_REQUIRED") }
        assertThat(audit.entries.single().details).containsEntry("approval", "NOT_REQUIRED")
    }

    @Test
    fun `a transition is previewed with its target status and moves only after approval`() {
        connections.save(ConnectionFakes.connection("mover", setOf(ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ)))
        val approvers = mapOf("projectRoles" to listOf("owner"), "users" to listOf(UUID(0, 7).toString()), "forbidSelfApproval" to true)
        val result = transition.execute(context(), task(TaskExecutor.TRANSITION_ISSUE, mapOf("toStatus" to "In Review", "approvers" to approvers)))

        val write = result.pendingWrite!!
        assertThat(write.describe()).isEqualTo("move SHOP-1 to In Review")
        assertThat(result.writeApprovers).isEqualTo(GateApprovers(listOf("owner"), listOf(UUID(0, 7)), "owner", true))
        assertThat(trackers.calls.filter { it.startsWith("transition") }).isEmpty()

        transition.commit(context(mapOf(RunStateKeys.DECISION to mapOf("option" to "approve", "decidedBy" to "Olivia"))), task(TaskExecutor.TRANSITION_ISSUE, mapOf("toStatus" to "Done")), write)
        assertThat(trackers.calls).contains("transition SHOP-1 In Review")
    }

    @Test
    fun `no connection fails the step before anybody is asked to approve`() {
        connections.save(ConnectionFakes.connection("reader", READ))
        assertThatThrownBy { notify.execute(context(), task(TaskExecutor.NOTIFY, mapOf("messageTemplate" to "x"))) }
            .isInstanceOf(IntegrationException::class.java).hasMessageContaining("[ISSUE_COMMENT]")
    }

    @Test
    fun `a commit of another kind of write is refused`() {
        connections.save(ConnectionFakes.connection("commenter", setOf(ConnectionCapability.ISSUE_COMMENT)))
        val write = notify.execute(context(), task(TaskExecutor.NOTIFY, mapOf("messageTemplate" to "x"))).pendingWrite!!
        assertThat(transition.commit(context(), task(TaskExecutor.TRANSITION_ISSUE, mapOf("toStatus" to "Done")), write).failed).isTrue()
        assertThat(trackers.comments).isEmpty()
    }
}
