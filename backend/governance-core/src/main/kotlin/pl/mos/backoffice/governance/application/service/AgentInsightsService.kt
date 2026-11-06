package pl.mos.backoffice.governance.application.service

import pl.mos.backoffice.governance.application.port.`in`.AgentInsightsQuery
import pl.mos.backoffice.governance.application.port.out.AgentInsightsReadModel
import pl.mos.backoffice.governance.domain.model.AgentInsights
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.governance.domain.service.AgentInsightsCalculator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.tasks.TaskCatalog
import java.time.Instant
import java.util.UUID

/**
 * Agent insights (ADR 0059): one project needs `project.view` on it; without a project the task steps of every project
 * the caller may view count. Same window rules as the delivery insights (default the last 30 days, at most 366 days).
 * Template names come from the library ([TaskCatalog] palette: global templates, plus the project's own with a project);
 * a template the library no longer offers keeps its key only.
 */
class AgentInsightsService(
    private val readModel: AgentInsightsReadModel,
    private val gate: AuthorizationGate,
    private val clock: Clock,
    private val tasks: TaskCatalog? = null,
) : AgentInsightsQuery {

    override fun get(projectId: UUID?, from: Instant?, to: Instant?): AgentInsights {
        val filter = if (projectId != null) {
            gate.require(GovernancePermissions.PROJECT_VIEW, projectId)
            ProjectFilter.Only(setOf(projectId))
        } else {
            gate.projectFilter(GovernancePermissions.PROJECT_VIEW)
        }
        val (start, end) = DeliveryInsightsService.window(from, to, clock)
        val steps = if (filter is ProjectFilter.Only && filter.projectIds.isEmpty()) emptyList() else readModel.taskSteps(filter, start, end)
        val names = if (steps.isEmpty()) emptyMap() else templateNames(projectId)
        return AgentInsightsCalculator.insights(start, end, projectId, steps, names)
    }

    private fun templateNames(projectId: UUID?): Map<String, String> =
        runCatching { tasks?.palette(projectId).orEmpty().associate { it.key to it.name } }.getOrDefault(emptyMap())
}
