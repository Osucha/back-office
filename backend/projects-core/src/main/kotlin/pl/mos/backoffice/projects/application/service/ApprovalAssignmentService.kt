package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.AssignApproval
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval.AssignCommand
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval.AssigneeCandidate
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.domain.event.ApprovalAssigned
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectAccessDirectory

/**
 * Implements [AssignApproval] (ADR 0028): the manager-led operating model, where a project owner or
 * maintainer decides who does which step. Needs `run.assign` on the approval's project; each assignee must
 * pass [GateApprovalPolicy.canBeAssigned] (holds `run.approve`, satisfies the gate's roles rule and the
 * four-eyes rule), resolved through the identity context's [ProjectAccessDirectory].
 */
class ApprovalAssignmentService(
    private val approvals: ApprovalRequestRepository,
    private val directory: ProjectAccessDirectory,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : AssignApproval {

    override fun assign(approvalId: ApprovalRequestId, command: AssignCommand): ApprovalRequest {
        val approval = load(approvalId)
        val me = gate.require(ProjectsPermissions.RUN_ASSIGN, approval.projectId.value)
        if (approval.status != ApprovalStatus.OPEN) throw InvalidStateException("approval $approvalId is already ${approval.status}")
        val userIds = command.userIds.distinct()
        userIds.forEach { id ->
            val user = directory.user(id) ?: throw ValidationException("user $id does not exist or is not active", "userIds")
            val check = GateApprovalPolicy.canBeAssigned(approval, user)
            if (!check.allowed) throw ValidationException("${user.displayName} cannot decide this gate: ${check.reason}", "userIds")
        }
        val before = approval.assignment?.userIds
        val now = clock.now()
        val assigned = approvals.save(approval.assign(userIds, me.actor, now, command.comment))
        audit.record(
            AuditEntry(
                OPERATION, ApprovalService.ENTITY, approvalId.toString(), projectId = approval.projectId.value,
                details = mapOf(
                    "runId" to approval.runId.toString(), "nodeId" to approval.nodeId,
                    "old" to (before ?: approval.approvers.users).map { it.toString() },
                    "new" to assigned.assignees.map { it.toString() },
                    "overridden" to (before != null), "cleared" to userIds.isEmpty(),
                    "commentLength" to (command.comment?.length ?: 0),
                ),
            ),
        )
        events.publish(ApprovalAssigned(assigned.id, assigned.runId, assigned.projectId, assigned.assignment?.userIds ?: emptyList(), now))
        return assigned
    }

    override fun candidates(approvalId: ApprovalRequestId): List<AssigneeCandidate> {
        val approval = load(approvalId)
        gate.require(ProjectsPermissions.RUN_ASSIGN, approval.projectId.value)
        val projectId = approval.projectId.value
        return directory.members(projectId)
            .filter { it.userId != null && GateApprovalPolicy.canBeAssigned(approval, it).allowed }
            .map { AssigneeCandidate(it.userId!!.value, it.displayName, it.projectRoles[projectId] ?: emptySet(), it.userId!!.value in approval.assignees) }
    }

    private fun load(approvalId: ApprovalRequestId): ApprovalRequest =
        approvals.findById(approvalId) ?: throw NotFoundException("Approval", approvalId)

    companion object {
        const val OPERATION = "APPROVAL_ASSIGN"
    }
}
