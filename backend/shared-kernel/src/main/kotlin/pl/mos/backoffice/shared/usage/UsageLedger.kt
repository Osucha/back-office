package pl.mos.backoffice.shared.usage

import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Prices per million tokens, user-maintained on the model profile. */
data class Pricing(
    val inputPerMTok: BigDecimal,
    val outputPerMTok: BigDecimal,
    val cacheReadPerMTok: BigDecimal? = null,
    val cacheWritePerMTok: BigDecimal? = null,
    val currency: String = "USD",
) {
    fun costOf(usage: TokenUsage): Money {
        val million = BigDecimal(1_000_000)
        val input = BigDecimal(usage.inputTokens) * inputPerMTok
        val output = BigDecimal(usage.outputTokens) * outputPerMTok
        val cacheRead = BigDecimal(usage.cacheReadTokens) * (cacheReadPerMTok ?: inputPerMTok)
        val cacheWrite = BigDecimal(usage.cacheWriteTokens) * (cacheWritePerMTok ?: inputPerMTok)
        return Money((input + output + cacheRead + cacheWrite).divide(million), currency).rounded()
    }

    companion object {
        val FREE = Pricing(BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

enum class UsageKind { PIPELINE_STEP, CODE_REVIEW, BENCHMARK_ATTEMPT, MODEL_TEST }

/** Where a usage record belongs. Identifiers are plain UUIDs so the shared kernel stays context-agnostic. */
data class UsageContext(
    val kind: UsageKind,
    val projectId: UUID? = null,
    val runId: UUID? = null,
    val stepId: String? = null,
    val reviewId: UUID? = null,
    val benchmarkId: UUID? = null,
    val attemptId: UUID? = null,
)

data class UsageRecord(
    val id: UUID = UUID.randomUUID(),
    val occurredAt: Instant,
    val modelRef: ModelRef,
    val usage: TokenUsage,
    /** Booked cost: reported by the harness when available, otherwise `estimatedCost`. */
    val cost: Money,
    /** Cost computed from catalogue pricing, kept for comparison with reported cost. */
    val estimatedCost: Money,
    val durationMs: Long?,
    val context: UsageContext,
)

enum class UsageGroupBy { MODEL, PROJECT, BENCHMARK, DAY, CONTEXT_KIND }

data class UsageQuery(
    val from: Instant?,
    val to: Instant?,
    val projectId: UUID? = null,
    val runId: UUID? = null,
    val benchmarkId: UUID? = null,
)

data class UsageGroup(val key: String, val label: String, val cost: Money, val usage: TokenUsage, val calls: Long)

data class UsageSummary(
    val from: Instant?,
    val to: Instant?,
    val groupBy: UsageGroupBy,
    val total: Money,
    val totalUsage: TokenUsage,
    val groups: List<UsageGroup>,
)

/** Outbound port: the cost ledger. Implemented in `llm-gateway` (JPA). */
interface UsageLedger {
    fun record(record: UsageRecord)
    fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary
    fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord>
    fun totalCost(query: UsageQuery): Money
}

data class PageOf<T>(val content: List<T>, val page: Int, val size: Int, val totalElements: Long)
