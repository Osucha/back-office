package pl.mos.backoffice.governance.application.service

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.ScopeFilter
import pl.mos.backoffice.governance.application.port.`in`.DeliveryInsightsQuery
import pl.mos.backoffice.governance.application.port.out.DeliveryInsightsReadModel
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Delivery insights (ADR 0025): one project needs `project.view` on it; without a project the metrics cover
 * every project the caller may view. The window defaults to the last 30 days and is at most 366 days.
 */
class DeliveryInsightsService(
    private val readModel: DeliveryInsightsReadModel,
    private val guard: AccessGuard,
    private val clock: Clock,
) : DeliveryInsightsQuery {

    override fun get(projectId: UUID?, from: Instant?, to: Instant?): DeliveryInsights {
        val filter = if (projectId != null) {
            guard.require(GovernancePermissions.PROJECT_VIEW, projectId)
            ScopeFilter.Only(setOf(projectId))
        } else {
            guard.scopesWith(GovernancePermissions.PROJECT_VIEW)
        }
        val (start, end) = window(from, to, clock)
        return readModel.compute(filter, start, end).copy(projectId = projectId)
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
        val MAX_WINDOW: Duration = Duration.ofDays(366)

        /** `[from, to)` with the defaults applied (to = now, from = to - 30 days), at most [MAX_WINDOW] long. */
        fun window(from: Instant?, to: Instant?, clock: Clock): Pair<Instant, Instant> {
            val end = to ?: clock.now()
            val start = from ?: end.minus(DEFAULT_WINDOW)
            if (!start.isBefore(end)) throw ValidationException("from must be before to", "from")
            if (Duration.between(start, end) > MAX_WINDOW) throw ValidationException("the window may span at most ${MAX_WINDOW.toDays()} days", "from")
            return start to end
        }
    }
}
