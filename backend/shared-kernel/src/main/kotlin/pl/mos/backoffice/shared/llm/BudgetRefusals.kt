package pl.mos.backoffice.shared.llm

import java.util.UUID

/**
 * Hard budget stops the LLM gateway applied to agent jobs (ADR 0025). The execution plane reads them when a
 * job ends, so a sandbox that failed because the gateway answered 402 is reported as `budget-exceeded`
 * rather than as a generic agent failure.
 */
fun interface BudgetRefusals {
    /** The message of the latest refusal for [jobId], or null when the gateway never refused it. */
    fun refusalOf(jobId: UUID): String?

    companion object {
        const val REASON_PREFIX = "budget-exceeded"
        val NONE = BudgetRefusals { null }
    }
}
