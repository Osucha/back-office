package pl.mos.backoffice.llm.budget.application.service

import pl.mos.backoffice.llm.budget.application.port.`in`.BudgetInput
import pl.mos.backoffice.llm.budget.application.port.`in`.EnforceBudgets
import pl.mos.backoffice.llm.budget.application.port.`in`.ManageBudgets
import pl.mos.backoffice.llm.budget.application.port.out.BudgetStore
import pl.mos.backoffice.llm.budget.domain.BudgetAlertEvent
import pl.mos.backoffice.llm.budget.domain.BudgetConfig
import pl.mos.backoffice.llm.budget.domain.BudgetExceededEvent
import pl.mos.backoffice.llm.budget.domain.BudgetExceededException
import pl.mos.backoffice.llm.budget.domain.BudgetPeriods
import pl.mos.backoffice.llm.budget.domain.BudgetScope
import pl.mos.backoffice.llm.budget.domain.BudgetStatus
import pl.mos.backoffice.llm.budget.domain.LimitScope
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.usage.UsageContext
import java.math.BigDecimal
import java.util.UUID

/** Permission keys used by budgets (no new permissions, docs/architecture/11-governance.md). */
object BudgetPermissions {
    const val PLATFORM_MANAGE = "admin.settings.manage"
    const val USAGE_VIEW = "usage.view"
    const val PROJECT_MANAGE = "project.manage"
    const val PROJECT_USAGE_VIEW = "project.usage.view"
}

/**
 * Monthly budgets (platform and per project) with alert thresholds and hard stops, plus per-job caps from
 * the job credential (ADR 0025). Spend lives in `budget_state(scope, period, spent_usd, version)`; updates
 * use optimistic locking and retry, so concurrent LLM calls on several replicas never lose spend.
 */
