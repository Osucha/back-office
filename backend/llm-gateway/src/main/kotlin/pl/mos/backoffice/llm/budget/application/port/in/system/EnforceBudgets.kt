package pl.mos.backoffice.llm.budget.application.port.`in`.system

import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.usage.UsageContext
import java.math.BigDecimal
import java.util.UUID

/** Enforcement at the LLM gateway (system operations; the caller is a sandbox or the platform itself). */
interface EnforceBudgets {
    /** Throws `BudgetExceededException` when [estimatedCostUsd] / [estimatedTokens] would cross a hard limit. */
    fun precheck(credential: JobCredential?, context: UsageContext, estimatedCostUsd: BigDecimal, estimatedTokens: Long)

    /** Adds booked spend to the platform and project month (alerts and exceed events when thresholds are crossed). */
    fun recordSpend(context: UsageContext, costUsd: BigDecimal)

    /** Adds spend of one agent job (per-job caps). */
    fun recordJobSpend(jobId: UUID, costUsd: BigDecimal, tokens: Long)

    /** Remembers that the gateway refused a call of this job (read back through `BudgetRefusals`). */
    fun recordRefusal(jobId: UUID, reason: String)

    /**
     * Counts one metered proxy call of the job and throws `BudgetExceededException` (scope JOB) when it would
     * exceed `JobCredential.maxRequests` (ADR 0029: a hijacked agent cannot loop forever on the gateway).
     */
    fun countJobRequest(credential: JobCredential, context: UsageContext)
}
