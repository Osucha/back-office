package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectorFakes
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.service.ConnectorCatalogService
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.RunnerConnector
import pl.mos.backoffice.shared.security.Actor

/** ADR 0064: which connectors a job gets, and the connector's own rules. */
class ConnectorResolutionTest {

    private val project = ProjectId.new()
    private val repos = listOf(ConnectorResolution.Repository("app", primary = true), ConnectorResolution.Repository("docs", primary = false))

    private fun token(name: String, repo: String, caps: Set<ScmCapability>, powers: List<String> = emptyList()) = ScmConnection(
        ScmConnectionId.new(), project, repo, name, ScmProvider.GITLAB, caps, SealedSecret("k1", "w", "c"), null,
        ScmVerification(VerificationStatus.OK, Fixtures.now, null, null, null, null, caps, emptySet(), powers, emptyList()),
        Fixtures.now, Actor.SYSTEM, Fixtures.now, Actor.SYSTEM,
    )

    @Test
    fun `project connectors shadow platform ones, missing keys are skipped with a note, secrets are named not carried`() {
        val platformDocs = ConnectorFakes.mcp("docs")
        val projectDocs = ConnectorFakes.mcp("docs", projectId = project, url = "https://internal.example.com/mcp", auth = ConnectorAuth.HEADER, headerName = "X-Key")
        val jira = ConnectorFakes.mcp("jira", auth = ConnectorAuth.BEARER)
        val result = ConnectorResolution.resolve(listOf("docs", "jira", "ghost", "docs"), listOf(projectDocs), listOf(platformDocs, jira), repos, emptyList())

        assertThat(result.failure).isNull()
        assertThat(result.connectors.connectors).containsExactly(
            RunnerConnector("docs", ConnectorKind.MCP_HTTP, "https://internal.example.com/mcp", ConnectorAuth.HEADER, "X-Key"),
            RunnerConnector("jira", ConnectorKind.MCP_HTTP, "https://mcp.example.com/jira", ConnectorAuth.BEARER),
        )
        assertThat(result.connectors.credentials).containsExactly(
            JobCredentialRef(projectDocs.id.toString(), "", "CONNECTOR", "docs"),
            JobCredentialRef(jira.id.toString(), "", "CONNECTOR", "jira"),
        )
        assertThat(result.connectors.notes).anyMatch { it.contains("ghost") && it.contains("skipped") }
        assertThat(result.connectors.notes).anyMatch { it.contains("internal.example.com") && it.contains("(project)") }
        assertThat(result.connectors.toString()).doesNotContain("cipher-")

        val secretless = ConnectorFakes.mcp("jira", auth = ConnectorAuth.BEARER, secret = null)
        val skipped = ConnectorResolution.resolve(listOf("jira"), emptyList(), listOf(secretless), repos, emptyList())
        assertThat(skipped.connectors.connectors).isEmpty()
        assertThat(skipped.connectors.notes.single()).contains("has no secret")
    }

    @Test
    fun `GITLAB_READ takes the verified read-only CI_READ token of its repository, skips repositories the step lacks, fails without a token`() {
        val reader = token("reader", "app", setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ))
        val writer = token("writer", "app", setOf(ScmCapability.CI_READ, ScmCapability.MR_COMMENT), listOf("api scope"))
        val docsReader = token("docs-reader", "docs", setOf(ScmCapability.CI_READ))
        val primary = ConnectorFakes.gitlabRead(project)
        val docs = ConnectorFakes.gitlabRead(project, key = "gitlab-docs", repository = "docs")
        val result = ConnectorResolution.resolve(listOf("gitlab-read", "gitlab-docs"), listOf(primary, docs), emptyList(), repos, listOf(writer, reader, docsReader))
        assertThat(result.connectors.connectors).containsExactly(
            RunnerConnector("gitlab-read", ConnectorKind.GITLAB_READ, repository = "app"),
            RunnerConnector("gitlab-docs", ConnectorKind.GITLAB_READ, repository = "docs"),
        )
        assertThat(result.connectors.credentials).containsExactly(
            JobCredentialRef(reader.id.toString(), "app", "CI_READ"),
            JobCredentialRef(docsReader.id.toString(), "docs", "CI_READ"),
        )

        val notInStep = ConnectorResolution.resolve(listOf("gitlab-docs"), listOf(docs), emptyList(), repos.take(1), listOf(docsReader))
        assertThat(notInStep.connectors.connectors).isEmpty()
        assertThat(notInStep.connectors.notes.single()).contains("not a source of this step")
        val noRepository = ConnectorResolution.resolve(listOf("gitlab-read"), listOf(primary), emptyList(), emptyList(), listOf(reader))
        assertThat(noRepository.failure).isNull()
        assertThat(noRepository.connectors.connectors).isEmpty()

        val refused = ConnectorResolution.resolve(listOf("gitlab-read"), listOf(primary), emptyList(), repos, listOf(writer))
        assertThat(refused.failure).contains("read-only token with CI_READ for repository 'app'")
    }

    @Test
    fun `the catalog for benchmarks gives platform MCP connectors only`() {
        val store = ConnectorFakes.InMemoryConnectors(ConnectorFakes.mcp("docs", auth = ConnectorAuth.BEARER), ConnectorFakes.gitlabRead(project))
        val catalog = ConnectorCatalogService(store)
        val platform = catalog.mcpConnectors(listOf("docs", "gitlab-read"), null)
        assertThat(platform.connectors.map { it.key }).containsExactly("docs")
        assertThat(platform.credentials.single().capability).isEqualTo("CONNECTOR")
        assertThat(platform.notes).anyMatch { it.contains("gitlab-read") }
        val inProject = catalog.mcpConnectors(listOf("docs", "gitlab-read"), project.value)
        assertThat(inProject.connectors.map { it.key }).containsExactly("docs")
        assertThat(inProject.notes).anyMatch { it.contains("gitlab-read (GITLAB_READ)") }
        assertThat(catalog.mcpConnectors(emptyList(), null).connectors).isEmpty()
    }

    @Test
    fun `keys come from names and the URL and header rules hold`() {
        assertThat(Connector.keyFromName("Jira Cloud (prod)")).isEqualTo("jira-cloud-prod")
        assertThat(Connector.keyFromName("Łódź docs")).isEqualTo("lodz-docs")
        assertThat(Connector.keyFromName("2FA server")).isEqualTo("connector-2fa-server")
        assertThat(Connector.keyFromName("!!!")).isEqualTo("connector")
        assertThat(Connector.keyFromName("x".repeat(80)).length).isLessThanOrEqualTo(36)
        assertThat(Connector.uniqueKey("docs") { it in setOf("docs", "docs-2") }).isEqualTo("docs-3")
        assertThat(ConnectorFakes.mcp("docs").host).isEqualTo("mcp.example.com")
        assertThatThrownBy { ConnectorFakes.mcp("docs", url = "https://") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.mcp("docs", url = "https://a.example/" + "x".repeat(600)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.mcp("Docs") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.mcp("docs", headerName = "X-Key") }.describedAs("header only for HEADER").isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.mcp("docs", auth = ConnectorAuth.HEADER, headerName = "bad header") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.gitlabRead(project, repository = " ") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ConnectorFakes.mcp("docs").copy(name = " ") }.isInstanceOf(ValidationException::class.java)
    }
}
