package pl.mos.backoffice.projects.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.shared.ValidationException

/** ADR 0033: approval before external writes in the domain model. */
class WriteApprovalModelTest {

    private val comment = PendingWrite(ExternalWriteAction.JIRA_COMMENT, "SHOP-42", comment = "Merged in !12.\n\nThanks.", connectionName = "commenter")

    @Test
    fun `a missing approval setting means REQUIRED, also for definitions saved before it existed`() {
        assertThat(WriteApprovalConfig.mode(emptyMap())).isEqualTo(WriteApprovalMode.REQUIRED)
        assertThat(WriteApprovalConfig.mode(mapOf("approval" to " "))).isEqualTo(WriteApprovalMode.REQUIRED)
        assertThat(WriteApprovalConfig.mode(mapOf("approval" to "not_required"))).isEqualTo(WriteApprovalMode.NOT_REQUIRED)
        assertThatThrownBy { WriteApprovalConfig.mode(mapOf("approval" to "off")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `default approvers are project owners and maintainers, accountable owner`() {
        assertThat(WriteApprovalConfig.approvers(emptyMap())).isEqualTo(GateApprovers(listOf("owner", "maintainer"), accountable = "owner"))
        val custom = WriteApprovalConfig.approvers(mapOf("approvers" to mapOf("projectRoles" to listOf("approver"), "forbidSelfApproval" to "true")))
        assertThat(custom.projectRoles).containsExactly("approver")
        assertThat(custom.accountable).isNull()
        assertThat(custom.forbidSelfApproval).isTrue()
        assertThatThrownBy { WriteApprovalConfig.approvers(mapOf("approvers" to mapOf("users" to listOf("bob")))) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a pending write describes itself, survives the run state and has a stable digest`() {
        assertThat(comment.question()).isEqualTo("Agent wants to comment on SHOP-42")
        assertThat(PendingWrite.fromStateMap(comment.toStateMap())).isEqualTo(comment)
        assertThat(comment.digest()).hasSize(64).isEqualTo(comment.copy(connectionName = "other").digest())
        assertThat(comment.copy(comment = "Merged.").digest()).isNotEqualTo(comment.digest())
        val move = PendingWrite(ExternalWriteAction.JIRA_TRANSITION, "SHOP-42", toStatus = "In Review")
        assertThat(move.question()).isEqualTo("Agent wants to move SHOP-42 to In Review")
        assertThatThrownBy { PendingWrite(ExternalWriteAction.JIRA_TRANSITION, "SHOP-42") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a write confirmation carries its write, offers approve and reject, and cannot edit artefacts`() {
        val run = Fixtures.runningRun()
        val approval = ApprovalRequest(
            id = ApprovalRequestId.new(), runId = run.id, nodeId = "tell", projectId = run.projectId, projectKey = "SHOP", workflowName = "w",
            issueKey = "SHOP-42", question = comment.question(), options = WriteApprovalConfig.OPTIONS, createdAt = Fixtures.now,
            kind = ApprovalKind.WRITE_CONFIRMATION, write = comment,
        )
        assertThatThrownBy { approval.decide(Decision("approve", editedArtifacts = listOf(EditedArtifact(ArtifactId.new(), "x")), decidedAt = Fixtures.now)) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { approval.decide(Decision("revise", decidedAt = Fixtures.now)) }.isInstanceOf(ValidationException::class.java)
        assertThat(approval.decide(Decision("REJECT", decidedAt = Fixtures.now)).decision!!.option).isEqualTo("reject")
        assertThatThrownBy { approval.copy(write = null) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the task node stays open while its write waits and continues running after the decision`() {
        val run = Fixtures.runningRun()
        val node = run.definitionSnapshot.requireNode("transition")
        val approvalId = ApprovalRequestId.new()
        val waiting = run.nodeStarted(node, Fixtures.now).waitingForWriteConfirmation(node, approvalId, Fixtures.now)

        assertThat(waiting.status).isEqualTo(RunStatus.WAITING)
        assertThat(waiting.waitingOn).isEqualTo(WaitingOn(WaitKind.WRITE_CONFIRMATION, "transition", approvalId = approvalId))
        assertThat(waiting.nodes.last().status).isEqualTo(NodeExecutionStatus.WAITING)

        val resumed = waiting.resumedWithDecision(Decision("approve", decidedAt = Fixtures.now), Fixtures.now)
        assertThat(resumed.status).isEqualTo(RunStatus.RUNNING)
        assertThat(resumed.waitingOn).isNull()
        assertThat(resumed.nodes.last().status).isEqualTo(NodeExecutionStatus.RUNNING)
        assertThat(resumed.nodeFinished("transition", "success", Fixtures.now).nodes.last().status).isEqualTo(NodeExecutionStatus.SUCCEEDED)

        assertThatThrownBy { run.waitingForWriteConfirmation(node, approvalId, Fixtures.now) }.isInstanceOf(pl.mos.backoffice.shared.InvalidStateException::class.java)
    }
}
