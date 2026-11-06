package pl.mos.backoffice.governance.domain.service

import pl.mos.backoffice.governance.domain.model.AgentInsights
import pl.mos.backoffice.governance.domain.model.AgentMetrics
import pl.mos.backoffice.governance.domain.model.DistributionStat
import pl.mos.backoffice.governance.domain.model.TaskStepExecution
import pl.mos.backoffice.governance.domain.model.TemplateAgentMetrics
import pl.mos.backoffice.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * ADR 0059: pure aggregation of task step executions into agent insights (docs/architecture/11-governance.md). Only
 * finished executions count (SUCCEEDED or FAILED; skipped and open ones never ran an agent to the end). Rows are per
 * agent key and model profile; the name, harness and model label come from the latest execution of the row.
 *
 * - accepted: a succeeded execution that is the last finished execution of its node in its run, in a run that ended
 *   successfully;
 * - first-pass rate: accepted executions with attempt 1 / accepted executions;
 * - cost per accepted: the cost of every execution of the row / accepted executions (null without accepted ones).
 */
object AgentInsightsCalculator {

    private const val SUCCEEDED = "SUCCEEDED"
    private const val FAILED = "FAILED"

    fun insights(from: Instant, to: Instant, projectId: UUID?, executions: List<TaskStepExecution>, templateNames: Map<String, String> = emptyMap()): AgentInsights {
        val finished = executions.filter { it.status == SUCCEEDED || it.status == FAILED }
        val lastOfNode = finished.groupBy { it.runId to it.nodeId }.mapValues { (_, e) -> e.maxOf { it.index } }
        val attributed = finished.filter { it.agent != null }
        fun accepted(e: TaskStepExecution) = e.status == SUCCEEDED && e.runSuccessful && lastOfNode[e.runId to e.nodeId] == e.index
        return AgentInsights(
            from = from,
            to = to,
            projectId = projectId,
            byAgent = rows(attributed, ::accepted),
            byTemplate = attributed.filter { it.templateKey != null }.groupBy { it.templateKey!! }.toSortedMap()
                .map { (key, e) -> TemplateAgentMetrics(key, templateNames[key], rows(e, ::accepted)) },
            unattributed = finished.size - attributed.size,
        )
    }

    /** One row per agent and model, lowest cost per accepted step first (rows without accepted steps last). */
    fun rows(executions: List<TaskStepExecution>, accepted: (TaskStepExecution) -> Boolean): List<AgentMetrics> =
        executions.groupBy { it.agent!!.agentKey to it.agent.modelProfileId }
            .map { (_, e) -> row(e, accepted) }
            .sortedWith(
                compareBy<AgentMetrics, BigDecimal?>(nullsLast()) { it.costPerAccepted?.amount }
                    .thenByDescending { it.executions }
                    .thenBy { it.agentName }
                    .thenBy { it.modelLabel ?: "" },
            )

    private fun row(executions: List<TaskStepExecution>, isAccepted: (TaskStepExecution) -> Boolean): AgentMetrics {
        val agent = executions.last().agent!!
        val accepted = executions.filter(isAccepted)
        val cost = Money.sum(executions.map { it.cost }).rounded()
        return AgentMetrics(
            agentKey = agent.agentKey,
            agentName = agent.agentName,
            harness = agent.harness,
            modelProfileId = agent.modelProfileId,
            modelLabel = agent.modelLabel,
            executions = executions.size,
            succeeded = executions.count { it.status == SUCCEEDED },
            failed = executions.count { it.status == FAILED },
            accepted = accepted.size,
            firstPassRate = rate(accepted.count { it.attempt == 1 }, accepted.size),
            cost = cost,
            apiCost = Money.sum(executions.map { it.apiCost }).rounded(),
            costPerAccepted = if (accepted.isEmpty()) null else cost / accepted.size,
            duration = distribution(executions.mapNotNull { it.durationSeconds }),
        )
    }

    /** p50 / p90 (continuous, like SQL `percentile_cont`), total and count of [values], rounded to milliseconds. */
    fun distribution(values: List<Double>): DistributionStat {
        if (values.isEmpty()) return DistributionStat.EMPTY
        val sorted = values.sorted()
        return DistributionStat(round(percentile(sorted, 0.5)), round(percentile(sorted, 0.9)), round(sorted.sum()), sorted.size)
    }

    fun percentile(sorted: List<Double>, p: Double): Double {
        val position = p * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun rate(part: Int, whole: Int): Double? =
        if (whole == 0) null else BigDecimal(part).divide(BigDecimal(whole), 4, RoundingMode.HALF_UP).toDouble()

    private fun round(value: Double): Double = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).toDouble()
}
