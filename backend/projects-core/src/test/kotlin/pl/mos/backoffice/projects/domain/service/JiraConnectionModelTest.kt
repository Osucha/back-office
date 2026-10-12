package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.ConnectionFakes.WRITE
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_READ
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_TRANSITION
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.JiraScopes
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.ProjectProbe
import pl.mos.backoffice.projects.domain.model.ScopeProbe
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.model.VerifiedAccount
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/** ADR 0035: system connection + list, personal connections, step settings, scoped tokens and their verification. */
class JiraConnectionModelTest {

    private val selector = ConnectionSelector()
    private val projectId = Fixtures.project().id
    private val anna = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    @Test
    fun `identifying the account with a scoped token needs every granular scope of myself`() {
        // Jira answers 401 to GET /myself when a scoped token lacks one of them (seen with read:user:jira alone)
        assertThat(JiraScopes.coversVerification(listOf("read:user:jira", "read:avatar:jira"))).isFalse()
        assertThat(JiraScopes.coversVerification(listOf("read:user:jira", "read:group:jira", "read:application-role:jira", "read:avatar:jira"))).isTrue()
        assertThat(JiraScopes.coversVerification(listOf("read:jira-user"))).isTrue()
    }

    @Test
    fun `the system connection is used when it covers the step, even if a tighter connection exists`() {
        val writer = ConnectionFakes.connection("writer", WRITE, projectId)
        val reader = ConnectionFakes.connection("reader", READ)
        val settings = ProjectJiraConnections(writer.id, listOf(reader.id))

        val choice = selector.selectSystem(setOf(ISSUE_READ), settings, listOf(writer), listOf(reader), "SHOP")

        assertThat(choice.connection).isEqualTo(writer)
        assertThat(choice.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)
        assertThat(choice.fallback).isFalse()
    }

    @Test
    fun `a system connection without the capability falls back to least privilege among the project list only`() {
        val system = ConnectionFakes.connection("reader", READ, projectId)
        val broadWriter = ConnectionFakes.connection("broad", WRITE)
        val commenter = ConnectionFakes.connection("commenter", setOf(ISSUE_COMMENT))
        val notListed = ConnectionFakes.connection("not listed", setOf(ISSUE_COMMENT))
        val settings = ProjectJiraConnections(system.id, listOf(broadWriter.id, commenter.id))

        val choice = selector.selectSystem(setOf(ISSUE_COMMENT), settings, listOf(system), listOf(broadWriter, commenter, notListed), "SHOP")

        assertThat(choice.connection).isEqualTo(commenter)
        assertThat(choice.fallback).isTrue()
        assertThat(choice.message).contains("the system connection \"reader\" lacks ISSUE_COMMENT").contains("from the project's connection list")

        val none = selector.selectSystem(setOf(ISSUE_TRANSITION), ProjectJiraConnections(system.id, listOf(commenter.id)), listOf(system), listOf(commenter, notListed), "SHOP")
        assertThat(none.status).isEqualTo(SelectionStatus.NONE)
        assertThat(none.message).contains("no connection of the project's list")
    }

    @Test
    fun `a system connection limited to other Jira projects falls back too`() {
        val system = ConnectionFakes.connection("web reader", READ, projectId, allowed = listOf("WEB"))
        val other = ConnectionFakes.connection("reader", READ)
        val choice = selector.selectSystem(setOf(ISSUE_READ), ProjectJiraConnections(system.id, listOf(other.id)), listOf(system), listOf(other), "SHOP")
        assertThat(choice.connection).isEqualTo(other)
        assertThat(choice.message).contains("is limited to the Jira projects [WEB]")
    }

    @Test
    fun `without a system connection the ADR 0030 rule over every project and platform connection applies`() {
        val reader = ConnectionFakes.connection("reader", READ)
        val writer = ConnectionFakes.connection("writer", WRITE, projectId)
        val choice = selector.selectSystem(setOf(ISSUE_READ), ProjectJiraConnections(), listOf(writer), listOf(reader), "SHOP")
        assertThat(choice.connection).isEqualTo(reader)
        assertThat(choice.fallback).isFalse()
    }

    @Test
    fun `personal connections are never candidates of the system rule, the ADR 0030 rule or a pin`() {
        val mine = ConnectionFakes.personal("Anna's Jira", READ, anna)
        assertThat(selector.select(setOf(ISSUE_READ), emptyList(), listOf(mine), "SHOP").status).isEqualTo(SelectionStatus.NONE)
        assertThat(selector.selectSystem(setOf(ISSUE_READ), ProjectJiraConnections(mine.id), emptyList(), listOf(mine), "SHOP").status)
            .isEqualTo(SelectionStatus.NONE)
        val pinned = selector.selectPinned(setOf(ISSUE_READ), emptyList(), listOf(mine), "SHOP", mine.id)
        assertThat(pinned.status).isEqualTo(SelectionStatus.PINNED_NOT_FOUND)
        assertThat(pinned.message).contains("personal connections cannot be pinned")
        assertThat(selector.order(emptyList(), listOf(mine))).isEmpty()
    }

