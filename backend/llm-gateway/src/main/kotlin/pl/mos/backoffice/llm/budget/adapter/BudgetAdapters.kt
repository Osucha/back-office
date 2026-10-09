package pl.mos.backoffice.llm.budget.adapter

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.llm.budget.application.port.`in`.EnforceBudgets
import pl.mos.backoffice.llm.budget.application.port.out.BudgetStore
import pl.mos.backoffice.llm.budget.domain.BudgetConfig
import pl.mos.backoffice.llm.budget.domain.BudgetScope
import pl.mos.backoffice.llm.budget.domain.BudgetState
import pl.mos.backoffice.llm.budget.domain.JobSpend
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private fun Instant.ts(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/** JDBC store of V3__budgets.sql. */
class JdbcBudgetStore(private val db: NamedParameterJdbcTemplate) : BudgetStore {

    private fun config(rs: ResultSet) = BudgetConfig(
        scope = BudgetScope.valueOf(rs.getString("scope")),
        projectId = rs.getObject("project_id", UUID::class.java),
        monthlyLimitUsd = rs.getBigDecimal("monthly_limit_usd"),
        alertAtPercent = rs.getInt("alert_at_percent"),
        hardStop = rs.getBoolean("hard_stop"),
    )

    override fun findConfig(key: String): BudgetConfig? =
        db.query("SELECT * FROM budget_config WHERE scope_key = :key", mapOf("key" to key)) { rs, _ -> config(rs) }.firstOrNull()

    override fun findConfigs(): List<BudgetConfig> = db.query("SELECT * FROM budget_config ORDER BY scope, scope_key") { rs, _ -> config(rs) }

    override fun saveConfig(config: BudgetConfig, at: Instant, updatedBy: String?) {
        db.update(
            """
            INSERT INTO budget_config (scope_key, scope, project_id, monthly_limit_usd, alert_at_percent, hard_stop, updated_at, updated_by)
            VALUES (:key, :scope, :project, :limit, :alert, :hard, :at, :by)
            ON CONFLICT (scope_key) DO UPDATE SET monthly_limit_usd = EXCLUDED.monthly_limit_usd, alert_at_percent = EXCLUDED.alert_at_percent,
                hard_stop = EXCLUDED.hard_stop, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """.trimIndent(),
            MapSqlParameterSource().addValue("key", config.key).addValue("scope", config.scope.name).addValue("project", config.projectId)
                .addValue("limit", config.monthlyLimitUsd).addValue("alert", config.alertAtPercent).addValue("hard", config.hardStop)
                .addValue("at", at.ts()).addValue("by", updatedBy),
        )
    }

    override fun findState(key: String, period: String): BudgetState? =
        db.query("SELECT * FROM budget_state WHERE scope_key = :key AND period = :period", mapOf("key" to key, "period" to period)) { rs, _ ->
            BudgetState(key, period, rs.getBigDecimal("spent_usd"), rs.getBoolean("alert_sent"), rs.getBoolean("exceeded_sent"), rs.getLong("version"))
        }.firstOrNull()

    override fun ensureState(key: String, period: String, at: Instant) {
        db.update(
            "INSERT INTO budget_state (scope_key, period, updated_at) VALUES (:key, :period, :at) ON CONFLICT DO NOTHING",
            MapSqlParameterSource().addValue("key", key).addValue("period", period).addValue("at", at.ts()),
        )
    }

    override fun updateState(key: String, period: String, expectedVersion: Long, spentUsd: BigDecimal, alertSent: Boolean, exceededSent: Boolean, at: Instant): Boolean =
        db.update(
            """
            UPDATE budget_state SET spent_usd = :spent, alert_sent = :alert, exceeded_sent = :exceeded, version = version + 1, updated_at = :at
            WHERE scope_key = :key AND period = :period AND version = :version
            """.trimIndent(),
            MapSqlParameterSource().addValue("key", key).addValue("period", period).addValue("version", expectedVersion)
                .addValue("spent", spentUsd).addValue("alert", alertSent).addValue("exceeded", exceededSent).addValue("at", at.ts()),
        ) == 1

    override fun findJobSpend(jobId: UUID): JobSpend? =
        db.query("SELECT * FROM budget_job_spend WHERE job_id = :id", mapOf("id" to jobId)) { rs, _ ->
            JobSpend(jobId, rs.getBigDecimal("spent_usd"), rs.getLong("tokens"))
        }.firstOrNull()

    override fun addJobSpend(jobId: UUID, costUsd: BigDecimal, tokens: Long, at: Instant) {
        db.update(
            """
            INSERT INTO budget_job_spend (job_id, spent_usd, tokens, updated_at) VALUES (:id, :cost, :tokens, :at)
            ON CONFLICT (job_id) DO UPDATE SET spent_usd = budget_job_spend.spent_usd + EXCLUDED.spent_usd,
                tokens = budget_job_spend.tokens + EXCLUDED.tokens, version = budget_job_spend.version + 1, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", jobId).addValue("cost", costUsd).addValue("tokens", tokens).addValue("at", at.ts()),
        )
    }

    override fun incrementJobRequests(jobId: UUID, at: Instant): Long =
        db.queryForObject(
            """
            INSERT INTO budget_job_spend (job_id, requests, updated_at) VALUES (:id, 1, :at)
            ON CONFLICT (job_id) DO UPDATE SET requests = budget_job_spend.requests + 1, version = budget_job_spend.version + 1,
                updated_at = EXCLUDED.updated_at
            RETURNING requests
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", jobId).addValue("at", at.ts()),
            Long::class.java,
        ) ?: 0L

    override fun recordJobRefusal(jobId: UUID, reason: String, at: Instant) {
        db.update(
            """
            INSERT INTO budget_job_spend (job_id, updated_at, refused_reason, refused_at) VALUES (:id, :at, :reason, :at)
            ON CONFLICT (job_id) DO UPDATE SET refused_reason = EXCLUDED.refused_reason, refused_at = EXCLUDED.refused_at,
                version = budget_job_spend.version + 1, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", jobId).addValue("reason", reason).addValue("at", at.ts()),
        )
    }

    override fun findJobRefusal(jobId: UUID): String? =
        db.query("SELECT refused_reason FROM budget_job_spend WHERE job_id = :id", mapOf("id" to jobId)) { rs, _ -> rs.getString(1) }
            .firstOrNull()
}

/**
 * Decorates the ledger so that every booked cost (LLM proxy, workflow LLM steps, reviews, benchmark calls)
 * also moves the monthly budget state. Budget bookkeeping runs in its own transaction and never fails the
 * ledger write.
 */
class BudgetTrackingUsageLedger(
    private val delegate: UsageLedger,
    private val budgets: EnforceBudgets,
    private val gate: AuthorizationGate,
    private val tx: TransactionTemplate,
) : UsageLedger by delegate {

    private val log = LoggerFactory.getLogger(BudgetTrackingUsageLedger::class.java)

    override fun record(record: UsageRecord) {
        delegate.record(record)
        try {
            tx.executeWithoutResult { gate.asSystem("budget bookkeeping") { budgets.recordSpend(record.context, record.cost.amount) } }
        } catch (e: RuntimeException) {
            log.warn("budget state not updated for usage record {}: {}", record.id, e.toString())
        }
    }

    override fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary = delegate.summary(query, groupBy)
    override fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> = delegate.entries(query, page, size)
}
