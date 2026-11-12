package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageConnectors
import pl.mos.backoffice.projects.application.port.`in`.ManageConnectors.ConnectorCommand
import pl.mos.backoffice.projects.application.port.`in`.RewrapConnectorSecrets
import pl.mos.backoffice.projects.application.port.out.ConnectorRepository
import pl.mos.backoffice.projects.application.port.out.McpProbePort
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.ScmProbePort
import pl.mos.backoffice.projects.application.port.out.ScmToken
import pl.mos.backoffice.projects.application.port.out.SecretSealer
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ConnectorTest
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnectionSelector
import pl.mos.backoffice.projects.domain.model.ScmVerificationPolicy
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * ADR 0064: connectors, the tools agents use. The secret of an MCP connector is sealed before it is stored and never
 * returned; the test runs from Back Office (not the sandbox) and its result is kept on the connector. A `GITLAB_READ`
 * connector has no secret: its test verifies the project's read-only `CI_READ` token of its repository again (ADR 0051).
 */
class ConnectorService(
    private val connectors: ConnectorRepository,
    private val projects: ProjectRepository,
    private val sealer: SecretSealer,
    private val mcp: McpProbePort,
    private val scmConnections: ScmConnectionRepository,
    private val scmProbe: ScmProbePort,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageConnectors, RewrapConnectorSecrets {

    override fun list(projectId: ProjectId?): List<Connector> {
        val user = if (projectId == null) gate.require(ProjectsPermissions.LIBRARY_VIEW) else gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val platform = if (projectId == null || user.can(ProjectsPermissions.LIBRARY_VIEW)) connectors.findPlatform() else emptyList()
        val own = projectId?.let { loadProject(it); connectors.findByProject(it) }.orEmpty()
        return (platform.sortedBy { it.key } + own.sortedBy { it.key })
    }

    override fun create(command: ConnectorCommand): Connector {
        val actor = requireManage(command.projectId).actor
        val project = command.projectId?.let(::loadProject)
        val siblings = siblings(command.projectId)
        val key = command.key?.trim()?.takeIf { it.isNotEmpty() }
            ?.also { k -> if (siblings.any { it.key == k }) throw InvalidStateException("connector $k already exists in ${scopeName(command.projectId)}") }
            ?: Connector.keyFromName(command.name).let { base -> Connector.uniqueKey(base) { k -> siblings.any { it.key == k } } }
        val id = ConnectorId.new()
        val secret = command.secret?.takeIf { it.isNotEmpty() }?.let {
            if (command.kind != ConnectorKind.MCP_HTTP) throw ValidationException("GITLAB_READ uses the project's read-only CI_READ token, not a secret of its own", "secret")
            seal(id, it, command.auth)
        }
        val now = clock.now()
        val connector = Connector(
            id = id, key = key, name = command.name.trim(), description = command.description.trim(), kind = command.kind,
            projectId = command.projectId, url = url(command), auth = auth(command), headerName = headerName(command),
            repository = repository(command, project), secret = secret,
            createdAt = now, createdBy = actor, updatedAt = now, updatedBy = actor,
        )
        val saved = connectors.save(connector)
        audit.record(AuditEntry(CREATE, ENTITY, id.toString(), projectId = saved.projectId?.value, details = describe(saved)))
        return saved
    }

    override fun update(id: ConnectorId, command: ConnectorCommand): Connector {
        val current = connectors.findById(id) ?: throw NotFoundException(ENTITY, id)
        val actor = requireManage(current.projectId).actor
        if (command.projectId != current.projectId) throw ValidationException("the scope of a connector cannot change; create a new one", "scope")
        command.key?.trim()?.takeIf { it.isNotEmpty() && it != current.key }?.let { throw ValidationException("the key of a connector cannot change", "key") }
        val project = current.projectId?.let(::loadProject)
        val auth = auth(command)
        // null keeps the stored secret, "" removes it; auth NONE (or GITLAB_READ) keeps none
        val secret = when {
            command.kind != ConnectorKind.MCP_HTTP || auth == ConnectorAuth.NONE -> null
            command.secret == null -> current.secret
            command.secret.isEmpty() -> null
            else -> seal(id, command.secret, auth)
        }
        val saved = connectors.save(
            current.copy(
                name = command.name.trim(), description = command.description.trim(), kind = command.kind, url = url(command), auth = auth,
                headerName = headerName(command), repository = repository(command, project), secret = secret,
                // a changed endpoint or secret makes the last test meaningless
                lastTest = current.lastTest.takeIf { command.kind == current.kind && url(command) == current.url && secret == current.secret && auth == current.auth },
                updatedAt = clock.now(), updatedBy = actor,
            ),
        )
        val secretChange = when {
            saved.secret == current.secret -> emptyMap()
            saved.secret == null -> mapOf("secret" to "removed")
            current.secret == null -> mapOf("secret" to "set", "keyId" to saved.secret.keyId)
            else -> mapOf("secret" to "replaced", "keyId" to saved.secret.keyId)
        }
        val changes = AuditableChanges.changed(
            "name" to (current.name to saved.name),
            "description" to (current.description to saved.description),
            "kind" to (current.kind.name to saved.kind.name),
            "url" to (current.url to saved.url),
            "auth" to (current.auth.name to saved.auth.name),
            "headerName" to (current.headerName to saved.headerName),
            "repository" to (current.repository to saved.repository),
        ) + secretChange
        audit.record(AuditEntry(UPDATE, ENTITY, id.toString(), projectId = saved.projectId?.value, details = mapOf("key" to saved.key) + changes))
        return saved
    }

    override fun delete(id: ConnectorId) {
        val current = connectors.findById(id) ?: throw NotFoundException(ENTITY, id)
        requireManage(current.projectId)
        connectors.delete(id)
        audit.record(AuditEntry(DELETE, ENTITY, id.toString(), projectId = current.projectId?.value, details = describe(current)))
    }

    override fun test(id: ConnectorId): ConnectorTest {
        val current = connectors.findById(id) ?: throw NotFoundException(ENTITY, id)
        requireManage(current.projectId)
        val result = when (current.kind) {
            ConnectorKind.MCP_HTTP -> testMcp(current)
            ConnectorKind.GITLAB_READ -> testGitLabRead(current)
        }
        connectors.save(current.copy(lastTest = result))
        audit.record(
            AuditEntry(
                TEST, ENTITY, id.toString(), projectId = current.projectId?.value,
                details = mapOf("key" to current.key, "kind" to current.kind.name, "ok" to result.ok, "tools" to result.tools.size),
            ),
        )
        return result
    }

    @SystemOperation("startup job of the worker role after a key rotation; changes no plaintext and no permissions")
    override fun rewrapConnectorSecrets(): Int {
        if (!sealer.configured) return 0
        var count = 0
        connectors.findAll().forEach { connector ->
            val sealed = connector.secret ?: return@forEach
            val rewrapped = runCatching { sealer.rewrap(sealed, Connector.secretAssociatedData(connector.id)) }.getOrNull() ?: return@forEach
            if (connectors.replaceSecret(connector.id, sealed, rewrapped)) {
                count++
                audit.record(
                    AuditEntry(
                        REWRAP, ENTITY, connector.id.toString(), projectId = connector.projectId?.value, actor = Actor.SYSTEM,
                        details = mapOf("key" to connector.key, "fromKeyId" to sealed.keyId, "toKeyId" to rewrapped.keyId),
                    ),
                )
            }
        }
        return count
    }

    private fun testMcp(connector: Connector): ConnectorTest {
        if (connector.auth != ConnectorAuth.NONE && connector.secret == null) {
            return ConnectorTest(false, "auth ${connector.auth} needs a secret; set one and test again", emptyList(), clock.now())
        }
        val probe = mcp.probe(connector)
        return ConnectorTest(probe.ok, probe.message, probe.tools, clock.now())
    }

    /** ADR 0051: the least-privileged token declared read-only with CI_READ, verified again; ok when it stays verified read-only. */
    private fun testGitLabRead(connector: Connector): ConnectorTest {
        val now = clock.now()
        val project = loadProject(connector.projectId ?: return ConnectorTest(false, "GITLAB_READ connectors belong to a project", emptyList(), now))
        val repositoryKey = connector.repository ?: project.primaryRepository?.key
            ?: return ConnectorTest(false, "the project has no primary repository; name the connector's repository", emptyList(), now)
        val repository = project.repositories.firstOrNull { it.key == repositoryKey }
            ?: return ConnectorTest(false, "repository '$repositoryKey' is not part of the project", emptyList(), now)
        val candidates = scmConnections.findByProject(project.id)
            .filter { it.repositoryKey == repositoryKey && ScmCapability.CI_READ in it.capabilities && it.capabilities.none { c -> c.write } }
        if (candidates.isEmpty()) return ConnectorTest(false, noTokenMessage(repositoryKey), emptyList(), now)
        val verified = candidates.map { c ->
            val verification = ScmVerificationPolicy.evaluate(c.capabilities, scmProbe.probe(c.provider, repository, ScmToken.Stored(c)), now)
            scmConnections.save(c.copy(verification = verification, secretExpiresAt = c.secretExpiresAt ?: verification.tokenExpiresAt))
        }
        val token = ScmConnectionSelector.select(verified, repositoryKey, setOf(ScmCapability.CI_READ), readOnly = true)
        if (token == null) {
            val problems = verified.flatMap { c -> c.verification?.messages.orEmpty().filter { it.severity != MessageSeverity.INFO }.map { "${c.name}: ${it.text}" } }
            return ConnectorTest(false, (listOf(noTokenMessage(repositoryKey)) + problems).joinToString(" "), emptyList(), now)
        }
        val v = token.verification
        return ConnectorTest(
            true,
            "token ${token.name} of repository $repositoryKey is verified read-only with CI_READ" + (v?.role?.let { " (role $it)" } ?: ""),
            emptyList(), now,
        )
    }

    private fun noTokenMessage(repositoryKey: String) =
        "no verified read-only token with CI_READ for repository '$repositoryKey': add one under the project's Sources tab, " +
            "Repository access tokens (role Reporter, scopes read_api and read_repository), and test it."

    // inline: the gate call lands in each use case's body (DenyByDefaultArchitectureTest)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun requireManage(projectId: ProjectId?): CurrentUser =
        if (projectId == null) gate.require(ProjectsPermissions.LIBRARY_MANAGE) else gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value)

    private fun siblings(projectId: ProjectId?): List<Connector> = projectId?.let(connectors::findByProject) ?: connectors.findPlatform()

    private fun seal(id: ConnectorId, secret: String, auth: ConnectorAuth): SealedSecret {
        if (auth == ConnectorAuth.NONE) throw ValidationException("auth NONE sends no secret; choose BEARER or HEADER", "secret")
        if (secret.length > Connector.MAX_SECRET_LENGTH) throw ValidationException("at most ${Connector.MAX_SECRET_LENGTH} characters", "secret")
        if (secret.any { it == '\r' || it == '\n' }) throw ValidationException("must be a single line", "secret")
        if (!sealer.configured) {
            throw InvalidStateException(
                "BACKOFFICE_SECRETS_KEY is not configured, so secrets cannot be stored; set it (base64 of 32 random bytes, docs/guides/configuration.md) and restart",
            )
        }
        return sealer.seal(secret, Connector.secretAssociatedData(id))
    }

    private fun url(c: ConnectorCommand) = c.url?.trim()?.takeIf { it.isNotEmpty() }

    private fun auth(c: ConnectorCommand) = if (c.kind == ConnectorKind.GITLAB_READ) ConnectorAuth.NONE else c.auth

    private fun headerName(c: ConnectorCommand) = c.headerName?.trim()?.takeIf { it.isNotEmpty() && c.kind == ConnectorKind.MCP_HTTP && c.auth == ConnectorAuth.HEADER }

    /** GITLAB_READ: a repository of the project, or null for the primary one. */
    private fun repository(c: ConnectorCommand, project: Project?): String? {
        val key = c.repository?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (c.kind != ConnectorKind.GITLAB_READ) throw ValidationException("is only for GITLAB_READ connectors", "repository")
        if (project != null && project.repositories.none { it.key == key }) throw ValidationException("is not a repository of this project", "repository")
        return key
    }

    private fun scopeName(projectId: ProjectId?) = if (projectId == null) "the platform" else "this project"

    private fun loadProject(id: ProjectId): Project = projects.findById(id) ?: throw NotFoundException("Project", id)

    private fun describe(c: Connector): Map<String, Any?> = mapOf(
        "key" to c.key, "name" to c.name, "kind" to c.kind.name, "scope" to (if (c.platform) "PLATFORM" else "PROJECT"),
        "url" to c.url, "auth" to c.auth.name, "headerName" to c.headerName, "repository" to c.repository,
        "hasSecret" to c.hasSecret, "keyId" to c.secret?.keyId,
    )

    companion object {
        const val ENTITY = "Connector"
        const val CREATE = "CONNECTOR_CREATE"
        const val UPDATE = "CONNECTOR_UPDATE"
        const val DELETE = "CONNECTOR_DELETE"
        const val TEST = "CONNECTOR_TEST"
        const val REWRAP = "CONNECTOR_SECRET_REWRAP"
    }
}
