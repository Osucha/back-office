package pl.mos.backoffice.projects.adapter.`in`.rest

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.CheckAgentCoherence
import pl.mos.backoffice.projects.application.port.`in`.CoherenceReport
import pl.mos.backoffice.projects.domain.model.ProjectId
import java.util.UUID

/**
 * `POST /api/v1/agents/{agentId}/coherence-check` (openapi tag `task-library`, ADR 0061). Served by the Projects context:
 * the check needs the project constitution and the default model besides the agent the Task Library resolves.
 */
@RestController
class AgentCoherenceController(private val coherence: CheckAgentCoherence) {

    @PostMapping("/api/v1/agents/{agentId}/coherence-check")
    fun check(@PathVariable agentId: UUID, @RequestParam(required = false) projectId: UUID?): CoherenceReportDto =
        CoherenceReportDto.of(coherence.check(agentId, projectId?.let(::ProjectId)))
}

data class CoherenceQuoteDto(val source: String, val text: String)

data class CoherenceFindingDto(val severity: String, val summary: String, val quotes: List<CoherenceQuoteDto>, val suggestion: String?)

/** `CoherenceReport` schema (ADR 0061). */
data class CoherenceReportDto(
    val agentKey: String,
    val model: String?,
    val cost: MoneyDto,
    val sources: List<String>,
    val findings: List<CoherenceFindingDto>,
) {
    companion object {
        fun of(r: CoherenceReport) = CoherenceReportDto(
            r.agentKey, r.model, MoneyDto(r.cost.amount, r.cost.currency), r.sources,
            r.findings.map { f -> CoherenceFindingDto(f.severity.name, f.summary, f.quotes.map { CoherenceQuoteDto(it.source, it.text) }, f.suggestion) },
        )
    }
}
