package pl.mos.backoffice.projects.adapter.`in`.rest

import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectAccessDirectory

/**
 * Builds [ApprovalRequestDto]s as the caller sees them: `canDecide` from the gate rule, `canAssign` from
 * `run.assign`, and the assignees with display names (one directory lookup per list). Used by the inbox and
 * the dashboard.
 */
@Component
class ApprovalViews(
    private val runs: RunQueries,
    private val gate: AuthorizationGate,
    private val directory: ProjectAccessDirectory,
    private val mapper: RestMapper,
) {
    fun toDto(approval: ApprovalRequest): ApprovalRequestDto = toDtos(listOf(approval)).single()

    fun toDtos(approvals: List<ApprovalRequest>): List<ApprovalRequestDto> {
        if (approvals.isEmpty()) return emptyList()
        val caller = gate.currentUser()
        val names = runCatching { directory.displayNames(approvals.flatMap { it.assignees }.toSet()) }.getOrDefault(emptyMap())
        return approvals.map { approval ->
            val artifacts = if (approval.context.artifactIds.isEmpty()) emptyList() else runCatching { runs.artifacts(approval.runId) }.getOrDefault(emptyList())
            mapper.toDto(
                approval, artifacts, GateApprovalPolicy.check(approval, caller), names,
                canAssign = caller.can(ProjectsPermissions.RUN_ASSIGN, approval.projectId.value),
            )
        }
    }
}
