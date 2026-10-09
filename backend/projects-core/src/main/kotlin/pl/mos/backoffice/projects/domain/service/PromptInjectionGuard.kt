package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.shared.prompt.InjectionDetector
import pl.mos.backoffice.shared.prompt.InjectionSignal

/**
 * How a task reacts to injection markers in its untrusted inputs (`config.injectionGuard` of the task
 * template, ADR 0029):
 * - [BLOCK] (default): every signal becomes a BLOCKER finding "Needs human review" (the review verdict is then
 *   REQUEST_CHANGES, so a BLOCKING CI gate fails and workflows route to rework or a human), and a
 *   `confidence` of the step is forced to 0;
 * - [FLAG]: signals become MINOR findings, the verdict is left to the review; confidence is untouched;
 * - [OFF]: signals are only written to the run log (for repositories that legitimately contain prompt text).
 */
enum class InjectionGuardMode {
    BLOCK, FLAG, OFF;

    companion object {
        fun of(value: Any?): InjectionGuardMode =
            entries.firstOrNull { it.name.equals(value?.toString()?.trim(), ignoreCase = true) } ?: BLOCK
    }
}

/** One untrusted input of a step: [diff] inputs are scanned as unified diffs (file and line of each signal). */
data class UntrustedInput(val source: String, val text: String?, val diff: Boolean = false)

/**
 * Deterministic checks around model calls that judge untrusted content (docs/architecture/12-prompt-injection.md):
 * injection scanning of the inputs, strict validation of the structured review answer and its consistency
 * with the diff. Pure domain logic; the executor decides what to do with the result.
 */
class PromptInjectionGuard {

    fun scan(inputs: List<UntrustedInput>): List<InjectionSignal> = inputs.flatMap { input ->
        if (input.diff) InjectionDetector.scanDiff(input.text, input.source) else InjectionDetector.scan(input.text, input.source)
    }.distinctBy { Triple(it.kind, it.source, it.line) }.take(MAX_SIGNALS)

    /** Findings that report [signals] in [mode]; empty for [InjectionGuardMode.OFF]. */
    fun findingsFor(signals: List<InjectionSignal>, mode: InjectionGuardMode): List<Finding> {
        if (mode == InjectionGuardMode.OFF || signals.isEmpty()) return emptyList()
        val severity = if (mode == InjectionGuardMode.BLOCK) Severity.BLOCKER else Severity.MINOR
        val prefix = if (mode == InjectionGuardMode.BLOCK) ReviewReport.NEEDS_HUMAN_PREFIX else "Possible prompt injection: "
        return signals.take(MAX_FINDINGS).map { signal ->
            Finding(
                severity = severity,
                file = signal.source,
                line = signal.line,
                title = prefix + "possible prompt injection (${signal.kind.name.lowercase().replace('_', ' ')})",
                detail = "The input contains ${signal.kind.description}: \"${signal.excerpt}\". Text addressed to the AI reviewer is " +
                    "treated as an attack on the review, so this change is not approved automatically; a person must review it.",
                suggestion = "Remove the text, or have a maintainer review the change and accept it explicitly.",
            )
        }
    }

    /**
     * Validates the structured answer of a review task against the review contract: `summary` string,
     * `findings` array of {severity in BLOCKER|MAJOR|MINOR|NIT, file string, title non-blank, detail string,
     * line integer or null}, `verdict` in APPROVE|COMMENT|REQUEST_CHANGES, `score` 0..100, and
     * REQUEST_CHANGES only with at least one finding. Returns the problems; empty = valid.
     */
    fun validateReviewAnswer(structured: Map<String, Any?>?): List<String> {
        if (structured == null) return listOf("the answer is not a JSON object")
        val problems = mutableListOf<String>()
        if (structured["summary"] !is String) problems += "summary must be a string"
        val findings = structured["findings"]
        if (findings !is List<*>) {
            problems += "findings must be an array"
        } else {
            findings.forEachIndexed { i, item -> problems += findingProblems(i, item) }
        }
        val verdict = structured["verdict"]?.toString()
        if (Verdict.entries.none { it.name == verdict }) problems += "verdict must be one of ${Verdict.entries.joinToString()}"
        val score = structured["score"]
        if (score !is Number || score.toDouble() !in 0.0..100.0) problems += "score must be a number from 0 to 100"
        if (verdict == Verdict.REQUEST_CHANGES.name && (findings as? List<*>).isNullOrEmpty()) {
            problems += "verdict REQUEST_CHANGES must be justified by at least one finding"
        }
        return problems
    }

    /** Problems of the finding at [index] of a review answer (see [validateReviewAnswer]). */
    private fun findingProblems(index: Int, item: Any?): List<String> {
        val m = item as? Map<*, *> ?: return listOf("findings[$index] must be an object")
        val problems = mutableListOf<String>()
        val severity = m["severity"]?.toString()
        if (Severity.entries.none { it.name == severity }) problems += "findings[$index].severity must be one of ${Severity.entries.joinToString()}"
        if (m["file"] !is String) problems += "findings[$index].file must be a string"
        if ((m["title"] as? String).isNullOrBlank()) problems += "findings[$index].title must be a non-empty string"
        if (m["detail"] !is String) problems += "findings[$index].detail must be a string"
        if (!isIntegerOrNull(m["line"])) problems += "findings[$index].line must be an integer or null"
        return problems
    }

    private fun isIntegerOrNull(value: Any?): Boolean =
        value == null || (value is Number && value.toDouble() == Math.floor(value.toDouble()))

    /** Findings that cite a file the diff does not touch (hallucinated or steered); empty when the diff is unknown. */
    fun findingsOutsideDiff(findings: List<Finding>, diff: String?): List<Finding> {
        val files = InjectionDetector.filesOf(diff)
        if (files.isEmpty()) return emptyList()
        return findings.filter { f ->
            val file = f.file.trim().removePrefix("./").removePrefix("a/").removePrefix("b/")
            file.isNotEmpty() && !f.title.startsWith(ReviewReport.NEEDS_HUMAN_PREFIX) && files.none { it == file || it.endsWith("/$file") || file.endsWith("/$it") }
        }
    }

    /** The fail-closed finding when the review answer stays invalid after the repair attempt. */
    fun invalidAnswerFinding(problems: List<String>): Finding = Finding(
        severity = Severity.BLOCKER,
        file = "",
        title = ReviewReport.NEEDS_HUMAN_PREFIX + "the AI review answer was invalid",
        detail = "The review model did not return a valid answer after one repair attempt (${problems.take(5).joinToString("; ")}). " +
            "An invalid answer is never treated as an approval.",
    )

    companion object {
        const val MAX_SIGNALS = 20
        const val MAX_FINDINGS = 10
    }
}
