package pl.mos.backoffice.llm.web

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.llm.usage.UsageQueryService
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** `/api/v1/usage/summary` and `/api/v1/usage/entries` (openapi tag `usage`): cost summaries grouped by model, project, benchmark or day, and the raw ledger. */
@RestController
@RequestMapping("/api/v1/usage")
class UsageController(private val usage: UsageQueryService) {

    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam groupBy: UsageGroupBy,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(required = false) benchmarkId: UUID?,
    ): UsageSummaryDto {
        val summary = usage.summary(UsageQuery(from, to, projectId, runId, benchmarkId), groupBy)
        return UsageSummaryDto(
            from = summary.from,
            to = summary.to,
            groupBy = summary.groupBy.name,
            total = MoneyDto.of(summary.total),
            totalUsage = TokenUsageDto.of(summary.totalUsage),
            groups = summary.groups.map { UsageGroupDto(it.key, it.label, MoneyDto.of(it.cost), TokenUsageDto.of(it.usage), it.calls) },
        )
    }

    @GetMapping("/entries")
    fun entries(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(required = false) benchmarkId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): UsageEntryPageDto {
        val result = usage.entries(UsageQuery(from, to, projectId, runId, benchmarkId), page, size)
        return UsageEntryPageDto(result.content.map { UsageEntryDto.of(it) }, result.page, result.size, result.totalElements)
    }
}

data class MoneyDto(val amount: BigDecimal, val currency: String) {
    companion object {
        fun of(money: Money) = MoneyDto(money.rounded().amount, money.currency)
    }
}

data class TokenUsageDto(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long) {
    companion object {
        fun of(usage: TokenUsage) = TokenUsageDto(usage.inputTokens, usage.outputTokens, usage.cacheReadTokens, usage.cacheWriteTokens)
    }
}

data class ModelOptionsDto(val temperature: Double?, val maxOutputTokens: Int?, val reasoning: Reasoning, val extra: Map<String, Any?>) {
    companion object {
        fun of(o: ModelOptions) = ModelOptionsDto(o.temperature, o.maxOutputTokens, o.reasoning, o.extra)
    }
}

data class ModelRefDto(val provider: Provider, val modelId: String, val options: ModelOptionsDto) {
    companion object {
        fun of(ref: ModelRef) = ModelRefDto(ref.provider, ref.modelId, ModelOptionsDto.of(ref.options))
    }
}

data class UsageGroupDto(val key: String, val label: String, val cost: MoneyDto, val usage: TokenUsageDto, val calls: Long)

data class UsageSummaryDto(
    val from: Instant?,
    val to: Instant?,
    val groupBy: String,
    val total: MoneyDto,
    val totalUsage: TokenUsageDto,
    val groups: List<UsageGroupDto>,
)

data class UsageContextDto(
    val kind: UsageKind,
    val projectId: UUID?,
    val runId: UUID?,
    val stepId: String?,
    val benchmarkId: UUID?,
    val attemptId: UUID?,
    val reviewId: UUID?,
)

data class UsageEntryDto(
    val id: UUID,
    val occurredAt: Instant,
    val modelRef: ModelRefDto,
    val usage: TokenUsageDto,
    val cost: MoneyDto,
    val estimatedCost: MoneyDto,
    val durationMs: Long?,
    val context: UsageContextDto,
) {
    companion object {
        fun of(r: UsageRecord) = UsageEntryDto(
            id = r.id,
            occurredAt = r.occurredAt,
            modelRef = ModelRefDto.of(r.modelRef),
            usage = TokenUsageDto.of(r.usage),
            cost = MoneyDto.of(r.cost),
            estimatedCost = MoneyDto.of(r.estimatedCost),
            durationMs = r.durationMs,
            context = UsageContextDto(
                kind = r.context.kind,
                projectId = r.context.projectId,
                runId = r.context.runId,
                stepId = r.context.stepId,
                benchmarkId = r.context.benchmarkId,
                attemptId = r.context.attemptId,
                reviewId = r.context.reviewId,
            ),
        )
    }
}

data class UsageEntryPageDto(val content: List<UsageEntryDto>, val page: Int, val size: Int, val totalElements: Long)
