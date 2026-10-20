package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.CoverageStatus
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.RequirementCoverage
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.Verdict

data class ReviewAssessment(val score: Int, val verdict: Verdict)

/**
 * Turns a list of findings into a score (0-100) and a verdict:
 * any BLOCKER -> REQUEST_CHANGES; any MISSING requirement of a review against the specification -> REQUEST_CHANGES;
 * more than two MAJOR -> REQUEST_CHANGES; otherwise APPROVE when score >= 80, else COMMENT.
 *
 * Score starts at 100 and subtracts per finding: BLOCKER 40, MAJOR 15, MINOR 5, NIT 1. A missing requirement does
 * not change the score (the model reports it as a MAJOR finding as well); it only blocks approval.
 */
class ReviewVerdictPolicy {

    fun assess(findings: List<Finding>, coverage: List<RequirementCoverage> = emptyList()): ReviewAssessment {
        val score = score(findings)
        val blockers = findings.count { it.severity == Severity.BLOCKER }
        val majors = findings.count { it.severity == Severity.MAJOR }
        val missing = coverage.count { it.status == CoverageStatus.MISSING }
        val verdict = when {
            blockers > 0 -> Verdict.REQUEST_CHANGES
            missing > 0 -> Verdict.REQUEST_CHANGES
            majors > 2 -> Verdict.REQUEST_CHANGES
            score >= APPROVE_THRESHOLD -> Verdict.APPROVE
            else -> Verdict.COMMENT
        }
        return ReviewAssessment(score, verdict)
    }

    fun score(findings: List<Finding>): Int {
        val penalty = findings.sumOf { penalty(it.severity) }
        return (100 - penalty).coerceIn(0, 100)
    }

    /** Removes findings that repeat the same file/line/title triple. */
    fun deduplicate(findings: List<Finding>): List<Finding> =
        findings.distinctBy { Triple(it.file, it.line, it.title.trim().lowercase()) }

    private fun penalty(severity: Severity): Int = when (severity) {
        Severity.BLOCKER -> 40
        Severity.MAJOR -> 15
        Severity.MINOR -> 5
        Severity.NIT -> 1
    }

    companion object {
        const val APPROVE_THRESHOLD = 80
    }
}
