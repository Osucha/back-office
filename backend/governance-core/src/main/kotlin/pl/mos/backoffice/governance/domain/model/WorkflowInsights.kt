package pl.mos.backoffice.governance.domain.model

import pl.mos.backoffice.shared.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the workflow metrics are about (ADR 0037): a library workflow across projects, or one project workflow. */
sealed interface WorkflowScope {
    data class Library(val libraryWorkflowId: UUID) : WorkflowScope
    data class Workflow(val workflowId: UUID) : WorkflowScope
}

/**
 * Runs by result. `failed` counts runs that ended `FAILED` and runs that reached a `FAILURE` end; `modified` counts
 * runs of linked copies that differed from their library version.
 */
data class WorkflowRunCounts(val total: Int, val successful: Int, val failed: Int, val cancelled: Int, val active: Int, val modified: Int) {
    val finished: Int get() = successful + failed + cancelled

    companion object {
        val EMPTY = WorkflowRunCounts(0, 0, 0, 0, 0, 0)
    }
}

/**
 * Effectiveness (success and first-pass rates, rework) and efficiency (durations, cost, tokens) of a set of runs
 * (definitions in docs/architecture/11-governance.md, section "Workflow metrics").
 */
data class WorkflowMetrics(
    val runs: WorkflowRunCounts,
    val successRate: Double?,
    val firstPassRate: Double?,
    val reworkLoopsPerRun: Double?,
    val duration: DistributionStat,
    val activeTime: DistributionStat,
    val humanWait: DistributionStat,
    val totalCost: Money,
    val costPerRun: Money,
    val costPerSuccessfulRun: Money,
    val inputTokens: Long,
    val outputTokens: Long,
    val mergeRequests: Int,
    /**
     * ADR 0042: the same runs at API list prices: the spent cost plus the estimate of steps a subscription paid for
     * (Claude Code with its owner's login). Equal to the cost when everything went through the gateway.
     */
    val totalApiCost: Money = totalCost,
    val apiCostPerRun: Money = costPerRun,
    val apiCostPerSuccessfulRun: Money = costPerSuccessfulRun,
) {
    companion object {
        val EMPTY = WorkflowMetrics(
            WorkflowRunCounts.EMPTY, null, null, null, DistributionStat.EMPTY, DistributionStat.EMPTY, DistributionStat.EMPTY,
            Money.ZERO, Money.ZERO, Money.ZERO, 0, 0, 0,
        )
    }
}

data class VersionMetrics(val version: Int, val metrics: WorkflowMetrics)

data class ProjectWorkflowMetrics(val projectId: UUID, val projectKey: String, val metrics: WorkflowMetrics)

data class PortCount(val port: String, val count: Int)

/** One node of the workflow over all runs in scope: its executions (attempts), failures, durations, cost and ports taken. */
data class NodeMetrics(
    val nodeId: String,
    val nodeName: String,
    val kind: String,
    val templateKey: String?,
    val executions: Int,
    val succeeded: Int,
    val failed: Int,
    val duration: DistributionStat,
    val cost: Money,
    val ports: List<PortCount>,
    /** ADR 0042: the node's cost at API list prices. */
    val apiCost: Money = cost,
) {
    val failureRate: Double? get() = if (succeeded + failed == 0) null else failed.toDouble() / (succeeded + failed)
}

data class WeeklyOutcome(val weekStart: LocalDate, val successful: Int, val failed: Int, val cost: Money)

data class WorkflowInsights(
    val from: Instant,
    val to: Instant,
    val libraryWorkflowId: UUID?,
    val workflowId: UUID?,
    val totals: WorkflowMetrics,
    /** Library versions (library scope) or workflow versions (workflow scope), newest first. */
    val byVersion: List<VersionMetrics>,
    /** Library scope only. */
    val byProject: List<ProjectWorkflowMetrics>,
    val byNode: List<NodeMetrics>,
    val weekly: List<WeeklyOutcome>,
)
