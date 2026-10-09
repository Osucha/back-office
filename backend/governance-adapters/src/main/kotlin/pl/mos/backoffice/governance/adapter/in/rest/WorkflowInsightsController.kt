package pl.mos.backoffice.governance.adapter.`in`.rest

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.governance.application.port.`in`.WorkflowInsightsQuery
import pl.mos.backoffice.governance.domain.model.NodeMetrics
import pl.mos.backoffice.governance.domain.model.WorkflowInsights
import pl.mos.backoffice.governance.domain.model.WorkflowMetrics
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Workflow metrics of the `insights` tag of openapi.yaml (ADR 0037). */
@RestController
@RequestMapping("/api/v1/insights")
class WorkflowInsightsController(private val insights: WorkflowInsightsQuery) {

    @GetMapping("/workflows")
    fun workflow(
        @RequestParam(required = false) libraryWorkflowId: UUID?,
        @RequestParam(required = false) workflowId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): WorkflowInsightsDto = WorkflowInsightsDto.of(insights.get(libraryWorkflowId, workflowId, from, to))

    @GetMapping("/library-workflows")
    fun library(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): List<LibraryWorkflowMetricsRowDto> =
        insights.libraryHeadlines(from, to).map { (id, m) -> LibraryWorkflowMetricsRowDto(id, WorkflowMetricsDto.of(m)) }.sortedBy { it.libraryWorkflowId }
}

data class WorkflowRunCountsDto(val total: Int, val successful: Int, val failed: Int, val cancelled: Int, val active: Int, val modified: Int)

data class WorkflowMetricsDto(
    val runs: WorkflowRunCountsDto,
    val successRate: Double?,
    val firstPassRate: Double?,
    val reworkLoopsPerRun: Double?,
    val duration: DistributionStatDto,
    val activeTime: DistributionStatDto,
    val humanWait: DistributionStatDto,
    val totalCost: MoneyDto,
    val costPerRun: MoneyDto,
    val costPerSuccessfulRun: MoneyDto,
    val inputTokens: Long,
    val outputTokens: Long,
    val mergeRequests: Int,
) {
    companion object {
        fun of(m: WorkflowMetrics) = WorkflowMetricsDto(
            runs = m.runs.let { WorkflowRunCountsDto(it.total, it.successful, it.failed, it.cancelled, it.active, it.modified) },
            successRate = m.successRate,
            firstPassRate = m.firstPassRate,
            reworkLoopsPerRun = m.reworkLoopsPerRun,
            duration = DistributionStatDto.of(m.duration),
            activeTime = DistributionStatDto.of(m.activeTime),
            humanWait = DistributionStatDto.of(m.humanWait),
            totalCost = MoneyDto.of(m.totalCost),
            costPerRun = MoneyDto.of(m.costPerRun),
            costPerSuccessfulRun = MoneyDto.of(m.costPerSuccessfulRun),
            inputTokens = m.inputTokens,
            outputTokens = m.outputTokens,
            mergeRequests = m.mergeRequests,
        )
    }
}

data class VersionMetricsDto(val version: Int, val metrics: WorkflowMetricsDto)
data class ProjectMetricsDto(val projectId: UUID, val projectKey: String, val metrics: WorkflowMetricsDto)
data class PortCountDto(val port: String, val count: Int)
data class WorkflowNodeMetricsDto(
    val nodeId: String,
    val nodeName: String,
    val kind: String,
    val templateKey: String?,
    val executions: Int,
    val succeeded: Int,
    val failed: Int,
    val failureRate: Double?,
    val duration: DistributionStatDto,
    val cost: MoneyDto,
    val ports: List<PortCountDto>,
) {
    companion object {
        fun of(n: NodeMetrics) = WorkflowNodeMetricsDto(
            n.nodeId, n.nodeName, n.kind, n.templateKey, n.executions, n.succeeded, n.failed,
            n.failureRate?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP).toDouble() },
            DistributionStatDto.of(n.duration), MoneyDto.of(n.cost), n.ports.map { PortCountDto(it.port, it.count) },
        )
    }
}
data class WeeklyOutcomeDto(val weekStart: LocalDate, val successful: Int, val failed: Int, val cost: MoneyDto)

data class WorkflowInsightsDto(
    val from: Instant,
    val to: Instant,
    val libraryWorkflowId: UUID?,
    val workflowId: UUID?,
    val totals: WorkflowMetricsDto,
    val byVersion: List<VersionMetricsDto>,
    val byProject: List<ProjectMetricsDto>,
    val byNode: List<WorkflowNodeMetricsDto>,
    val weekly: List<WeeklyOutcomeDto>,
) {
    companion object {
        fun of(i: WorkflowInsights) = WorkflowInsightsDto(
            from = i.from,
            to = i.to,
            libraryWorkflowId = i.libraryWorkflowId,
            workflowId = i.workflowId,
            totals = WorkflowMetricsDto.of(i.totals),
            byVersion = i.byVersion.map { VersionMetricsDto(it.version, WorkflowMetricsDto.of(it.metrics)) },
            byProject = i.byProject.map { ProjectMetricsDto(it.projectId, it.projectKey, WorkflowMetricsDto.of(it.metrics)) },
            byNode = i.byNode.map(WorkflowNodeMetricsDto::of),
            weekly = i.weekly.map { WeeklyOutcomeDto(it.weekStart, it.successful, it.failed, MoneyDto.of(it.cost)) },
        )
    }
}

data class LibraryWorkflowMetricsRowDto(val libraryWorkflowId: UUID, val metrics: WorkflowMetricsDto)
