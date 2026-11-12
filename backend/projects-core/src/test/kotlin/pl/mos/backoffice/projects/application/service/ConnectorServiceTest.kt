package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectorFakes
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageConnectors.ConnectorCommand
import pl.mos.backoffice.projects.application.port.out.McpProbe
import pl.mos.backoffice.projects.application.port.out.McpProbePort
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.ScmProbePort
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProbe
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

/** ADR 0064: connectors: scopes and permissions, write-only secrets, audit, the test from Back Office. */
class ConnectorServiceTest {

    private val project = Fixtures.project()
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val store = ConnectorFakes.InMemoryConnectors()
    private val sealer = ConnectionFakes.FakeSealer()
    private val audit = RecordingAuditPort()
    private var mcpAnswer = McpProbe(true, "MCP server docs answered; 2 tool(s)", listOf("search", "fetch"))
    private val probed = mutableListOf<Connector>()
    private val mcp = McpProbePort { probed += it; mcpAnswer }
    private val scm = InMemoryScm()
    private var scmAnswer = ScmProbe(gitRead = true, gitPush = false, scopes = listOf("read_api", "read_repository"), accessLevel = 20, defaultBranch = "main", defaultBranchProtected = true)
    private val scmProbe = ScmProbePort { _, _, _ -> scmAnswer }

