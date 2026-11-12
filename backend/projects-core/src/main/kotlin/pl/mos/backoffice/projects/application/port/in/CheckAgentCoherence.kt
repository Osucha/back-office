package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.Money
import java.util.UUID

/**
 * Use case (ADR 0061 decision 4): on request, a model (the platform default for model answers, booked as usage) reads an
 * agent's whole environment (platform preamble, instructions, constitution, project and agent rules, skill names and
 * descriptions; with a project the primary repository's `AGENTS.md` / `CLAUDE.md` when readable) and lists contradictions
 * with quotes. `library.view`; with [projectId] (and for a project agent) also `project.view`. Audited as
 * `AGENT_COHERENCE_CHECK`. `InvalidStateException` (409) when no usable default model is configured.
 */
interface CheckAgentCoherence {
    fun check(agentId: UUID, projectId: ProjectId?): CoherenceReport
}

enum class CoherenceSeverity { CONTRADICTION, AMBIGUITY, REDUNDANCY }

data class CoherenceQuote(val source: String, val text: String)

data class CoherenceFinding(
    val severity: CoherenceSeverity,
    val summary: String,
    val quotes: List<CoherenceQuote> = emptyList(),
    val suggestion: String? = null,
)

/** [model] is the display name of the model that checked; [sources] what it read (`constitution`, `rule:<key>`...). */
data class CoherenceReport(
    val agentKey: String,
    val model: String?,
    val cost: Money,
    val sources: List<String>,
    val findings: List<CoherenceFinding>,
)
