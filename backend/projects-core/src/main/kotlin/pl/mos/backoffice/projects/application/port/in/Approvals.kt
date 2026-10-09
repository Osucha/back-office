package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.EditedArtifact
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import java.util.UUID

/**
 * Use case: answer an approval request. Checks `run.approve` and the gate's approver rule, requires the
 * required checklist items for `approve`, stores the decision with `decidedBy`, applies artefact edits and
 * resumes the run through the port named by the chosen option (`approve`, `revise`, `reject`, ...).
 */
interface DecideApproval {
    fun decide(approvalId: ApprovalRequestId, command: DecisionCommand): ApprovalRequest

    data class DecisionCommand(
        val option: String,
        val comment: String? = null,
        val editedArtifacts: List<EditedArtifact> = emptyList(),
        val checkedItems: List<String> = emptyList(),
        /** ADR 0035, write confirmations: who writes after approval; null = the step's default. */
        val writeAs: WriteIdentity? = null,
    )
}

/**
 * Query (ADR 0035): which identities the CALLER could write a waiting Jira write as, for the write-confirmation
 * dialog. Needs `project.view`; reveals only the caller's own personal connection.
 */
interface WriteIdentityQuery {
    fun writeIdentity(approvalId: ApprovalRequestId): WriteIdentityOptions

    data class WriteIdentityOptions(
        /** What approve does without a choice. */
        val defaultIdentity: WriteIdentity,
        /** The step may write through its system (or pinned) connection, and one is usable. */
        val systemAllowed: Boolean,
        val systemConnectionName: String?,
        /** The step lets the approver write as themselves. */
        val personalAllowed: Boolean,
        /** Name of the caller's own fitting personal connection, or null when they have none. */
        val personalConnectionName: String?,
        /** Why the caller cannot write personally (no personal connection that fits), for the dialog. */
        val personalProblem: String?,
    ) {
        val canApprove: Boolean get() = systemAllowed || personalConnectionName != null
    }
}

/** Queries over the inbox; `list` only returns approvals of projects the caller may view. */
interface ApprovalQueries {
    fun get(approvalId: ApprovalRequestId): ApprovalRequest
    fun list(status: ApprovalStatus): List<ApprovalRequest>
}

/**
 * Use case (ADR 0028): a project owner or maintainer (`run.assign`) names who decides an open approval, for
 * this approval only. Every assignee must be able to decide it apart from the named-users rule (`run.approve`
 * on the project, the gate's roles rule, four-eyes). An empty list clears the assignment (back to the gate's
 * own rule). Audited as `APPROVAL_ASSIGN`, published as `ApprovalAssigned`.
 */
interface AssignApproval {
    fun assign(approvalId: ApprovalRequestId, command: AssignCommand): ApprovalRequest

    /** Project members who could be assigned (same rules as [assign]); needs `run.assign`. */
    fun candidates(approvalId: ApprovalRequestId): List<AssigneeCandidate>

    data class AssignCommand(val userIds: List<UUID>, val comment: String? = null)

    data class AssigneeCandidate(val userId: UUID, val displayName: String, val projectRoles: Set<String>, val assigned: Boolean)
}
