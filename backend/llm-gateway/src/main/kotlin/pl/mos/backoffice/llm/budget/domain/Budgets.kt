package pl.mos.backoffice.llm.budget.domain

import pl.mos.backoffice.shared.DomainEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

enum class BudgetScope { PLATFORM, PROJECT }

/** Where a budget or a hard stop applies. `JOB` is the per-job cap carried by the job credential. */
enum class LimitScope { PLATFORM, PROJECT, JOB }

data class BudgetConfig(
    val scope: BudgetScope,
    val projectId: UUID?,
    /** null = no limit (spend is still tracked). */
    val monthlyLimitUsd: BigDecimal?,
    val alertAtPercent: Int = 80,
    val hardStop: Boolean = true,
) {
    val key: String get() = keyOf(scope, projectId)

    companion object {
        fun keyOf(scope: BudgetScope, projectId: UUID?): String = if (scope == BudgetScope.PLATFORM) "PLATFORM" else projectId.toString()
    }
}

data class BudgetState(val key: String, val period: String, val spentUsd: BigDecimal, val alertSent: Boolean, val exceededSent: Boolean, val version: Long)

data class JobSpend(val jobId: UUID, val spentUsd: BigDecimal, val tokens: Long)

enum class BudgetHealth { OK, ALERT, EXCEEDED, NO_LIMIT }

data class BudgetStatus(
    val config: BudgetConfig,
    val period: String,
    val spentUsd: BigDecimal,
    val forecastUsd: BigDecimal,
    val state: BudgetHealth,
)

object BudgetPeriods {
    fun of(at: Instant): String = YearMonth.from(at.atZone(ZoneOffset.UTC)).toString()

    /** Linear forecast of the month's spend from the elapsed share of the month. */
    fun forecast(spent: BigDecimal, at: Instant): BigDecimal {
        val month = YearMonth.from(at.atZone(ZoneOffset.UTC))
        val start = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val elapsed = (at.epochSecond - start.epochSecond).coerceAtLeast(3600)
        val total = end.epochSecond - start.epochSecond
        return spent.multiply(BigDecimal(total)).divide(BigDecimal(elapsed), 6, RoundingMode.HALF_UP)
    }

    fun health(config: BudgetConfig, spent: BigDecimal): BudgetHealth {
        val limit = config.monthlyLimitUsd ?: return BudgetHealth.NO_LIMIT
        return when {
            spent >= limit -> BudgetHealth.EXCEEDED
            spent * BigDecimal(100) >= limit * BigDecimal(config.alertAtPercent) -> BudgetHealth.ALERT
            else -> BudgetHealth.OK
        }
    }
}

/** A hard limit would be crossed by the call. Mapped to HTTP 402 `budget-exceeded`. */
class BudgetExceededException(
    val scope: LimitScope,
    val projectId: UUID?,
    val limit: String,
    val spent: String,
    message: String,
) : RuntimeException(message)

/** Spend crossed the alert threshold of a budget (once per scope and month). */
data class BudgetAlertEvent(
    val scope: BudgetScope,
    val projectId: UUID?,
    val period: String,
    val spentUsd: BigDecimal,
    val limitUsd: BigDecimal,
    val alertAtPercent: Int,
    override val occurredAt: Instant,
) : DomainEvent {
    override val type: String = "BudgetAlert"
    override val payload: Map<String, Any?> = mapOf(
        "scope" to scope.name, "projectId" to projectId?.toString(), "period" to period,
        "spentUsd" to spentUsd.toPlainString(), "limitUsd" to limitUsd.toPlainString(), "alertAtPercent" to alertAtPercent,
    )
}

/** Spend reached the limit of a budget (once per scope and month); with `hardStop` new LLM calls get 402. */
data class BudgetExceededEvent(
    val scope: BudgetScope,
    val projectId: UUID?,
    val period: String,
    val spentUsd: BigDecimal,
    val limitUsd: BigDecimal,
    val hardStop: Boolean,
    override val occurredAt: Instant,
) : DomainEvent {
    override val type: String = "BudgetExceeded"
    override val payload: Map<String, Any?> = mapOf(
        "scope" to scope.name, "projectId" to projectId?.toString(), "period" to period,
        "spentUsd" to spentUsd.toPlainString(), "limitUsd" to limitUsd.toPlainString(), "hardStop" to hardStop,
    )
}
