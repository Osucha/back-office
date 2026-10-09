package pl.mos.backoffice.llm.budget.application.port.out

import pl.mos.backoffice.llm.budget.domain.BudgetConfig
import pl.mos.backoffice.llm.budget.domain.BudgetState
import pl.mos.backoffice.llm.budget.domain.JobSpend
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface BudgetStore {
    fun findConfig(key: String): BudgetConfig?
    fun findConfigs(): List<BudgetConfig>
    fun saveConfig(config: BudgetConfig, at: Instant, updatedBy: String?)

    fun findState(key: String, period: String): BudgetState?

    /** Creates an empty state row when missing (no-op otherwise). */
    fun ensureState(key: String, period: String, at: Instant)

    /**
     * Optimistic update: sets the new spend and flags when the row still has [expectedVersion]; false when
     * another writer won (the caller re-reads and retries).
     */
    fun updateState(key: String, period: String, expectedVersion: Long, spentUsd: BigDecimal, alertSent: Boolean, exceededSent: Boolean, at: Instant): Boolean

    fun findJobSpend(jobId: UUID): JobSpend?

    /** Atomic increment of a job's spend. */
    fun addJobSpend(jobId: UUID, costUsd: BigDecimal, tokens: Long, at: Instant)

    /** Atomically adds one request to the job's counter and returns the new count. */
    fun incrementJobRequests(jobId: UUID, at: Instant): Long

    /** Remembers the latest hard stop applied to a job (creates the spend row when missing). */
    fun recordJobRefusal(jobId: UUID, reason: String, at: Instant)

    fun findJobRefusal(jobId: UUID): String?
}
