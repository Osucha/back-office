package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.ALL
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.alice
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections.ConnectionCommand
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_READ
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.ExpiryStatus
import pl.mos.backoffice.projects.domain.model.JiraPermission.ADD_COMMENTS
import pl.mos.backoffice.projects.domain.model.JiraPermission.BROWSE_PROJECTS
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.projects.domain.service.SelectionStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.LocalDate

/** ADR 0030: the connection registry use cases (permissions, sealing, verification on save, audit, legacy). */
class ConnectionServiceTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val connections = ConnectionFakes.InMemoryConnections()
    private val sealer = ConnectionFakes.FakeSealer()
    private val trackers = ConnectionFakes.FakeTrackers(granted = mapOf("SHOP" to setOf(BROWSE_PROJECTS)))
    private val audit = RecordingAuditPort()
    private var legacy = LegacyConnectionSource.NONE

    private fun service(gate: FakeAuthorizationGate) = ConnectionService(
        connections, projects, { legacy.legacy() }, sealer, trackers, ConnectionSelector(), ConnectionVerificationPolicy(), gate, audit, Clock.fixed(Fixtures.now),
    )

    private val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
    private val admin = FakeAuthorizationGate.permitting(ProjectsPermissions.CONNECTIONS_MANAGE, ProjectsPermissions.INTEGRATIONS_VIEW)

    private val secret = "ATATT3xFfGF0-very-secret-token"

    private fun command(name: String = "Jira reader", capabilities: Set<pl.mos.backoffice.projects.domain.model.ConnectionCapability> = READ, token: String? = secret) = ConnectionCommand(
        type = ConnectionType.JIRA, name = name, siteUrl = "https://acme.atlassian.net/", accountEmail = "reader@acme.com", secret = token,
        capabilities = capabilities, allowedProjectKeys = listOf("shop"), secretExpiresAt = LocalDate.parse("2026-10-20"),
        acknowledgeClassicToken = true,
    )

    @Test
    fun `an owner creates a project connection that is sealed, verified on save and audited without the secret`() {
        val created = service(owner).create(project.id, command())

        assertThat(owner.checked.first()).isEqualTo("project.connections.manage@${project.id}")
        assertThat(created.scope).isEqualTo(ConnectionScope.PROJECT)
        assertThat(created.siteUrl).isEqualTo("https://acme.atlassian.net")
        assertThat(created.allowedProjectKeys).containsExactly("SHOP")
        assertThat(created.createdBy).isEqualTo(alice)
        assertThat(created.secret!!.keyId).isEqualTo("k1")
        assertThat(created.secret.toString()).doesNotContain(secret)
        assertThat(sealer.sealedPlaintexts).containsExactly(secret)
        assertThat(trackers.probed).containsExactly(listOf("SHOP"))
        assertThat(created.verification!!.status).isEqualTo(VerificationStatus.OK)
        assertThat(created.expiryStatus(Fixtures.now)).isEqualTo(ExpiryStatus.EXPIRING_SOON)
        assertThat(connections.rows[created.id]!!.verification).isNotNull()

        assertThat(audit.operations()).containsExactly("CONNECTION_CREATE", "CONNECTION_VERIFY")
        assertThat(audit.entries.map { it.projectId }).containsOnly(project.id.value)
        assertThat(audit.entries.joinToString { it.details.toString() }).doesNotContain(secret)
        assertThat(audit.entries[0].details["capabilities"]).isEqualTo(listOf("ISSUE_READ"))
    }

    @Test
    fun `project connections need project connections manage, platform ones admin connections manage`() {
        val maintainer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        assertThatThrownBy { service(maintainer).create(project.id, command()) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(owner).create(null, command()) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(admin).create(project.id, command()) }.isInstanceOf(InsufficientPermissionException::class.java)

        val platform = service(admin).create(null, command("platform writer", setOf(ISSUE_COMMENT, ISSUE_READ)))
        assertThat(platform.scope).isEqualTo(ConnectionScope.PLATFORM)
        assertThat(admin.checked.last()).isEqualTo("admin.connections.manage")

        // a viewer lists; a project connection is not reachable through the platform endpoints and vice versa
        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        val own = service(owner).create(project.id, command())
        assertThat(service(viewer).listProject(project.id)).containsExactly(own)
        assertThatThrownBy { service(admin).get(null, own.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(owner).get(project.id, platform.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(viewer).verify(project.id, own.id, null) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `secrets are required on create, kept on update and replaced by rotation`() {
        assertThatThrownBy { service(owner).create(project.id, command(token = " ")) }.isInstanceOf(ValidationException::class.java)
        val created = service(owner).create(project.id, command())
        val sealedBefore = created.secret

        val updated = service(owner).update(project.id, created.id, command(name = "Jira reader SHOP", token = null))
        assertThat(updated.secret).isEqualTo(sealedBefore)
        assertThat(updated.name).isEqualTo("Jira reader SHOP")

        val rotated = service(owner).rotateSecret(project.id, created.id, "new-token-value", LocalDate.parse("2027-10-01"))
        assertThat(rotated.secret).isNotEqualTo(sealedBefore)
        assertThat(rotated.secretRotatedAt).isEqualTo(Fixtures.now)
        assertThat(rotated.expiryStatus(Fixtures.now)).isEqualTo(ExpiryStatus.OK)

        assertThat(audit.operations()).containsSubsequence("CONNECTION_CREATE", "CONNECTION_UPDATE", "CONNECTION_ROTATE")
        val update = audit.entries.first { it.operation == "CONNECTION_UPDATE" }
        assertThat(update.details["name"]).isEqualTo(mapOf("old" to "Jira reader", "new" to "Jira reader SHOP"))
        assertThat(update.details).doesNotContainKey("secret")
        assertThat(audit.entries.joinToString { it.details.toString() }).doesNotContain(secret).doesNotContain("new-token-value")

        service(owner).delete(project.id, created.id)
        assertThat(connections.rows).isEmpty()
        assertThat(audit.operations().last()).isEqualTo("CONNECTION_DELETE")
    }

    @Test
    fun `names are unique per scope and the key must be configured to store secrets`() {
        service(owner).create(project.id, command())
        assertThatThrownBy { service(owner).create(project.id, command(name = "jira READER")) }.isInstanceOf(ValidationException::class.java)
        service(admin).create(null, command())

        sealer.configured = false
        assertThatThrownBy { service(owner).create(project.id, command(name = "other")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("BACKOFFICE_SECRETS_KEY")
    }

    @Test
    fun `verification reports excess permissions and stores the result`() {
        val created = service(owner).create(project.id, command())
        trackers.granted = mapOf("SHOP" to setOf(BROWSE_PROJECTS, ADD_COMMENTS))

        val verified = service(owner).verify(project.id, created.id, null)
        assertThat(verified.verification!!.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(verified.verification!!.excess).containsExactly(ADD_COMMENTS)
        assertThat(connections.rows[created.id]!!.verification!!.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(audit.entries.last().details["excess"]).isEqualTo(listOf("ADD_COMMENTS"))
    }

    @Test
    fun `the legacy credential is listed last, read-only, verifiable but not stored`() {
        legacy = LegacyConnectionSource { ConnectionFakes.connection("Jira (environment variables)", ALL, legacy = true) }
        val platform = service(admin).create(null, command())

        assertThat(service(admin).listPlatform().map { it.id }).containsExactly(platform.id, ConnectionId.LEGACY_JIRA)
        assertThatThrownBy { service(admin).update(null, ConnectionId.LEGACY_JIRA, command()) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { service(admin).delete(null, ConnectionId.LEGACY_JIRA) }.isInstanceOf(InvalidStateException::class.java)

        val tested = service(admin).verify(null, ConnectionId.LEGACY_JIRA, "SHOP")
        // the account only browses SHOP, the legacy credential declares every capability -> missing permissions
        assertThat(tested.verification!!.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(trackers.probed.last()).containsExactly("SHOP")
        assertThat(connections.rows).doesNotContainKey(ConnectionId.LEGACY_JIRA)
    }

    @Test
    fun `resolve explains which connection a step would use`() {
        legacy = LegacyConnectionSource { ConnectionFakes.connection("Jira (environment variables)", ALL, legacy = true) }
        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))

        val legacyOnly = service(viewer).resolve(project.id, setOf(ISSUE_READ), null, null)
        assertThat(legacyOnly.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)
        assertThat(legacyOnly.connection!!.legacy).isTrue()

        val reader = service(owner).create(project.id, command())
        val resolved = service(viewer).resolve(project.id, setOf(ISSUE_READ), null, null)
        assertThat(resolved.status).isEqualTo(SelectionStatus.OK)
        assertThat(resolved.connection!!.id).isEqualTo(reader.id)
        assertThat(service(viewer).usable(project.id).map { it.name }).containsExactly("Jira reader", "Jira (environment variables)")
    }

    @Test
    fun `secrets sealed with an old key are re-wrapped to the active one`() {
        val created = service(owner).create(project.id, command())
        sealer.keyId = "k2"
        audit.entries.clear()

        assertThat(service(owner).rewrapSecrets()).isEqualTo(1)
        assertThat(connections.rows[created.id]!!.secret).isEqualTo(SealedSecret("k2", "rewrapped:connection:${created.id}", created.secret!!.ciphertext))
        assertThat(audit.entries.single().operation).isEqualTo("CONNECTION_SECRET_REWRAP")
        assertThat(audit.entries.single().actor!!.kind).isEqualTo(PrincipalKind.SYSTEM)
        assertThat(service(owner).rewrapSecrets()).isZero()
    }
}
