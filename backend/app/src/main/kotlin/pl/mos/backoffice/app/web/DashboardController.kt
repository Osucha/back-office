package pl.mos.backoffice.app.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.evaluation.application.port.`in`.EvaluationDashboardQuery
import pl.mos.backoffice.projects.adapter.`in`.rest.ApprovalRequestDto
import pl.mos.backoffice.projects.adapter.`in`.rest.ApprovalViews
import pl.mos.backoffice.projects.adapter.`in`.rest.CiExecutionSummaryDto
import pl.mos.backoffice.projects.adapter.`in`.rest.MoneyDto
import pl.mos.backoffice.projects.adapter.`in`.rest.RestMapper
import pl.mos.backoffice.projects.adapter.`in`.rest.WorkflowRunSummaryDto
import pl.mos.backoffice.projects.application.port.`in`.ProjectsDashboardQuery
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Landing-page read model composed from both bounded contexts plus the usage ledger. Lives in `app`
 * because it is the only place allowed to see Projects and Evaluation at the same time (ADR 0001).
 * Shape: `Dashboard` in docs/api/openapi.yaml. The Projects counts and lists are filtered to the projects
 * the caller holds `project.view` on (the query use cases do that); pending approvals carry `canDecide`, `canAssign` and the assignees.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val projects: ProjectsDashboardQuery,
    private val evaluation: EvaluationDashboardQuery,
    private val approvals: ApprovalViews,
    private val usage: UsageLedger,
    private val mapper: RestMapper,
    private val gate: AuthorizationGate,
    private val clock: Clock,
) {

    data class CostByDayDto(val day: LocalDate, val cost: MoneyDto)

    data class DashboardDto(
        val openApprovals: Int,
        val activeRuns: Int,
        val waitingForCi: Int,
        val runningBenchmarks: Int,
        val failedRunsLast7Days: Int,
        val costThisMonth: MoneyDto,
        val costLast30DaysByDay: List<CostByDayDto>,
        val recentRuns: List<WorkflowRunSummaryDto>,
        val recentCiExecutions: List<CiExecutionSummaryDto>,
        val pendingApprovals: List<ApprovalRequestDto>,
    )

    @GetMapping
    fun dashboard(): DashboardDto {
        val now = clock.now()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val thirtyDaysAgo = today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant()

        val caller = gate.currentUser()
        // the ledger aggregates all projects; platform-wide cost is only shown to holders of usage.view
        val seesCost = caller.can(USAGE_VIEW)
        val byDay = if (!seesCost) emptyMap() else usage.summary(UsageQuery(from = thirtyDaysAgo, to = now), UsageGroupBy.DAY).groups
            .associate { it.key to it.cost }
        val days = (0..29).map { today.minusDays(29L - it) }
            .map { day -> CostByDayDto(day, mapper.money(byDay[day.toString()] ?: Money.ZERO)) }

        return DashboardDto(
            openApprovals = projects.openApprovals(),
            activeRuns = projects.activeRuns(),
            waitingForCi = projects.waitingForCi(),
            runningBenchmarks = evaluation.runningBenchmarks(),
            failedRunsLast7Days = projects.failedRunsSince(now.minus(Duration.ofDays(7))),
            costThisMonth = mapper.money(if (seesCost) usage.totalCost(UsageQuery(from = monthStart, to = now)) else Money.ZERO),
            costLast30DaysByDay = days,
            recentRuns = projects.recentRuns(10).map(mapper::toSummary),
            recentCiExecutions = projects.recentCiExecutions(10).map(mapper::toSummary),
            pendingApprovals = approvals.toDtos(projects.pendingApprovals(10)),
        )
    }

    companion object {
        /** Platform permission of the llm module (docs/architecture/08-identity-and-access.md). */
        const val USAGE_VIEW = "usage.view"
    }
}
