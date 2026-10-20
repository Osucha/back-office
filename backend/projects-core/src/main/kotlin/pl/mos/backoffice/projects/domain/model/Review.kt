package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage

enum class Verdict { APPROVE, COMMENT, REQUEST_CHANGES }
enum class Severity { BLOCKER, MAJOR, MINOR, NIT }

data class Finding(
    val severity: Severity,
    val file: String,
    val line: Int? = null,
    val title: String,
    val detail: String,
    val suggestion: String? = null,
)

/** How far a change covers one agreed requirement (acceptance criterion, task verification) in a review against the specification. */
enum class CoverageStatus { MET, PARTIAL, MISSING, NOT_APPLICABLE }

/** One row of the coverage matrix of `review-against-spec`: the requirement, its status and the evidence (file and line). */
data class RequirementCoverage(val requirement: String, val status: CoverageStatus, val evidence: String = "")

/**
 * Result of a review task (`code-review`, `review-against-spec`): stored on the run and copied to the CI execution.
 * [coverage] is the requirement coverage matrix of a review against the specification (empty for a plain code review);
 * it is part of the `review` artefact (markdown) but not of the stored run review.
 */
data class ReviewReport(
    val verdict: Verdict,
    val score: Int,
    val summary: String,
    val findings: List<Finding> = emptyList(),
    val modelRef: ModelRef? = null,
    val usage: TokenUsage = TokenUsage.ZERO,
    val cost: Money = Money.ZERO,
    val coverage: List<RequirementCoverage> = emptyList(),
) {
    val blockers: Int get() = findings.count { it.severity == Severity.BLOCKER }
    val majors: Int get() = findings.count { it.severity == Severity.MAJOR }

    /** Agreed requirements the change does not implement at all; any of them means REQUEST_CHANGES. */
    val missingRequirements: Int get() = coverage.count { it.status == CoverageStatus.MISSING }

    /**
     * A deterministic guard (prompt-injection markers, an invalid model answer) took the decision away from
     * the model: the verdict is REQUEST_CHANGES and a person has to look (ADR 0029).
     */
    val humanReviewRequired: Boolean get() = findings.any { it.severity == Severity.BLOCKER && it.title.startsWith(NEEDS_HUMAN_PREFIX) }

    /** Routing view exposed as `review` to decision expressions and templates. */
    fun toStateMap(): Map<String, Any?> = mapOf(
        "verdict" to verdict.name,
        "score" to score,
        "summary" to summary,
        "blockers" to blockers,
        "majors" to majors,
        "findings" to findings.size,
        "humanReviewRequired" to humanReviewRequired,
        "missingRequirements" to missingRequirements,
    )

    /** Markdown rendering used as the `review` artefact and printed by the CI job. */
    fun toMarkdown(): String = buildString {
        appendLine("## AI code review: $verdict (score $score/100)")
        appendLine()
        if (humanReviewRequired) {
            appendLine("**Needs human review:** a deterministic guard blocked automatic approval (see the findings).")
            appendLine()
        }
        if (summary.isNotBlank()) {
            appendLine(summary)
            appendLine()
        }
        if (coverage.isNotEmpty()) {
            appendLine("### Requirement coverage" + if (missingRequirements > 0) " ($missingRequirements missing)" else "")
            appendLine()
            appendLine("| Requirement | Status | Evidence |")
            appendLine("|---|---|---|")
            coverage.forEach { c -> appendLine("| ${cell(c.requirement)} | ${c.status} | ${cell(c.evidence)} |") }
            appendLine()
        }
        if (findings.isNotEmpty()) {
            if (coverage.isNotEmpty()) {
                appendLine("### Findings")
                appendLine()
            }
            appendLine("| Severity | File | Line | Finding |")
            appendLine("|---|---|---|---|")
            findings.sortedBy { it.severity.ordinal }.forEach { f ->
                val detail = (f.title + ": " + f.detail + (f.suggestion?.let { " Suggestion: $it" } ?: "")).replace("|", "\\|").replace("\n", " ")
                appendLine("| ${f.severity} | `${f.file}` | ${f.line ?: ""} | $detail |")
            }
        }
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")

    companion object {
        /** Title prefix of findings raised by a deterministic guard rather than by the model. */
        const val NEEDS_HUMAN_PREFIX = "Needs human review: "
    }
}
