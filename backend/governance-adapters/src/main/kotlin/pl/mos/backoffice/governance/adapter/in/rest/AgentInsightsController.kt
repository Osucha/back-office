package pl.mos.backoffice.governance.adapter.`in`.rest

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.governance.application.port.`in`.AgentInsightsQuery
import pl.mos.backoffice.governance.domain.model.AgentInsights
import pl.mos.backoffice.governance.domain.model.AgentMetrics
import java.time.Instant
import java.util.UUID

/** Agent insights of the `insights` tag of openapi.yaml (ADR 0059). */
@RestController
@RequestMapping("/api/v1/insights")
class AgentInsightsController(private val insights: AgentInsightsQuery) {

    @GetMapping("/agents")
    fun agents(
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): AgentInsightsDto = AgentInsightsDto.of(insights.get(projectId, from, to))
}

data class AgentMetricsDto(
    val agentKey: String,
    val agentName: String,
    val harness: String?,
    val modelProfileId: UUID?,
    val modelLabel: String?,
    val executions: Int,
    val succeeded: Int,
    val failed: Int,
    val accepted: Int,
    val firstPassRate: Double?,
    val cost: MoneyDto,
    val apiCost: MoneyDto,
    val costPerAccepted: MoneyDto?,
    val duration: DistributionStatDto,
) {
    companion object {
        fun of(m: AgentMetrics) = AgentMetricsDto(
            m.agentKey, m.agentName, m.harness, m.modelProfileId, m.modelLabel, m.executions, m.succeeded, m.failed, m.accepted, m.firstPassRate,
            MoneyDto.of(m.cost), MoneyDto.of(m.apiCost), m.costPerAccepted?.let(MoneyDto::of), DistributionStatDto.of(m.duration),
        )
    }
}

data class TemplateAgentMetricsDto(val templateKey: String, val templateName: String?, val rows: List<AgentMetricsDto>)

data class AgentInsightsDto(
    val from: Instant,
    val to: Instant,
    val projectId: UUID?,
    val byAgent: List<AgentMetricsDto>,
    val byTemplate: List<TemplateAgentMetricsDto>,
    val unattributed: Int,
) {
    companion object {
        fun of(i: AgentInsights) = AgentInsightsDto(
            from = i.from,
            to = i.to,
            projectId = i.projectId,
            byAgent = i.byAgent.map(AgentMetricsDto::of),
            byTemplate = i.byTemplate.map { t -> TemplateAgentMetricsDto(t.templateKey, t.templateName, t.rows.map(AgentMetricsDto::of)) },
            unattributed = i.unattributed,
        )
    }
}
