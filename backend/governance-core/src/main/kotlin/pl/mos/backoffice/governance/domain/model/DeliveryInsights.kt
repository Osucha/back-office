package pl.mos.backoffice.governance.domain.model

import pl.mos.backoffice.shared.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** p50/p90/total over a set of durations (seconds); null values when [count] is 0. */
data class DistributionStat(val p50Seconds: Double?, val p90Seconds: Double?, val totalSeconds: Double?, val count: Int) {
    companion object {
        val EMPTY = DistributionStat(null, null, null, 0)
    }
}

data class RunCounts(val total: Int, val succeeded: Int, val failed: Int, val cancelled: Int)

data class AgentSuccess(val key: String, val jobs: Int, val succeeded: Int, val cost: Money) {
    val rate: Double get() = if (jobs == 0) 0.0 else succeeded.toDouble() / jobs
}

data class GateDecisionCount(val option: String, val count: Int)

data class WeeklyThroughput(val weekStart: LocalDate, val succeeded: Int, val failed: Int, val humanWaitHours: Double, val cost: Money)

data class Bottleneck(val nodeId: String, val nodeName: String, val workflowName: String, val p90WaitSeconds: Double)

data class ModelMix(val model: String, val calls: Long, val inputTokens: Long, val outputTokens: Long, val cost: Money)

/**
 * Delivery insights over the runs **created** in `[from, to)` of the visible projects (definitions in
 * docs/architecture/11-governance.md, section "Delivery insights").
 */
data class DeliveryInsights(
    val from: Instant,
    val to: Instant,
    val projectId: UUID?,
    val leadTime: DistributionStat,
    val humanWait: DistributionStat,
    val ciWait: DistributionStat,
    val runs: RunCounts,
    val reworkLoopsPerRun: Double?,
    val costPerSuccessfulRun: Money,
    val costPerMergeRequest: Money,
    val totalCost: Money,
    val agentSuccessByTemplate: List<AgentSuccess>,
    val agentSuccessByModel: List<AgentSuccess>,
    val gateDecisions: List<GateDecisionCount>,
    val weekly: List<WeeklyThroughput>,
    val bottleneck: Bottleneck?,
    val modelMix: List<ModelMix>,
)
