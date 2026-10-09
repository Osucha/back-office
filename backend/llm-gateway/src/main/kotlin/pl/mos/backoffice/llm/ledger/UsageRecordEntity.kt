package pl.mos.backoffice.llm.ledger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.usage.UsageRecord
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Row of the `usage_record` table (V1__usage_ledger.sql). */
@Entity
@Table(name = "usage_record")
class UsageRecordEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.EPOCH,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val provider: Provider = Provider.OPENAI_COMPATIBLE,
    @Column(name = "model_id", nullable = false, length = 200)
    val modelId: String = "",
    @Column(name = "model_label", nullable = false, length = 240)
    val modelLabel: String = "",
    @Column(name = "input_tokens", nullable = false)
    val inputTokens: Long = 0,
    @Column(name = "output_tokens", nullable = false)
    val outputTokens: Long = 0,
    @Column(name = "cache_read_tokens", nullable = false)
    val cacheReadTokens: Long = 0,
    @Column(name = "cache_write_tokens", nullable = false)
    val cacheWriteTokens: Long = 0,
    @Column(nullable = false, precision = 18, scale = 6)
    val cost: BigDecimal = BigDecimal.ZERO,
    @Column(name = "estimated_cost", nullable = false, precision = 18, scale = 6)
    val estimatedCost: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false, length = 3)
    val currency: String = "USD",
    @Column(name = "duration_ms")
    val durationMs: Long? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "context_kind", nullable = false, length = 32)
    val contextKind: UsageKind = UsageKind.MODEL_TEST,
    @Column(name = "project_id")
    val projectId: UUID? = null,
    @Column(name = "run_id")
    val runId: UUID? = null,
    @Column(name = "step_id", length = 200)
    val stepId: String? = null,
    @Column(name = "review_id")
    val reviewId: UUID? = null,
    @Column(name = "benchmark_id")
    val benchmarkId: UUID? = null,
    @Column(name = "attempt_id")
    val attemptId: UUID? = null,
) {
    fun toRecord(): UsageRecord = UsageRecord(
        id = id,
        occurredAt = occurredAt,
        modelRef = ModelRef(provider, modelId),
        usage = TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens),
        cost = Money(cost, currency),
        estimatedCost = Money(estimatedCost, currency),
        durationMs = durationMs,
        context = UsageContext(
            kind = contextKind,
            projectId = projectId,
            runId = runId,
            stepId = stepId,
            reviewId = reviewId,
            benchmarkId = benchmarkId,
            attemptId = attemptId,
        ),
    )

    companion object {
        fun from(record: UsageRecord): UsageRecordEntity = UsageRecordEntity(
            id = record.id,
            occurredAt = record.occurredAt,
            provider = record.modelRef.provider,
            modelId = record.modelRef.modelId,
            modelLabel = record.modelRef.label,
            inputTokens = record.usage.inputTokens,
            outputTokens = record.usage.outputTokens,
            cacheReadTokens = record.usage.cacheReadTokens,
            cacheWriteTokens = record.usage.cacheWriteTokens,
            cost = record.cost.rounded().amount,
            estimatedCost = record.estimatedCost.rounded().amount,
            currency = record.cost.currency,
            durationMs = record.durationMs,
            contextKind = record.context.kind,
            projectId = record.context.projectId,
            runId = record.context.runId,
            stepId = record.context.stepId,
            reviewId = record.context.reviewId,
            benchmarkId = record.context.benchmarkId,
            attemptId = record.context.attemptId,
        )
    }
}

interface UsageRecordJpaRepository : JpaRepository<UsageRecordEntity, UUID>, JpaSpecificationExecutor<UsageRecordEntity>
