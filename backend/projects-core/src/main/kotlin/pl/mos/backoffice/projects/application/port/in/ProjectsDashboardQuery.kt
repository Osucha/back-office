package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import java.time.Instant

/** Read model for the landing page; the `app` module composes the dashboard from it. */
interface ProjectsDashboardQuery {
    fun openApprovals(): Int
    fun activeRuns(): Int
    fun waitingForCi(): Int
    fun failedRunsSince(since: Instant): Int
    fun recentRuns(limit: Int): List<WorkflowRun>
    fun recentCiExecutions(limit: Int): List<CiExecution>
    fun pendingApprovals(limit: Int): List<ApprovalRequest>
}
