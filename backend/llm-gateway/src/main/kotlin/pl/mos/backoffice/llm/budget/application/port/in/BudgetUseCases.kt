package pl.mos.backoffice.llm.budget.application.port.`in`

import pl.mos.backoffice.llm.budget.domain.BudgetStatus
import java.math.BigDecimal
import java.util.UUID

data class BudgetInput(val monthlyLimitUsd: BigDecimal?, val alertAtPercent: Int = 80, val hardStop: Boolean = true)

/** `/budgets`, `/admin/budget`, `/projects/{projectId}/budget`. */
interface ManageBudgets {
    /** Platform budget (for `admin.settings.manage` / `usage.view`) and the budgets of projects the caller may see usage of. */
    fun list(): List<BudgetStatus>
    fun setPlatform(input: BudgetInput): BudgetStatus
    fun getProject(projectId: UUID): BudgetStatus
    fun setProject(projectId: UUID, input: BudgetInput): BudgetStatus
}
