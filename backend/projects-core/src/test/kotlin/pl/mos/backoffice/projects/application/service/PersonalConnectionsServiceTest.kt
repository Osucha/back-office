package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections.ConnectionCommand
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.JiraPermission.BROWSE_PROJECTS
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/**
 * ADR 0035: personal connections (owner only, never readable by others, metadata for administrators), scoped tokens
 * (cloud id discovery, declared scopes), the classic-token acknowledgement and the project's system connection + list.
 */
class PersonalConnectionsServiceTest {

    private var project: Project = Fixtures.project()
    private val projects = mockk<ProjectRepository> {
        every { findById(any()) } answers { project }
        every { findAll(any<ProjectFilter>()) } answers { listOf(project) }
        val saved = slot<Project>()
        every { save(capture(saved)) } answers { saved.captured.also { project = it } }
    }
    private val connections = ConnectionFakes.InMemoryConnections()
    private val trackers = ConnectionFakes.FakeTrackers(granted = mapOf("SHOP" to setOf(BROWSE_PROJECTS)))
    private val audit = RecordingAuditPort()

    private fun service(gate: FakeAuthorizationGate) = ConnectionService(
        connections, projects, LegacyConnectionSource.NONE, ConnectionFakes.FakeSealer(), trackers, ConnectionSelector(), ConnectionVerificationPolicy(),
        gate, audit, Clock.fixed(Fixtures.now),
    )

    private val anna = FakeAuthorizationGate.permitting(ProjectsPermissions.CONNECTIONS_PERSONAL)
    private val otherUser = FakeAuthorizationGate(
        FakeAuthorizationGate.user(platform = setOf(ProjectsPermissions.CONNECTIONS_PERSONAL)).copy(userId = UserId(UUID.randomUUID()), displayName = "Bob Other"),
    )
    private val admin = FakeAuthorizationGate.permitting(ProjectsPermissions.CONNECTIONS_MANAGE, ProjectsPermissions.INTEGRATIONS_VIEW)
    private val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))

    private fun classic(name: String = "My Jira", ack: Boolean = true) = ConnectionCommand(
        type = ConnectionType.JIRA, name = name, siteUrl = "https://acme.atlassian.net", accountEmail = "anna@acme.com", secret = "tok-123",
        capabilities = READ, allowedProjectKeys = listOf("SHOP"), acknowledgeClassicToken = ack,
    )

    private fun scoped(scopes: List<String> = listOf("read:jira-work", "read:jira-user"), cloudId: String? = null) = classic("My scoped").copy(
        tokenKind = JiraTokenKind.SCOPED, tokenScopes = scopes, cloudId = cloudId, acknowledgeClassicToken = false,
    )

    @Test
    fun `a user creates a personal connection that only they can read`() {
        val mine = service(anna).createMine(classic())

        assertThat(anna.checked.first()).isEqualTo("connections.personal")
        assertThat(mine.scope).isEqualTo(ConnectionScope.USER)
        assertThat(mine.ownerUserId).isEqualTo(FakeAuthorizationGate.TEST_USER_ID.value)
        assertThat(mine.ownerName).isEqualTo("Alice Test")
        assertThat(service(anna).listMine()).containsExactly(mine)
        assertThat(service(anna).getMine(mine.id)).isEqualTo(mine)

        // nobody else can read, change or delete it, and the platform and project endpoints do not see it (404)
        assertThat(service(otherUser).listMine()).isEmpty()
        assertThatThrownBy { service(otherUser).getMine(mine.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(otherUser).deleteMine(mine.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(admin).get(null, mine.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(admin).delete(null, mine.id) }.isInstanceOf(NotFoundException::class.java)
        assertThat(service(admin).listPlatform()).isEmpty()
        assertThat(audit.entries.first { it.operation == "CONNECTION_CREATE" }.details).containsEntry("scope", "USER").doesNotContainValue("tok-123")
    }

    @Test
    fun `administrators see personal connections as metadata only and may not use them`() {
        val mine = service(anna).createMine(classic())
        assertThat(service(admin).listAllPersonal().single().id).isEqualTo(mine.id)
        assertThatThrownBy { service(anna).listAllPersonal() }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(admin).verify(null, mine.id, null) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `personal connections need connections personal and a person, not a CI token`() {
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).listMine() }.isInstanceOf(InsufficientPermissionException::class.java)
        val ci = FakeAuthorizationGate(FakeAuthorizationGate.user(platform = setOf(ProjectsPermissions.CONNECTIONS_PERSONAL)).copy(kind = PrincipalKind.CI_TOKEN))
        assertThatThrownBy { service(ci).createMine(classic()) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `a classic token must be acknowledged, a scoped token gets its cloud id discovered and needs scopes`() {
        assertThatThrownBy { service(anna).createMine(classic(ack = false)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("acknowledgeClassicToken")

        val token = service(anna).createMine(scoped())
        assertThat(token.tokenKind).isEqualTo(JiraTokenKind.SCOPED)
        assertThat(token.cloudId).isEqualTo(trackers.cloudId)
        assertThat(trackers.discovered).containsExactly("https://acme.atlassian.net")
        assertThat(token.tokenScopes).containsExactly("read:jira-user", "read:jira-work")

        assertThatThrownBy { service(anna).createMine(scoped(scopes = emptyList()).copy(name = "x")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("tokenScopes")
        trackers.cloudId = null
        assertThatThrownBy { service(anna).createMine(scoped().copy(name = "y")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("cloudId")
        val given = service(anna).createMine(scoped(cloudId = "AAAAAAAA-2222-3333-4444-555555555555").copy(name = "z"))
        assertThat(given.cloudId).isEqualTo("aaaaaaaa-2222-3333-4444-555555555555")
    }

    @Test
    fun `the project's system connection and list accept only project and platform connections`() {
        val reader = service(admin).create(null, classic("Platform reader"))
        val mine = service(anna).createMine(classic())

        assertThatThrownBy { service(owner).updateJiraConnections(project.id, ProjectJiraConnections(mine.id)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("personal connections cannot be chosen")

        val view = service(owner).updateJiraConnections(project.id, ProjectJiraConnections(reader.id))
        assertThat(view.system).isEqualTo(reader)
        assertThat(project.jiraConnections.systemConnectionId).isEqualTo(reader.id)
        assertThat(view.available.map { it.id }).containsExactly(reader.id)
        assertThat(audit.entries.last().operation).isEqualTo("PROJECT_JIRA_CONNECTIONS_UPDATE")
        assertThatThrownBy { service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value)))
            .updateJiraConnections(project.id, ProjectJiraConnections()) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `deleting a connection removes it from the projects that named it`() {
        val reader = service(admin).create(null, classic("Platform reader"))
        val writer = service(admin).create(null, classic("Platform writer").copy(capabilities = ConnectionFakes.WRITE))
        service(owner).updateJiraConnections(project.id, ProjectJiraConnections(reader.id, listOf(writer.id)))

        service(admin).delete(null, reader.id)

        assertThat(project.jiraConnections).isEqualTo(ProjectJiraConnections(null, listOf(writer.id)))
    }
}
