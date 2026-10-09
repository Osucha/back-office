package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_READ
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_TRANSITION
import pl.mos.backoffice.projects.domain.model.JiraPermission.ADD_COMMENTS
import pl.mos.backoffice.projects.domain.model.JiraPermission.ADMINISTER_PROJECTS
import pl.mos.backoffice.projects.domain.model.JiraPermission.BROWSE_PROJECTS
import pl.mos.backoffice.projects.domain.model.JiraPermission.DELETE_ISSUES
import pl.mos.backoffice.projects.domain.model.JiraPermission.TRANSITION_ISSUES
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.ProbeFailure
import pl.mos.backoffice.projects.domain.model.ProjectProbe
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.model.VerifiedAccount

/** ADR 0030: what Jira grants vs. what the declared capabilities need. */
class ConnectionVerificationPolicyTest {

    private val policy = ConnectionVerificationPolicy()
    private val account = VerifiedAccount("acc", "Reader Bot", "reader@acme.com")

    @Test
    fun `exactly the needed permissions verify OK`() {
        val result = policy.evaluate(setOf(ISSUE_READ), JiraProbe(account, null, listOf(ProjectProbe("SHOP", setOf(BROWSE_PROJECTS)))), now)

        assertThat(result.status).isEqualTo(VerificationStatus.OK)
        assertThat(result.missing).isEmpty()
        assertThat(result.excess).isEmpty()
        assertThat(result.messages.single().code).isEqualTo(VerificationCode.VERIFIED)
    }

    @Test
    fun `missing permissions for declared capabilities fail the verification`() {
        val probe = JiraProbe(account, null, listOf(ProjectProbe("SHOP", setOf(BROWSE_PROJECTS))))
        val result = policy.evaluate(setOf(ISSUE_READ, ISSUE_COMMENT, ISSUE_TRANSITION), probe, now)

        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(result.missing).containsExactly(ADD_COMMENTS, TRANSITION_ISSUES)
        val message = result.messages.single { it.code == VerificationCode.MISSING_PERMISSIONS }
        assertThat(message.severity).isEqualTo(MessageSeverity.ERROR)
        assertThat(message.projectKey).isEqualTo("SHOP")
        assertThat(message.text).contains("Add Comments").contains("Transition Issues")
    }

    @Test
    fun `excess permissions make the connection over-privileged`() {
        val probe = JiraProbe(account, null, listOf(ProjectProbe("SHOP", setOf(BROWSE_PROJECTS, ADD_COMMENTS, DELETE_ISSUES, ADMINISTER_PROJECTS))))
        val result = policy.evaluate(setOf(ISSUE_READ), probe, now)

        assertThat(result.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(result.excess).containsExactly(ADD_COMMENTS, DELETE_ISSUES, ADMINISTER_PROJECTS)
        val warning = result.messages.single { it.code == VerificationCode.EXCESS_PERMISSIONS }
        assertThat(warning.text).contains("over-privileged").contains("delete issues").contains("administer the project").contains("only Browse Projects")
        assertThat(result.projects.single().granted).contains(DELETE_ISSUES)
    }

    @Test
    fun `missing beats excess and every project is reported`() {
        val probe = JiraProbe(
            account, null,
            listOf(ProjectProbe("SHOP", setOf(BROWSE_PROJECTS, DELETE_ISSUES)), ProjectProbe("OPS", emptySet()), ProjectProbe("NOPE", null, ProbeFailure(VerificationCode.PROJECT_NOT_FOUND, "not found"))),
        )
        val result = policy.evaluate(setOf(ISSUE_READ), probe, now)

        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(result.projects.map { it.projectKey }).containsExactly("SHOP", "OPS")
        assertThat(result.messages.map { it.code }).contains(VerificationCode.EXCESS_PERMISSIONS, VerificationCode.MISSING_PERMISSIONS, VerificationCode.PROJECT_NOT_FOUND)
    }

    @Test
    fun `an authentication failure fails without project checks`() {
        val result = policy.evaluate(setOf(ISSUE_READ), JiraProbe(null, ProbeFailure(VerificationCode.AUTHENTICATION_FAILED, "HTTP 401")), now)

        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(result.messages.single().code).isEqualTo(VerificationCode.AUTHENTICATION_FAILED)
    }

    @Test
    fun `without a project key only the token is proven`() {
        val result = policy.evaluate(setOf(ISSUE_READ), JiraProbe(account, null, emptyList()), now)

        assertThat(result.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(result.messages.single().code).isEqualTo(VerificationCode.NO_PROJECT_TO_CHECK)
    }
}
