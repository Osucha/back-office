package pl.mos.backoffice.governance.domain.model

import pl.mos.backoffice.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * ADR 0059: one execution of a TASK step as the agent insights read it from a run: its place in the run ([index] orders
 * the executions of the run), the agent record (null before ADR 0059), cost and duration, and whether the run ended
 * successfully (status SUCCEEDED with outcome SUCCESS, as `WorkflowMetrics.successful`).
 */
data class TaskStepExecution(
    val runId: UUID,
    val runSuccessful: Boolean,
    val nodeId: String,
    val index: Int,
    val templateKey: String?,
    val attempt: Int,
    /** `NodeExecutionStatus` name. */
    val status: String,
    val agent: StepAgent?,
    val cost: Money,
    val apiCost: Money,
    val durationSeconds: Double?,
)

/** ADR 0059: the agent and model of a task step; [harness] null = model answer. */
data class StepAgent(
    val agentKey: String,
    val agentName: String,
    val harness: String? = null,
    val modelProfileId: UUID? = null,
    val modelLabel: String? = null,
)

/**
 * ADR 0059: task steps done by one agent with one model. Accepted = a succeeded execution that is the last one of its
 * node in a run that ended successfully; [costPerAccepted] = [cost] of every execution / [accepted] (rework and failed
 * attempts are paid by the accepted ones), null without accepted executions.
 */
data class AgentMetrics(
    val agentKey: String,
    val agentName: String,
    val harness: String?,
    val modelProfileId: UUID?,
    val modelLabel: String?,
    val executions: Int,
    val succeeded: Int,
    val failed: Int,
    val accepted: Int,
    /** Accepted executions with attempt 1 / accepted executions. */
    val firstPassRate: Double?,
    val cost: Money,
    val apiCost: Money,
    val costPerAccepted: Money?,
    val duration: DistributionStat,
)

/** ADR 0059: the agents compared on one template. [templateName] null when the library does not know it (any more). */
data class TemplateAgentMetrics(val templateKey: String, val templateName: String?, val rows: List<AgentMetrics>)

data class AgentInsights(
    val from: Instant,
    val to: Instant,
    val projectId: UUID?,
    /** Lowest cost per accepted step first. */
    val byAgent: List<AgentMetrics>,
    val byTemplate: List<TemplateAgentMetrics>,
    /** Finished task step executions without an agent record (before ADR 0059). */
    val unattributed: Int,
)
