package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.CoverageStatus
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.RequirementCoverage
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.Verdict

class ReviewVerdictPolicyTest {

    private val policy = ReviewVerdictPolicy()

    private fun finding(severity: Severity, title: String = severity.name, line: Int? = 1) = Finding(severity, "a.kt", line, title, "detail")

    @Test
    fun `blockers and too many majors request changes, high scores approve, the rest comment`() {
        assertThat(policy.assess(listOf(finding(Severity.BLOCKER))).verdict).isEqualTo(Verdict.REQUEST_CHANGES)
        assertThat(policy.assess(List(3) { finding(Severity.MAJOR, "m$it") }).verdict).isEqualTo(Verdict.REQUEST_CHANGES)
        assertThat(policy.assess(listOf(finding(Severity.MINOR), finding(Severity.NIT))).let { it.verdict to it.score }).isEqualTo(Verdict.APPROVE to 94)
        assertThat(policy.assess(List(2) { finding(Severity.MAJOR, "m$it") }).verdict).isEqualTo(Verdict.COMMENT)
        assertThat(policy.assess(emptyList()).score).isEqualTo(100)
    }

    @Test
    fun `a MISSING requirement requests changes even without findings, PARTIAL and NOT_APPLICABLE do not`() {
        val missing = policy.assess(emptyList(), listOf(RequirementCoverage("AC1: blocks saving without a reason", CoverageStatus.MISSING)))
        assertThat(missing.verdict).isEqualTo(Verdict.REQUEST_CHANGES)
        assertThat(missing.score).describedAs("the score comes from the findings only").isEqualTo(100)
        val partial = policy.assess(listOf(finding(Severity.MINOR)), listOf(RequirementCoverage("AC1", CoverageStatus.PARTIAL), RequirementCoverage("AC2", CoverageStatus.NOT_APPLICABLE)))
        assertThat(partial.verdict).isEqualTo(Verdict.APPROVE)
    }

    @Test
    fun `the review report renders the coverage matrix and counts missing requirements`() {
        val report = ReviewReport(
            Verdict.REQUEST_CHANGES, 85, "One criterion is not implemented.", listOf(finding(Severity.MAJOR, "Missing: AC2")),
            coverage = listOf(RequirementCoverage("AC1 | shows list", CoverageStatus.MET, "src/A.kt:12"), RequirementCoverage("AC2", CoverageStatus.MISSING)),
        )
        assertThat(report.missingRequirements).isEqualTo(1)
        assertThat(report.toStateMap()).containsEntry("missingRequirements", 1)
        assertThat(report.toMarkdown())
            .contains("### Requirement coverage (1 missing)")
            .contains("| AC1 \\| shows list | MET | src/A.kt:12 |")
            .contains("| AC2 | MISSING |  |")
            .contains("### Findings")
    }

    @Test
    fun `duplicate findings collapse`() {
        assertThat(policy.deduplicate(listOf(finding(Severity.MINOR, "Same"), finding(Severity.MINOR, "same ")))).hasSize(1)
    }
}
