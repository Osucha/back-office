package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.Finding
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
    fun `duplicate findings collapse`() {
        assertThat(policy.deduplicate(listOf(finding(Severity.MINOR, "Same"), finding(Severity.MINOR, "same ")))).hasSize(1)
    }
}
