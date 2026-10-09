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

/** Result of a review task (`code-review` template): stored on the run and copied to the CI execution. */
data class ReviewReport(
    val verdict: Verdict,
    val score: Int,
    val summary: String,
    val findings: List<Finding> = emptyList(),
    val modelRef: ModelRef? = null,
    val usage: TokenUsage = TokenUsage.ZERO,
    val cost: Money = Money.ZERO,
) {
    val blockers: Int get() = findings.count { it.severity == Severity.BLOCKER }
    val majors: Int get() = findings.count { it.severity == Severity.MAJOR }

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
        if (findings.isNotEmpty()) {
            appendLine("| Severity | File | Line | Finding |")
            appendLine("|---|---|---|---|")
            findings.sortedBy { it.severity.ordinal }.forEach { f ->
                val detail = (f.title + ": " + f.detail + (f.suggestion?.let { " Suggestion: $it" } ?: "")).replace("|", "\\|").replace("\n", " ")
                appendLine("| ${f.severity} | `${f.file}` | ${f.line ?: ""} | $detail |")
            }
        }
    }

    companion object {
        /** Title prefix of findings raised by a deterministic guard rather than by the model. */
        const val NEEDS_HUMAN_PREFIX = "Needs human review: "
    }
}
