package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.security.CurrentUser

/**
 * Gate approver rules (docs/architecture/08-identity-and-access.md, "Gate approval rules"). A caller may
 * decide an open approval when all of these hold:
 *
 * 1. they hold `run.approve` on the approval's project;
 * 2. `approvers.projectRoles` is empty, or they hold one of those roles on the project, or they have
 *    platform-wide project access (`admin.projects.all`);
 * 3. the approval's assignees are empty, or they are one of them; the assignees are the run-time assignment
 *    of the approval (ADR 0028) when there is one, else the gate's `approvers.users`;
 * 4. not (`approvers.forbidSelfApproval` and they started the run).
 *
 * [canBeAssigned] is the same check without rule 3: who may be named as an assignee.
 */
object GateApprovalPolicy {

    data class Check(val allowed: Boolean, val reason: String? = null, val missingPermission: Boolean = false) {
        companion object {
            val ALLOWED = Check(true)
        }
    }

    fun check(approval: ApprovalRequest, user: CurrentUser): Check {
        val base = canBeAssigned(approval, user)
        if (!base.allowed) return base
        val assignees = approval.assignees
        if (assignees.isNotEmpty() && user.userId?.value !in assignees) {
            val reason = if (approval.assignment != null) "this gate is assigned to other users" else "only the named approvers may decide this gate"
            return Check(false, reason)
        }
        return Check.ALLOWED
    }

    /** Rules 1, 2 and 4: [user] could decide this approval if it were assigned to them. */
    fun canBeAssigned(approval: ApprovalRequest, user: CurrentUser): Check {
        val projectId = approval.projectId.value
        if (!user.can(ProjectsPermissions.RUN_APPROVE, projectId)) {
            return Check(false, "missing permission ${ProjectsPermissions.RUN_APPROVE} on project ${approval.projectKey}", missingPermission = true)
        }
        if (approval.status != ApprovalStatus.OPEN) return Check(false, "approval is ${approval.status}")
        val rule = approval.approvers
        if (rule.projectRoles.isNotEmpty() && !user.allProjects && !user.hasProjectRole(projectId, rule.projectRoles)) {
            return Check(false, "only project roles ${rule.projectRoles.joinToString()} may decide this gate")
        }
        val starter = approval.startedBy?.userId
        if (rule.forbidSelfApproval && starter != null && starter == user.userId?.value) {
            return Check(false, "the user who started the run cannot decide its gate (four-eyes rule)")
        }
        return Check.ALLOWED
    }
}
