package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ProjectsDashboardQuery
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.PublicOperation
import java.time.Instant

/**
 * Implements [ProjectsDashboardQuery] with indexed repository counts. Available to every authenticated
 * caller, but every count and list only covers the projects the caller holds `project.view` on.
 */
class DashboardQueryService(
    private val runs: WorkflowRunRepository,
    private val approvals: ApprovalRequestRepository,
    private val ciExecutions: CiExecutionRepository,
    private val gate: AuthorizationGate,
) : ProjectsDashboardQuery {

    @PublicOperation("landing page; filtered by project.view")
    override fun openApprovals(): Int = approvals.countOpen(visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun activeRuns(): Int = runs.countByStatus(setOf(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.WAITING), visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun waitingForCi(): Int = runs.countWaitingForCi(visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun failedRunsSince(since: Instant): Int = runs.countFinishedSince(RunStatus.FAILED, since, visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun recentRuns(limit: Int): List<WorkflowRun> = runs.findRecent(limit, visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun recentCiExecutions(limit: Int): List<CiExecution> = ciExecutions.findRecent(limit, visible())

    @PublicOperation("landing page; filtered by project.view")
    override fun pendingApprovals(limit: Int): List<ApprovalRequest> = approvals.findByStatus(ApprovalStatus.OPEN, visible(), limit)

    private fun visible(): ProjectFilter = gate.projectFilter(ProjectsPermissions.PROJECT_VIEW)
}