    @Test
    fun `selectPersonal picks the person's least privileged fitting connection`() {
        val broad = ConnectionFakes.personal("Anna all", WRITE, anna)
        val commenter = ConnectionFakes.personal("Anna comments", setOf(ISSUE_COMMENT), anna)
        val choice = selector.selectPersonal(setOf(ISSUE_COMMENT), listOf(broad, commenter), "Anna Nowak", "SHOP")
        assertThat(choice.connection).isEqualTo(commenter)
        assertThat(choice.mode).isEqualTo(ConnectionMode.ACTING_USER)
        assertThat(choice.personal).isTrue()
        assertThat(choice.label).isEqualTo("personal connection of Anna Nowak (\"Anna comments\")")

        val none = selector.selectPersonal(setOf(ISSUE_TRANSITION), listOf(commenter), "Anna Nowak", "SHOP")
        assertThat(none.status).isEqualTo(SelectionStatus.NO_PERSONAL_CONNECTION)
        assertThat(none.message).contains("Anna Nowak has no personal Jira connection with capabilities [ISSUE_TRANSITION]").contains("My account")
    }

    @Test
    fun `step settings parse SYSTEM, a connection id and ACTING_USER with their flags`() {
        assertThat(StepConnectionConfig.of(emptyMap()).mode).isEqualTo(ConnectionMode.SYSTEM)
        assertThat(StepConnectionConfig.of(mapOf("jiraConnection" to "system")).mode).isEqualTo(ConnectionMode.SYSTEM)
        val id = ConnectionId.new()
        assertThat(StepConnectionConfig.of(mapOf("jiraConnection" to id.toString())).pinned).isEqualTo(id)
        val acting = StepConnectionConfig.of(mapOf("jiraConnection" to "ACTING_USER", "fallbackToSystem" to "true"))
        assertThat(acting.mode).isEqualTo(ConnectionMode.ACTING_USER)
        assertThat(acting.personalAllowed).isTrue()
        assertThat(acting.systemAllowed).isTrue()
        assertThat(StepConnectionConfig.of(mapOf("jiraConnection" to "ACTING_USER")).systemAllowed).isFalse()
        val allowing = StepConnectionConfig.of(mapOf("allowActingUser" to true))
        assertThat(allowing.personalAllowed).isTrue()
        assertThat(StepConnectionConfig.of(emptyMap()).personalAllowed).isFalse()
        assertThatThrownBy { StepConnectionConfig.of(mapOf("jiraConnection" to "the bot")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { StepConnectionConfig.of(mapOf("fallbackToSystem" to "maybe")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `project settings refuse duplicates and the system connection in the list, and forget a deleted connection`() {
        val a = ConnectionId.new()
        val b = ConnectionId.new()
        assertThatThrownBy { ProjectJiraConnections(a, listOf(a)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ProjectJiraConnections(a, listOf(b, b)) }.isInstanceOf(ValidationException::class.java)
        assertThat(ProjectJiraConnections(a, listOf(b)).without(a)).isEqualTo(ProjectJiraConnections(null, listOf(b)))
        assertThat(ProjectJiraConnections(a, listOf(b)).list()).containsExactly(a, b)
    }

    @Test
    fun `a scoped token needs a cloud id and scopes and calls the gateway, issue links stay on the site`() {
        val base = ConnectionFakes.connection("scoped", READ)
        assertThatThrownBy { base.copy(tokenKind = JiraTokenKind.SCOPED, cloudId = null, tokenScopes = listOf("read:jira-work")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { base.copy(tokenKind = JiraTokenKind.SCOPED, cloudId = CLOUD, tokenScopes = emptyList()) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { base.copy(tokenScopes = listOf("read:jira-work")) }.isInstanceOf(ValidationException::class.java)
        val scoped = base.copy(tokenKind = JiraTokenKind.SCOPED, cloudId = CLOUD, tokenScopes = listOf("read:jira-work"))
        assertThat(scoped.apiBaseUrl("https://api.atlassian.com/")).isEqualTo("https://api.atlassian.com/ex/jira/$CLOUD")
        assertThat(base.apiBaseUrl("https://api.atlassian.com")).isEqualTo("https://acme.atlassian.net")
    }

    @Test
    fun `personal connections need an owner and nothing else may have one`() {
        val base = ConnectionFakes.connection("x", READ)
        assertThatThrownBy { base.copy(scope = ConnectionScope.USER) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { base.copy(ownerUserId = anna) }.isInstanceOf(ValidationException::class.java)
        val mine = base.copy(scope = ConnectionScope.USER, ownerUserId = anna)
        assertThat(mine.ownedBy(anna)).isTrue()
        assertThat(mine.ownedBy(UUID.randomUUID())).isFalse()
    }

    @Test
    fun `scope catalogue covers capabilities with the classic or the granular set and spots extra write scopes`() {
        assertThat(JiraScopes.missing(setOf(ISSUE_READ), listOf("read:jira-work"))).isEmpty()
        assertThat(JiraScopes.missing(setOf(ISSUE_READ), listOf("read:issue:jira", "read:issue-details:jira", "read:jql:jira"))).isEmpty()
        assertThat(JiraScopes.missing(setOf(ISSUE_READ, ISSUE_COMMENT), listOf("read:jira-work"))).containsExactly(ISSUE_COMMENT)
        assertThat(JiraScopes.missing(setOf(ISSUE_COMMENT), listOf("write:comment:jira", "read:comment:jira"))).isEmpty()
        assertThat(JiraScopes.missing(setOf(ISSUE_TRANSITION), listOf("write:issue:jira", "read:issue.transition:jira", "read:status:jira"))).isEmpty()
        assertThat(JiraScopes.excessWrites(setOf(ISSUE_READ), listOf("read:jira-work", "write:comment:jira", "delete:issue:jira")))
            .containsExactly("delete:issue:jira", "write:comment:jira")
        assertThat(JiraScopes.excessWrites(setOf(ISSUE_COMMENT), listOf("write:comment:jira", "read:comment:jira"))).isEmpty()
        assertThat(JiraScopes.recommendedGranular(setOf(ISSUE_READ))).contains("read:issue:jira", "read:user:jira").noneMatch { it.startsWith("write:") }
    }

    @Test
    fun `verification of a scoped token reports missing and extra scopes and endpoints refused with 403`() {
        val probe = JiraProbe(
            VerifiedAccount("a", "Anna Nowak", "anna@acme.com"), null,
            listOf(ProjectProbe("SHOP", setOf(JiraPermission.BROWSE_PROJECTS, JiraPermission.ADD_COMMENTS, JiraPermission.DELETE_ISSUES))),
            listOf(
                ScopeProbe(ISSUE_READ, "GET /rest/api/3/search/jql", 200, ok = true),
                ScopeProbe(ISSUE_COMMENT, "GET /rest/api/3/issue/SHOP-1/comment", 403, ok = false),
            ),
        )
        val result = ConnectionVerificationPolicy().evaluate(
            setOf(ISSUE_READ, ISSUE_COMMENT), probe, Fixtures.now, JiraTokenKind.SCOPED, listOf("read:jira-work", "write:issue:jira"),
        )

        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        val codes = result.messages.map { it.code to it.severity }
        assertThat(codes).contains(
            VerificationCode.MISSING_SCOPES to MessageSeverity.ERROR, // ISSUE_COMMENT not declared, and the comment endpoint refused
            VerificationCode.EXCESS_SCOPES to MessageSeverity.WARNING, // write:issue:jira is not needed
            VerificationCode.EXCESS_PERMISSIONS to MessageSeverity.INFO, // the account can delete, the token cannot
        )
        assertThat(result.messages.filter { it.code == VerificationCode.MISSING_SCOPES }).hasSize(2)
        assertThat(result.messages.first { it.code == VerificationCode.EXCESS_SCOPES }.text).contains("write:issue:jira")
    }

    @Test
    fun `a classic token keeps the over-privileged warning and a scoped token with only the needed scopes is OK`() {
        val probe = JiraProbe(VerifiedAccount("a", "Anna", null), null, listOf(ProjectProbe("SHOP", setOf(JiraPermission.BROWSE_PROJECTS, JiraPermission.ADD_COMMENTS))))
        val classic = ConnectionVerificationPolicy().evaluate(setOf(ISSUE_READ), probe, Fixtures.now)
        assertThat(classic.status).isEqualTo(VerificationStatus.WARNING)
        val scoped = ConnectionVerificationPolicy().evaluate(setOf(ISSUE_READ), probe, Fixtures.now, JiraTokenKind.SCOPED, listOf("read:jira-work", "read:jira-user"))
        assertThat(scoped.status).isEqualTo(VerificationStatus.OK)
        val broad = ConnectionVerificationPolicy().evaluate(
            setOf(ISSUE_COMMENT), probe, Fixtures.now, JiraTokenKind.SCOPED, listOf("read:jira-work", "write:jira-work"),
        )
        assertThat(broad.messages.map { it.code }).contains(VerificationCode.BROAD_SCOPE)
    }

    companion object {
        const val CLOUD = "11111111-2222-3333-4444-555555555555"
    }
}
