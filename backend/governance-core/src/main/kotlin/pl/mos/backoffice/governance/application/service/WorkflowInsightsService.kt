package pl.mos.backoffice.governance.application.service

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.ScopeFilter
import pl.mos.backoffice.governance.application.port.`in`.WorkflowInsightsQuery
import pl.mos.backoffice.governance.application.port.out.WorkflowInsightsReadModel
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.governance.domain.model.WorkflowInsights
import pl.mos.backoffice.governance.domain.model.WorkflowMetrics
import pl.mos.backoffice.governance.domain.model.WorkflowScope
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Workflow metrics (ADR 0037). A library workflow needs `library.view` and counts only the runs of projects the caller
 * may view; a project workflow needs `project.view` on its project. Same window rules as delivery insights.
 */
class WorkflowInsightsService(
    private val readModel: WorkflowInsightsReadModel,
    private val guard: AccessGuard,
    private val clock: Clock,
) : WorkflowInsightsQuery {

    override fun get(libraryWorkflowId: UUID?, workflowId: UUID?, from: Instant?, to: Instant?): WorkflowInsights {
        if ((libraryWorkflowId == null) == (workflowId == null)) {
            throw ValidationException("exactly one of libraryWorkflowId and workflowId is required", "libraryWorkflowId")
        }
        val (scope, filter) = if (libraryWorkflowId != null) {
            guard.require(GovernancePermissions.LIBRARY_VIEW)
            WorkflowScope.Library(libraryWorkflowId) to guard.scopesWith(GovernancePermissions.PROJECT_VIEW)
        } else {
            val projectId = readModel.projectOf(workflowId!!) ?: throw NotFoundException("Workflow", workflowId)
            guard.require(GovernancePermissions.PROJECT_VIEW, projectId)
            WorkflowScope.Workflow(workflowId) to ScopeFilter.Only(setOf(projectId))
        }
        val (start, end) = DeliveryInsightsService.window(from, to, clock)
        return readModel.compute(scope, filter, start, end)
    }

    override fun libraryHeadlines(from: Instant?, to: Instant?): Map<UUID, WorkflowMetrics> {
        guard.require(GovernancePermissions.LIBRARY_VIEW)
        val (start, end) = DeliveryInsightsService.window(from, to, clock)
        return readModel.libraryHeadlines(guard.scopesWith(GovernancePermissions.PROJECT_VIEW), start, end)
    }
}
