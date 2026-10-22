package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections.ScmConnectionCommand
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.ScmProbePort
import pl.mos.backoffice.projects.application.port.out.ScmToken
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ScmCapability.CI_READ
import pl.mos.backoffice.projects.domain.model.ScmCapability.GIT_READ
import pl.mos.backoffice.projects.domain.model.ScmCapability.PUSH_BRANCH
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProbe
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.LocalDate

/** ADR 0051 step 1: repository tokens are sealed, tested on every save, never returned or audited, owners only. */
class ScmConnectionServiceTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val store = InMemoryScmConnections()
    private val sealer = ConnectionFakes.FakeSealer()
    private val audit = RecordingAuditPort()
    private val tokens = mutableListOf<ScmToken>()
    private var answer = ScmProbe(gitRead = true, gitPush = false, scopes = listOf("read_api", "read_repository"), accessLevel = 20,
        tokenExpiresAt = LocalDate.parse("2027-01-15"), defaultBranch = "main", defaultBranchProtected = true, mergeAccessLevel = 40)
    private val probe = ScmProbePort { _, _, token -> tokens += token; answer }

    private val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
    private val member = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
    private fun service(gate: FakeAuthorizationGate = owner) = ScmConnectionService(store, projects, sealer, probe, gate, audit, Clock.SYSTEM)

    private val token = "glpat-very-secret-read-token"
    private val repo = project.repositories.single().key
    private val read = ScmConnectionCommand(repo, "CI reader", setOf(GIT_READ, CI_READ), token)

    @Test
    fun `an owner adds a token, it is sealed, tested, keeps the token's expiry and appears nowhere in plain text`() {
        val view = service().create(project.id, read)
        val c = view.connection
        assertThat(view.repositoryExists).isTrue()
        assertThat(sealer.sealedPlaintexts).containsExactly(token)
        assertThat(c.secret.wrappedKey).isEqualTo("wrapped:scm-connection:${c.id}")
        assertThat(c.verification!!.status).isEqualTo(VerificationStatus.OK)
        assertThat(c.verifiedReadOnly).isTrue()
        assertThat(c.secretExpiresAt).isEqualTo(LocalDate.parse("2027-01-15"))
        assertThat(tokens.single()).isEqualTo(ScmToken.Plain(token))
        assertThat(audit.entries.single().operation).isEqualTo("SCM_CONNECTION_CREATE")
        assertThat(audit.entries.toString()).doesNotContain(token)
        assertThat(tokens.single().toString()).doesNotContain(token)
    }

    @Test
    fun `members see the list but only owners change it, and the repository must be the project's`() {
        service().create(project.id, read)
        assertThat(service(member).list(project.id)).hasSize(1)
        assertThatThrownBy { service(member).create(project.id, read.copy(name = "other")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(member).test(project.id, read) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service().create(project.id, read.copy(repositoryKey = "nope", name = "x")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("not a repository")
        assertThatThrownBy { service().create(project.id, read.copy(name = "ci READER")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("already used")
        assertThatThrownBy { service().create(project.id, read.copy(name = "no token", token = " ")) }.isInstanceOf(ValidationException::class.java)
        sealer.configured = false
        assertThatThrownBy { service().create(project.id, read.copy(name = "no key")) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `an update re-tests the stored token, a new token is a rotation, a test before saving stores nothing`() {
        val created = service().create(project.id, read).connection
        tokens.clear()
        val updated = service().update(project.id, created.id, read.copy(capabilities = setOf(GIT_READ, PUSH_BRANCH), token = null)).connection
        assertThat(tokens.single()).isInstanceOf(ScmToken.Stored::class.java)
        assertThat(updated.secret).isEqualTo(created.secret)
        assertThat(updated.verification!!.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(updated.verification!!.missing).containsExactly(PUSH_BRANCH)
        assertThat(audit.entries.last().operation).isEqualTo("SCM_CONNECTION_UPDATE")

        answer = answer.copy(gitPush = true, accessLevel = 30)
        val rotated = service().update(project.id, created.id, read.copy(capabilities = setOf(GIT_READ, PUSH_BRANCH), token = "glpat-new")).connection
        assertThat(rotated.verification!!.status).isEqualTo(VerificationStatus.OK)
        assertThat(audit.entries.last().operation).isEqualTo("SCM_CONNECTION_ROTATE")
        assertThat(audit.entries.last().details).containsEntry("token", "replaced")

        val before = store.findAll().size
        val tested = service().test(project.id, read.copy(name = "draft"))
        assertThat(tested.granted).contains(GIT_READ, CI_READ)
        assertThat(store.findAll()).hasSize(before)
        assertThat(audit.entries.last().operation).isEqualTo("SCM_CONNECTION_TEST")
    }

    @Test
    fun `a connection of a removed repository is shown as orphaned and cannot be verified, delete and rewrap work`() {
        val created = service().create(project.id, read).connection
        every { projects.findById(project.id) } returns project.copy(repositories = emptyList())
        assertThat(service().list(project.id).single().repositoryExists).isFalse()
        assertThatThrownBy { service().verify(project.id, created.id) }.isInstanceOf(InvalidStateException::class.java)

        sealer.keyId = "k2"
        assertThat(service().rewrapScmSecrets()).isEqualTo(1)
        assertThat(store.findById(created.id)!!.secret.keyId).isEqualTo("k2")

        service().delete(project.id, created.id)
        assertThat(store.findAll()).isEmpty()
        assertThat(audit.entries.last().operation).isEqualTo("SCM_CONNECTION_DELETE")
    }

    private class InMemoryScmConnections : ScmConnectionRepository {
        private val rows = linkedMapOf<ScmConnectionId, ScmConnection>()
        override fun save(connection: ScmConnection) = connection.also { rows[it.id] = it }
        override fun findById(id: ScmConnectionId) = rows[id]
        override fun findByProject(projectId: ProjectId) = rows.values.filter { it.projectId == projectId }
        override fun findAll() = rows.values.toList()
        override fun delete(id: ScmConnectionId) { rows.remove(id) }
        override fun replaceSecret(id: ScmConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean {
            val row = rows[id]?.takeIf { it.secret == expected } ?: return false
            rows[id] = row.copy(secret = replacement)
            return true
        }
    }
}