class BudgetService(
    private val store: BudgetStore,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val events: EventPublisher,
    private val clock: Clock,
) : ManageBudgets, EnforceBudgets {

    // ---------------------------------------------------------------- management

    @PublicOperation("returns only the budgets the caller may see: platform with admin.settings.manage or usage.view, projects with project.usage.view")
    override fun list(): List<BudgetStatus> {
        val user = gate.currentUser()
        val filter = gate.projectFilter(BudgetPermissions.PROJECT_USAGE_VIEW)
        val configs = store.findConfigs()
        val platform = if (user.can(BudgetPermissions.PLATFORM_MANAGE) || user.can(BudgetPermissions.USAGE_VIEW) || user.allProjects) {
            listOf(status(configs.firstOrNull { it.scope == BudgetScope.PLATFORM } ?: BudgetConfig(BudgetScope.PLATFORM, null, null)))
        } else emptyList()
        val projects = configs.filter { it.scope == BudgetScope.PROJECT && it.projectId != null && filter.allows(it.projectId) }.map(::status)
        return platform + projects
    }

    override fun setPlatform(input: BudgetInput): BudgetStatus {
        val user = gate.require(BudgetPermissions.PLATFORM_MANAGE)
        return save(BudgetConfig(BudgetScope.PLATFORM, null, input.monthlyLimitUsd, input.alertAtPercent, input.hardStop), user.login)
    }

    override fun getProject(projectId: UUID): BudgetStatus {
        gate.require(BudgetPermissions.PROJECT_USAGE_VIEW, projectId)
        return status(projectConfig(projectId))
    }

    override fun setProject(projectId: UUID, input: BudgetInput): BudgetStatus {
        val user = gate.require(BudgetPermissions.PROJECT_MANAGE, projectId)
        return save(BudgetConfig(BudgetScope.PROJECT, projectId, input.monthlyLimitUsd, input.alertAtPercent, input.hardStop), user.login)
    }

    private fun save(config: BudgetConfig, by: String): BudgetStatus {
        if (config.monthlyLimitUsd != null && config.monthlyLimitUsd.signum() < 0) throw ValidationException("must not be negative", "monthlyLimitUsd")
        if (config.alertAtPercent !in 1..100) throw ValidationException("must be between 1 and 100", "alertAtPercent")
        val previous = store.findConfig(config.key)
        store.saveConfig(config, clock.now(), by)
        audit.record(
            AuditEntry(
                if (config.scope == BudgetScope.PLATFORM) "PLATFORM_BUDGET_UPDATE" else "PROJECT_BUDGET_UPDATE", "Budget", config.key, projectId = config.projectId,
                details = AuditableChanges.changed(
                    "monthlyLimitUsd" to (previous?.monthlyLimitUsd?.toPlainString() to config.monthlyLimitUsd?.toPlainString()),
                    "alertAtPercent" to (previous?.alertAtPercent to config.alertAtPercent),
                    "hardStop" to (previous?.hardStop to config.hardStop),
                ),
            ),
        )
        return status(config)
    }

    private fun projectConfig(projectId: UUID) =
        store.findConfig(BudgetConfig.keyOf(BudgetScope.PROJECT, projectId)) ?: BudgetConfig(BudgetScope.PROJECT, projectId, null)

    private fun status(config: BudgetConfig): BudgetStatus {
        val now = clock.now()
        val period = BudgetPeriods.of(now)
        val spent = store.findState(config.key, period)?.spentUsd ?: BigDecimal.ZERO
        return BudgetStatus(config, period, spent, BudgetPeriods.forecast(spent, now), BudgetPeriods.health(config, spent))
    }

    // ---------------------------------------------------------------- enforcement

    @SystemOperation("budget pre-check before an LLM provider call")
    override fun precheck(credential: JobCredential?, context: UsageContext, estimatedCostUsd: BigDecimal, estimatedTokens: Long) {
        if (credential != null) precheckJob(credential, context, estimatedCostUsd, estimatedTokens)
        precheckMonthly(context, estimatedCostUsd)
    }

    /** Per-job cost and token caps of the job's LLM credential. */
    private fun precheckJob(credential: JobCredential, context: UsageContext, estimatedCostUsd: BigDecimal, estimatedTokens: Long) {
        val spend = store.findJobSpend(credential.jobId)
        val spent = spend?.spentUsd ?: BigDecimal.ZERO
        val tokens = spend?.tokens ?: 0L
        credential.maxCostUsd?.let { cap ->
            if (spent >= cap || spent + estimatedCostUsd > cap) {
                throw BudgetExceededException(LimitScope.JOB, context.projectId, cap.toPlainString(), spent.toPlainString(), "job budget of ${cap.toPlainString()} USD would be exceeded (spent ${spent.toPlainString()})")
            }
        }
        credential.maxTokens?.let { cap ->
            if (tokens >= cap || tokens + estimatedTokens > cap) {
                throw BudgetExceededException(LimitScope.JOB, context.projectId, cap.toString(), tokens.toString(), "job token cap of $cap would be exceeded (used $tokens)")
            }
        }
    }

    /** Hard-stop monthly limits of the platform and the project. */
    private fun precheckMonthly(context: UsageContext, estimatedCostUsd: BigDecimal) {
        val period = BudgetPeriods.of(clock.now())
        listOfNotNull(
            store.findConfig(BudgetConfig.keyOf(BudgetScope.PLATFORM, null)),
            context.projectId?.let { store.findConfig(BudgetConfig.keyOf(BudgetScope.PROJECT, it)) },
        ).filter { it.hardStop && it.monthlyLimitUsd != null }.forEach { config ->
            val limit = config.monthlyLimitUsd!!
            val spent = store.findState(config.key, period)?.spentUsd ?: BigDecimal.ZERO
            if (spent >= limit || spent + estimatedCostUsd > limit) {
                val scope = if (config.scope == BudgetScope.PLATFORM) LimitScope.PLATFORM else LimitScope.PROJECT
                throw BudgetExceededException(
                    scope, config.projectId, limit.toPlainString(), spent.toPlainString(),
                    "${scope.name.lowercase()} budget of ${limit.toPlainString()} USD for $period would be exceeded (spent ${spent.toPlainString()}); raise the limit or stop",
                )
            }
        }
    }

    @SystemOperation("books spend of a ledger record into the monthly budget state")
    override fun recordSpend(context: UsageContext, costUsd: BigDecimal) {
        if (costUsd.signum() <= 0) return
        add(BudgetConfig.keyOf(BudgetScope.PLATFORM, null), BudgetScope.PLATFORM, null, costUsd)
        context.projectId?.let { add(BudgetConfig.keyOf(BudgetScope.PROJECT, it), BudgetScope.PROJECT, it, costUsd) }
    }

    @SystemOperation("books spend of one agent job")
    override fun recordJobSpend(jobId: UUID, costUsd: BigDecimal, tokens: Long) {
        if (costUsd.signum() <= 0 && tokens <= 0) return
        store.addJobSpend(jobId, costUsd.max(BigDecimal.ZERO), tokens.coerceAtLeast(0), clock.now())
    }

    @SystemOperation("per-job request cap of the LLM proxy")
    override fun countJobRequest(credential: JobCredential, context: UsageContext) {
        val cap = credential.maxRequests ?: return
        val count = store.incrementJobRequests(credential.jobId, clock.now())
        if (count > cap) {
            throw BudgetExceededException(
                LimitScope.JOB, context.projectId, cap.toString(), (count - 1).toString(),
                "job request cap of $cap LLM calls reached; the agent is stopped (raise backoffice.runner.max-llm-requests-per-job if the job needs more)",
            )
        }
    }

    @SystemOperation("remembers a hard budget stop of one agent job")
    override fun recordRefusal(jobId: UUID, reason: String) {
        store.recordJobRefusal(jobId, reason.take(MAX_REASON), clock.now())
    }

    private fun add(key: String, scope: BudgetScope, projectId: UUID?, cost: BigDecimal) {
        val now = clock.now()
        val period = BudgetPeriods.of(now)
        store.ensureState(key, period, now)
        repeat(MAX_RETRIES) {
            val state = store.findState(key, period) ?: return@repeat
            val spent = state.spentUsd + cost
            val config = store.findConfig(key)
            val limit = config?.monthlyLimitUsd
            val alert = limit != null && !state.alertSent && spent * BigDecimal(100) >= limit * BigDecimal(config.alertAtPercent)
            val exceeded = limit != null && !state.exceededSent && spent >= limit
            if (store.updateState(key, period, state.version, spent, state.alertSent || alert, state.exceededSent || exceeded, now)) {
                if (alert) events.publish(BudgetAlertEvent(scope, projectId, period, spent, limit!!, config.alertAtPercent, now))
                if (exceeded) events.publish(BudgetExceededEvent(scope, projectId, period, spent, limit!!, config.hardStop, now))
                return
            }
        }
        throw IllegalStateException("budget state $key/$period: too much contention, spend of $cost not booked")
    }

    companion object {
        const val MAX_RETRIES = 20
        const val MAX_REASON = 500
    }
}
