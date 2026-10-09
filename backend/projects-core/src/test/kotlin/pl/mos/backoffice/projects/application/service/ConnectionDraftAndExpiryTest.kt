package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections.ConnectionCommand
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.event.ConnectionExpiring
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.JiraPermission.ADD_COMMENTS
import pl.mos.backoffice.projects.domain.model.JiraPermission.BROWSE_PROJECTS
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.LocalDate

/** ADR 0030 follow-ups: "Test before saving" (nothing stored) and token expiry reminders. */
class ConnectionDraftAndExpiryTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val connections = ConnectionFakes.InMemoryConnections()
    private val sealer = ConnectionFakes.FakeSealer()
    private val trackers = ConnectionFakes.FakeTrackers(granted = mapOf("SHOP" to setOf(BROWSE_PROJECTS, ADD_COMMENTS)))
    private val audit = RecordingAuditPort()
    private val events = mutableListOf<DomainEvent>()
    private val today = LocalDate.parse("2026-09-26")

    private fun service(gate: FakeAuthorizationGate) = ConnectionService(
        connections, projects, LegacyConnectionSource.NONE, sealer, trackers, ConnectionSelector(), ConnectionVerificationPolicy(), gate, audit,
        Clock.fixed(Fixtures.now), { events += it },
    )

    private val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
    private val admin = FakeAuthorizationGate.permitting(ProjectsPermissions.CONNECTIONS_MANAGE, ProjectsPermissions.INTEGRATIONS_VIEW)
    private val secret = "ATATT3x-draft-token"

    private fun draft(token: String? = secret) = ConnectionCommand(
        type = ConnectionType.JIRA, name = "Jira reader", siteUrl = "https://acme.atlassian.net", accountEmail = "reader@acme.com", secret = token,
        capabilities = READ, allowedProjectKeys = listOf("SHOP"),
    )

    @Test
    fun `a draft is verified without storing anything and audited without the secret`() {
        val result = service(owner).test(project.id, draft(), null, null)

        assertThat(result.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(result.excess).containsExactly(ADD_COMMENTS)
        assertThat(connections.rows).isEmpty()
        assertThat(trackers.probed).containsExactly(listOf("SHOP"))
        assertThat(audit.operations()).containsExactly("CONNECTION_TEST")
        assertThat(audit.entries.single().details).containsEntry("secret", "draft")
        assertThat(audit.entries.joinToString { it.details.toString() }).doesNotContain(secret)
    }

    @Test
    fun `testing an edit without a new token uses the stored one, a new draft needs a token and the manage permission`() {
        val stored = connections.save(ConnectionFakes.connection("reader", READ, projectId = project.id))
        service(owner).test(project.id, draft(token = null), stored.id, "SHOP")
        assertThat(sealer.sealedPlaintexts).isEmpty()
        assertThat(audit.entries.single().details).containsEntry("secret", "stored")

        assertThatThrownBy { service(owner).test(project.id, draft(token = null), null, null) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(admin).test(project.id, draft(), null, null) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(service(admin).test(null, draft(), null, null).status).isEqualTo(VerificationStatus.WARNING)
    }

    @Test
    fun `tokens expiring within 14 days are reminded once per expiry date`() {
        val soon = connections.save(ConnectionFakes.connection("soon", READ).copy(secretExpiresAt = today.plusDays(10)))
        connections.save(ConnectionFakes.connection("later", READ).copy(secretExpiresAt = today.plusDays(20)))
        val expired = connections.save(ConnectionFakes.connection("expired", READ, projectId = project.id).copy(secretExpiresAt = today.minusDays(1)))
        connections.save(ConnectionFakes.connection("none", READ))

        assertThat(service(admin).remindExpiring()).isEqualTo(2)
        val reminders = events.filterIsInstance<ConnectionExpiring>()
        assertThat(reminders.map { it.name to it.daysLeft }).containsExactlyInAnyOrder("soon" to 10L, "expired" to -1L)
        assertThat(reminders.single { it.name == "expired" }.payload).containsEntry("projectId", project.id.toString())
        assertThat(audit.operations()).containsOnly("CONNECTION_EXPIRY_REMINDER")

        assertThat(service(admin).remindExpiring()).isZero()
        // a rotated token with a new expiry date is reminded again when it comes close
        connections.save(connections.rows[soon.id]!!.copy(secretExpiresAt = today.plusDays(12)))
        assertThat(service(admin).remindExpiring()).isEqualTo(1)
        assertThat(connections.rows[expired.id]!!.expiryRemindedFor).isEqualTo(today.minusDays(1))
    }

    @Test
    fun `the expiring list only shows connections the caller may manage`() {
        connections.save(ConnectionFakes.connection("platform soon", READ).copy(secretExpiresAt = today.plusDays(3)))
        connections.save(ConnectionFakes.connection("project soon", READ, projectId = project.id).copy(secretExpiresAt = today.plusDays(5)))
        connections.save(ConnectionFakes.connection("far", READ).copy(secretExpiresAt = today.plusDays(40)))

        assertThat(service(admin).expiring().map { it.name }).containsExactly("platform soon")
        assertThat(service(owner).expiring().map { it.name }).containsExactly("project soon")
        assertThat(service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))).expiring()).isEmpty()
    }
}