    private val admin = FakeAuthorizationGate.permitting(ProjectsPermissions.LIBRARY_MANAGE, ProjectsPermissions.LIBRARY_VIEW)
    private val owner = FakeAuthorizationGate.permitting(
        ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, ProjectsPermissions.PROJECT_VIEW, ProjectsPermissions.LIBRARY_VIEW, projects = setOf(project.id.value),
    )
    private val member = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, ProjectsPermissions.LIBRARY_VIEW, projects = setOf(project.id.value))
    private fun service(gate: FakeAuthorizationGate = admin) = ConnectorService(store, projects, sealer, mcp, scm, scmProbe, gate, audit, Clock.SYSTEM)

    private val secret = "tkn-very-secret-mcp-key"
    private val docs = ConnectorCommand(
        name = "Docs search", description = "Search the handbook", kind = ConnectorKind.MCP_HTTP, projectId = null,
        url = "https://mcp.example.com/docs", auth = ConnectorAuth.BEARER, secret = secret,
    )
    private val gitlab = ConnectorCommand(name = "GitLab read", kind = ConnectorKind.GITLAB_READ, projectId = project.id)

    @Test
    fun `an administrator adds a platform MCP connector - key from the name, secret sealed for the connector, audited without it`() {
        val c = service().create(docs)
        assertThat(c.key).isEqualTo("docs-search")
        assertThat(c.platform).isTrue()
        assertThat(c.host).isEqualTo("mcp.example.com")
        assertThat(sealer.sealedPlaintexts).containsExactly(secret)
        assertThat(c.secret!!.wrappedKey).isEqualTo("wrapped:connector:${c.id}")
        assertThat(c.hasSecret).isTrue()
        assertThat(c.updatedBy.displayName).isEqualTo("Alice Test")
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("CONNECTOR_CREATE")
        assertThat(entry.details).containsEntry("key", "docs-search").containsEntry("hasSecret", true).containsEntry("scope", "PLATFORM")
        assertThat(audit.entries.toString()).doesNotContain(secret)
        assertThat(c.toString()).doesNotContain(secret)

        // the same name again gets the next free key; an explicit key that exists is refused
        assertThat(service().create(docs).key).isEqualTo("docs-search-2")
        assertThatThrownBy { service().create(docs.copy(key = "docs-search")) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `platform connectors need library manage, project ones project connections manage, listing needs view rights`() {
        assertThatThrownBy { service(owner).create(docs) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(admin).create(gitlab) }.isInstanceOf(InsufficientPermissionException::class.java)
        val platform = service(admin).create(docs.copy(name = "Docs"))
        val own = service(owner).create(gitlab)
        assertThat(own.key).isEqualTo("gitlab-read")
        assertThat(own.repository).isNull()
        assertThatThrownBy { service(member).create(gitlab.copy(name = "Other")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(owner).update(platform.id, docs.copy(name = "Renamed")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(owner).delete(platform.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(member).test(own.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        assertThat(service(admin).list(null).map { it.key }).containsExactly("docs")
        assertThat(service(member).list(project.id).map { it.key }).describedAs("platform first, then the project's").containsExactly("docs", "gitlab-read")
        val outsider = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        assertThat(service(outsider).list(project.id).map { it.key }).describedAs("without library.view only the project's").containsExactly("gitlab-read")
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).list(null) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(owner).list(ProjectId.new()) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `an omitted secret keeps the stored one, an empty one removes it, a new one replaces it, key and scope stay`() {
        val c = service().create(docs)
        val kept = service().update(c.id, docs.copy(secret = null, name = "Docs v2"))
        assertThat(kept.secret).isEqualTo(c.secret)
        assertThat(kept.name).isEqualTo("Docs v2")
        assertThat(audit.entries.last().details).containsEntry("key", c.key).doesNotContainKey("secret")

        val replaced = service().update(c.id, docs.copy(secret = "tkn-new"))
        assertThat(replaced.secret).isNotEqualTo(c.secret)
        assertThat(audit.entries.last().details).containsEntry("secret", "replaced")

        val removed = service().update(c.id, docs.copy(secret = ""))
        assertThat(removed.hasSecret).isFalse()
        assertThat(audit.entries.last().operation).isEqualTo("CONNECTOR_UPDATE")
        assertThat(audit.entries.last().details).containsEntry("secret", "removed")

        val set = service().update(c.id, docs.copy(secret = "tkn-again"))
        assertThat(audit.entries.last().details).containsEntry("secret", "set")
        val none = service().update(c.id, docs.copy(auth = ConnectorAuth.NONE, secret = null))
        assertThat(none.hasSecret).describedAs("auth NONE keeps no secret").isFalse()
        assertThat(set.hasSecret).isTrue()

        assertThatThrownBy { service().update(c.id, docs.copy(key = "other")) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("key")
        assertThatThrownBy { service().update(c.id, docs.copy(projectId = project.id)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("scope")
        assertThatThrownBy { service().update(pl.mos.backoffice.projects.domain.model.ConnectorId.new(), docs) }.isInstanceOf(NotFoundException::class.java)
        assertThat(audit.entries.toString()).doesNotContain(secret).doesNotContain("tkn-new").doesNotContain("tkn-again")
    }

    @Test
    fun `invalid settings are refused`() {
        assertThatThrownBy { service().create(docs.copy(url = "ftp://x")) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("http")
        assertThatThrownBy { service().create(docs.copy(url = null)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service().create(docs.copy(auth = ConnectorAuth.HEADER)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("HEADER")
        assertThatThrownBy { service().create(docs.copy(auth = ConnectorAuth.HEADER, headerName = "Host")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service().create(docs.copy(auth = ConnectorAuth.NONE)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("NONE")
        assertThatThrownBy { service().create(docs.copy(secret = "two\nlines")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service().create(docs.copy(repository = "app")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(admin).create(gitlab.copy(projectId = null)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("project")
        assertThatThrownBy { service(owner).create(gitlab.copy(repository = "nope")) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("repository")
        assertThatThrownBy { service(owner).create(gitlab.copy(secret = "x")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(owner).create(gitlab.copy(url = "https://gitlab.example.com")) }.isInstanceOf(ValidationException::class.java)
        sealer.configured = false
        assertThatThrownBy { service().create(docs) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("BACKOFFICE_SECRETS_KEY")
        val header = service().create(docs.copy(auth = ConnectorAuth.HEADER, headerName = "X-Api-Key", secret = null))
        assertThat(header.headerName).isEqualTo("X-Api-Key")
        assertThat(service(owner).create(gitlab.copy(name = "Docs repo", repository = project.repositories.single().key)).repository).isEqualTo(project.repositories.single().key)
    }

    @Test
    fun `the MCP test runs the probe and is kept on the connector, without a needed secret it fails without calling`() {
        val c = service().create(docs)
        val result = service().test(c.id)
        assertThat(result.ok).isTrue()
        assertThat(result.tools).containsExactly("search", "fetch")
        assertThat(probed.single().id).isEqualTo(c.id)
        assertThat(store.findById(c.id)!!.lastTest).isEqualTo(result)
        assertThat(audit.entries.last().operation).isEqualTo("CONNECTOR_TEST")
        assertThat(audit.entries.last().details).containsEntry("ok", true).containsEntry("tools", 2)

        mcpAnswer = McpProbe(false, "mcp.example.com refused the credentials (HTTP 401) to initialize")
        assertThat(service().test(c.id).ok).isFalse()
        // a changed URL drops the stale result
        assertThat(service().update(c.id, docs.copy(url = "https://mcp.example.com/v2", secret = null)).lastTest).isNull()

        val noSecret = service().update(c.id, docs.copy(secret = ""))
        val failed = service().test(noSecret.id)
        assertThat(failed.ok).isFalse()
        assertThat(failed.message).contains("needs a secret")
        assertThat(probed).hasSize(2)
    }

    @Test
    fun `the GITLAB_READ test verifies the read-only CI_READ token of the repository again`() {
        val c = service(owner).create(gitlab)
        val missing = service(owner).test(c.id)
        assertThat(missing.ok).isFalse()
        assertThat(missing.message).contains("no verified read-only token with CI_READ")

        val repo = project.repositories.single().key
        scm.rows += token("reader", setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ), repo)
        val ok = service(owner).test(c.id)
        assertThat(ok.ok).isTrue()
        assertThat(ok.message).contains("reader").contains("read-only")
        assertThat(scm.saved).isNotEmpty()

        // the token can do more than declared: no longer verified read-only
        scmAnswer = scmAnswer.copy(gitPush = true, defaultBranchProtected = false)
        val excess = service(owner).test(c.id)
        assertThat(excess.ok).isFalse()
        assertThat(excess.message).contains("git push")

        val elsewhere = service(owner).create(gitlab.copy(name = "Other repo", repository = repo))
        scm.rows.clear()
        assertThat(service(owner).test(elsewhere.id).message).contains("'$repo'")
    }

    @Test
    fun `delete is audited, rewrap re-seals stored secrets with the active key`() {
        val c = service().create(docs)
        sealer.keyId = "k2"
        assertThat(service().rewrapConnectorSecrets()).isEqualTo(1)
        assertThat(store.findById(c.id)!!.secret!!.keyId).isEqualTo("k2")
        assertThat(audit.entries.last().operation).isEqualTo("CONNECTOR_SECRET_REWRAP")
        assertThat(service().rewrapConnectorSecrets()).isZero()
        service().delete(c.id)
        assertThat(store.findAll()).isEmpty()
        assertThat(audit.entries.last().operation).isEqualTo("CONNECTOR_DELETE")
        sealer.configured = false
        assertThat(service().rewrapConnectorSecrets()).isZero()
    }

    private fun token(name: String, caps: Set<ScmCapability>, repo: String) = ScmConnection(
        ScmConnectionId.new(), project.id, repo, name, ScmProvider.GITLAB, caps, SealedSecret("k1", "w", "c"), null, null,
        Fixtures.now, Actor.SYSTEM, Fixtures.now, Actor.SYSTEM,
    )

    private class InMemoryScm : ScmConnectionRepository {
        val rows = mutableListOf<ScmConnection>()
        val saved = mutableListOf<ScmConnection>()
        override fun save(connection: ScmConnection): ScmConnection {
            saved += connection
            rows.replaceAll { if (it.id == connection.id) connection else it }
            return connection
        }
        override fun findById(id: ScmConnectionId) = rows.firstOrNull { it.id == id }
        override fun findByProject(projectId: ProjectId) = rows.filter { it.projectId == projectId }
        override fun findAll() = rows.toList()
        override fun delete(id: ScmConnectionId) { rows.removeIf { it.id == id } }
        override fun replaceSecret(id: ScmConnectionId, expected: SealedSecret, replacement: SealedSecret) = false
    }
}
