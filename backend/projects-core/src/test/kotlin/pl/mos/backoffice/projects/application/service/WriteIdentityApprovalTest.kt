package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval.DecisionCommand
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.ExternalWriteAction
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

/** ADR 0035: who writes after a write confirmation: the step's system connection or the approver's own personal one. */
class WriteIdentityApprovalTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val approvals = mockk<ApprovalRequestRepository>()
    private val runs = mockk<WorkflowRunRepository>()
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val connections = ConnectionFakes.InMemoryConnections()
    private val audit = RecordingAuditPort()
    private val me = FakeAuthorizationGate.TEST_USER_ID.value

    init {
        every { approvals.save(any()) } answers { firstArg() }
        every { runs.save(any()) } answers { firstArg() }
    }

    private fun service() = ApprovalService(
        approvals, runs, engine, EventPublisher.NOOP,
        FakeAuthorizationGate(Fixtures.userWithRoles(project.id, setOf(ProjectsPermissions.RUN_APPROVE, ProjectsPermissions.PROJECT_VIEW), setOf("owner"))),
        audit, Clock.fixed(now), connections,
    )

    private fun waiting(write: PendingWrite): ApprovalRequest {
        val id = ApprovalRequestId.new()
        val node = definition.requireNode("fetch-issue")
        val run = Fixtures.runningRun(project, definition, startedBy = Fixtures.bob).nodeStarted(node, now).waitingForWriteConfirmation(node, id, now)
        val approval = ApprovalRequest(
            id = id, runId = run.id, nodeId = "fetch-issue", projectId = project.id, projectKey = project.key, workflowName = definition.name,
            issueKey = "SHOP-1", question = write.question(), options = WriteApprovalConfig.OPTIONS, createdAt = now,
            approvers = GateApprovers(listOf("owner")), kind = ApprovalKind.WRITE_CONFIRMATION, write = write, startedBy = Fixtures.bob,
        )
        every { approvals.findById(id) } returns approval
        every { runs.findById(run.id) } returns run
        return approval
    }

    private val actingWrite = PendingWrite(
        ExternalWriteAction.JIRA_COMMENT, "SHOP-1", comment = "hi", connectionMode = ConnectionMode.ACTING_USER, personalAllowed = true, systemAllowed = false,
    )

    @Test
    fun `the confirmation tells the approver which identities they can write as`() {
        val approval = waiting(actingWrite)
        val none = service().writeIdentity(approval.id)
        assertThat(none.defaultIdentity).isEqualTo(WriteIdentity.PERSONAL)
        assertThat(none.personalConnectionName).isNull()
        assertThat(none.personalProblem).contains("you have no personal Jira connection with ISSUE_COMMENT")
        assertThat(none.canApprove).isFalse()

        connections.save(ConnectionFakes.personal("Alice's Jira", setOf(ISSUE_COMMENT), me, "Alice Test"))
        val mine = service().writeIdentity(approval.id)
        assertThat(mine.personalConnectionName).isEqualTo("Alice's Jira")
        assertThat(mine.canApprove).isTrue()
    }

    @Test
    fun `approving as yourself without a personal connection is refused, rejecting is always possible`() {
        val approval = waiting(actingWrite)
        assertThatThrownBy { service().decide(approval.id, DecisionCommand("approve")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("no personal Jira connection")
        val rejected = service().decide(approval.id, DecisionCommand("reject", "not now"))
        assertThat(rejected.decision!!.writeAs).isNull()
    }

    @Test
    fun `the system identity needs fallbackToSystem, the personal one a step that allows it`() {
        val approval = waiting(actingWrite)
        assertThatThrownBy { service().decide(approval.id, DecisionCommand("approve", writeAs = WriteIdentity.SYSTEM)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("writes only as the approving person")

        val systemOnly = waiting(PendingWrite(ExternalWriteAction.JIRA_COMMENT, "SHOP-1", comment = "hi", connectionName = "bot"))
        connections.save(ConnectionFakes.personal("Alice's Jira", setOf(ISSUE_COMMENT), me, "Alice Test"))
        assertThatThrownBy { service().decide(systemOnly.id, DecisionCommand("approve", writeAs = WriteIdentity.PERSONAL)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("does not allow writing with your personal connection")
    }

    @Test
    fun `approving as yourself records the identity in the decision and the audit`() {
        connections.save(ConnectionFakes.personal("Alice's Jira", setOf(ISSUE_COMMENT), me, "Alice Test"))
        val approval = waiting(actingWrite)
        val decided = service().decide(approval.id, DecisionCommand("approve"))
        assertThat(decided.decision!!.writeAs).isEqualTo(WriteIdentity.PERSONAL)
        assertThat(decided.decision!!.toStateMap()).containsEntry("writeAs", "PERSONAL").containsEntry("decidedByUserId", me.toString())
        assertThat(audit.entries.single().details).containsEntry("writeAs", "PERSONAL")
    }
}
