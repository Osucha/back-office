package pl.mos.backoffice.governance.application.service

import pl.mos.backoffice.governance.application.port.`in`.DeliveryInsightsQuery
import pl.mos.backoffice.governance.application.port.out.DeliveryInsightsReadModel
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectFilter
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Delivery insights (ADR 0025): one project needs `project.view` on it; without a project the metrics cover
 * every project the caller may view. The window defaults to the last 30 days and is at most 366 days.
 */
class DeliveryInsightsService(
    private val readModel: DeliveryInsightsReadModel,
    private val gate: AuthorizationGate,
    private val clock: Clock,
) : DeliveryInsightsQuery {

    override fun get(projectId: UUID?, from: Instant?, to: Instant?): DeliveryInsights {
        val filter = if (projectId != null) {
            gate.require(GovernancePermissions.PROJECT_VIEW, projectId)
            ProjectFilter.Only(setOf(projectId))
        } else {
            gate.projectFilter(GovernancePermissions.PROJECT_VIEW)
        }
        val end = to ?: clock.now()
        val start = from ?: end.minus(DEFAULT_WINDOW)
        if (!start.isBefore(end)) throw ValidationException("from must be before to", "from")
        if (Duration.between(start, end) > MAX_WINDOW) throw ValidationException("the window may span at most ${MAX_WINDOW.toDays()} days", "from")
        return readModel.compute(filter, start, end).copy(projectId = projectId)
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
        val MAX_WINDOW: Duration = Duration.ofDays(366)
    }
}
