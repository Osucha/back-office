package pl.mos.backoffice.llm.budget.adapter

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.llm.budget.application.port.`in`.BudgetInput
import pl.mos.backoffice.llm.budget.application.port.`in`.ManageBudgets
import pl.mos.backoffice.llm.budget.domain.BudgetStatus
import java.math.BigDecimal
import java.util.UUID

data class BudgetInputJson(val monthlyLimitUsd: BigDecimal? = null, val alertAtPercent: Int = 80, val hardStop: Boolean = true) {
    fun toInput() = BudgetInput(monthlyLimitUsd, alertAtPercent, hardStop)
}

/** openapi `BudgetStatus`; `projectKey` is not known to this module and stays null (the UI resolves it). */
data class BudgetStatusJson(
    val scope: String,
    val projectId: UUID?,
    val projectKey: String?,
    val period: String,
    val monthlyLimitUsd: BigDecimal?,
    val alertAtPercent: Int,
    val hardStop: Boolean,
    val spentUsd: BigDecimal,
    val forecastUsd: BigDecimal,
    val state: String,
) {
    companion object {
        fun of(s: BudgetStatus) = BudgetStatusJson(
            s.config.scope.name, s.config.projectId, null, s.period, s.config.monthlyLimitUsd, s.config.alertAtPercent, s.config.hardStop,
            s.spentUsd, s.forecastUsd, s.state.name,
        )
    }
}

/** `/api/v1/budgets`, `/api/v1/admin/budget`, `/api/v1/projects/{projectId}/budget` (openapi tag `budgets`). */
@RestController
@RequestMapping("/api/v1")
class BudgetController(private val budgets: ManageBudgets) {

    @GetMapping("/budgets")
    fun list(): List<BudgetStatusJson> = budgets.list().map(BudgetStatusJson::of)

    @PutMapping("/admin/budget")
    fun setPlatform(@RequestBody body: BudgetInputJson): BudgetStatusJson = BudgetStatusJson.of(budgets.setPlatform(body.toInput()))

    @GetMapping("/projects/{projectId}/budget")
    fun getProject(@PathVariable projectId: UUID): BudgetStatusJson = BudgetStatusJson.of(budgets.getProject(projectId))

    @PutMapping("/projects/{projectId}/budget")
    fun setProject(@PathVariable projectId: UUID, @RequestBody body: BudgetInputJson): BudgetStatusJson =
        BudgetStatusJson.of(budgets.setProject(projectId, body.toInput()))
}
