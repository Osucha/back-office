package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

class CiTokenServiceTest {

    private val project = Fixtures.project()
    private val store = mutableMapOf<ProjectCiTokenId, ProjectCiToken>()
    private val tokens = object : ProjectCiTokenRepository {
        override fun save(token: ProjectCiToken) = token.also { store[it.id] = it }
        override fun findById(id: ProjectCiTokenId) = store[id]
        override fun findByHash(tokenHash: String) = store.values.firstOrNull { it.tokenHash == tokenHash }
        override fun findByProjectId(projectId: ProjectId) = store.values.filter { it.projectId == projectId }
        override fun deleteByProjectId(projectId: ProjectId) { store.values.removeIf { it.projectId == projectId } }
    }
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val hasher = Fixtures.TestHasher()
    private val audit = RecordingAuditPort()
    private val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CI_MANAGE, projects = setOf(project.id.value))
    private val service = CiTokenService(tokens, projects, hasher, gate, audit, Clock.fixed(now))

    @Test
    fun `tokens are stored hashed, shown once, stamped with the creator and audited`() {
        val created = service.create(project.id, "gitlab")
        assertThat(created.plaintext).startsWith("bo_ci_")
        assertThat(created.token.tokenHash).isEqualTo(hasher.hash(created.plaintext)).hasSize(64)
        assertThat(created.token.prefix).isEqualTo(created.plaintext.take(12))
        assertThat(created.token.createdBy).isEqualTo(Fixtures.alice)
        assertThat(service.list(project.id).single().id).isEqualTo(created.token.id)
        assertThat(gate.checked).contains("project.ci.manage@${project.id}")
        assertThat(audit.entries.single().operation).isEqualTo("CI_TOKEN_CREATE")
        assertThat(audit.entries.single().projectId).isEqualTo(project.id.value)
        assertThat(audit.entries.single().details["name"]).isEqualTo("gitlab")
    }

    @Test
    fun `verification resolves the project, records usage and rejects unknown or revoked tokens`() {
        val created = service.create(project.id, "gitlab")
        val identity = service.verify(created.plaintext)!!
        assertThat(identity.projectId).isEqualTo(project.id.value)
        assertThat(identity.projectKey).isEqualTo(project.key)
        assertThat(identity.tokenId).isEqualTo(created.token.id.value)
        assertThat(identity.tokenName).isEqualTo("gitlab")
        assertThat(store[created.token.id]?.lastUsedAt).isEqualTo(now)
        assertThat(service.verify("bo_ci_" + "x".repeat(40))).isNull()
        assertThat(service.verify("not-a-token")).isNull()

        service.revoke(project.id, created.token.id)
        assertThat(store[created.token.id]?.revokedAt).isEqualTo(now)
        assertThat(service.verify(created.plaintext)).isNull()
        assertThat(audit.operations()).containsExactly("CI_TOKEN_CREATE", "CI_TOKEN_REVOKE")
        val other = Fixtures.project("OTHER")
        val otherGate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CI_MANAGE, projects = setOf(other.id.value))
        assertThatThrownBy { CiTokenService(tokens, projects, hasher, otherGate, audit, Clock.fixed(now)).revoke(other.id, created.token.id) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `managing tokens needs project ci manage on that project`() {
        val viewer = CiTokenService(tokens, projects, hasher, FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value)), audit, Clock.fixed(now))
        assertThatThrownBy { viewer.create(project.id, "gitlab") }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { viewer.list(project.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { viewer.revoke(project.id, ProjectCiTokenId.new()) }.isInstanceOf(InsufficientPermissionException::class.java)
        val elsewhere = CiTokenService(tokens, projects, hasher, FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_CI_MANAGE, projects = setOf(ProjectId.new().value)), audit, Clock.fixed(now))
        assertThatThrownBy { elsewhere.create(project.id, "gitlab") }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.projectId).isEqualTo(project.id.value) }
        assertThat(audit.entries).isEmpty()
    }
}
