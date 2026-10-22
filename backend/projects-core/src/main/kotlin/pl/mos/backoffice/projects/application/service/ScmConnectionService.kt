package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections.ScmConnectionCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections.ScmConnectionView
import pl.mos.backoffice.projects.application.port.`in`.RewrapScmConnectionSecrets
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.ScmProbePort
import pl.mos.backoffice.projects.application.port.out.ScmToken
import pl.mos.backoffice.projects.application.port.out.SecretSealer
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.ScmVerificationPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * Tokens of project repositories (ADR 0051 step 1). The token is sealed before it is stored and never returned;
 * every create, update and token replacement tests it against the SCM right away and keeps the result (a failed
 * test does not block the save, as for Jira connections: the SCM may be down).
 */
class ScmConnectionService(
    private val connections: ScmConnectionRepository,
    private val projects: ProjectRepository,
    private val sealer: SecretSealer,
    private val probe: ScmProbePort,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageScmConnections, RewrapScmConnectionSecrets {

    override fun list(projectId: ProjectId): List<ScmConnectionView> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val project = loadProject(projectId)
        return connections.findByProject(projectId)
            .sortedWith(compareBy({ it.repositoryKey }, { it.name.lowercase() }))
            .map { view(it, project) }
    }

    override fun create(projectId: ProjectId, command: ScmConnectionCommand): ScmConnectionView {
        val actor = gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value).actor
        requireSealer()
        val project = loadProject(projectId)
        val repository = repositoryOf(project, command.repositoryKey)
        val token = command.token?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException("is required", "token")
        requireUniqueName(projectId, command.name, null)
        val now = clock.now()
        val id = ScmConnectionId.new()
        val verification = evaluate(command, repository, ScmToken.Plain(token))
        val connection = ScmConnection(
            id = id, projectId = projectId, repositoryKey = repository.key, name = command.name.trim(), provider = command.provider,
            capabilities = command.capabilities, secret = sealer.seal(token, ScmConnection.secretAssociatedData(id)),
            secretExpiresAt = command.secretExpiresAt ?: verification.tokenExpiresAt, verification = verification,
            createdAt = now, createdBy = actor, updatedAt = now, updatedBy = actor,
        )
        val saved = connections.save(connection)
        audit.record(AuditEntry(CREATE, ENTITY, id.toString(), projectId = projectId.value, details = describe(saved)))
        return view(saved, project)
    }

    override fun update(projectId: ProjectId, id: ScmConnectionId, command: ScmConnectionCommand): ScmConnectionView {
        val actor = gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value).actor
        val project = loadProject(projectId)
        val current = load(projectId, id)
        val repository = repositoryOf(project, command.repositoryKey)
        requireUniqueName(projectId, command.name, id)
        val newToken = command.token?.trim()?.takeIf { it.isNotEmpty() }
        if (newToken != null) requireSealer()
        val secret = newToken?.let { sealer.seal(it, ScmConnection.secretAssociatedData(id)) } ?: current.secret
        val draft = current.copy(repositoryKey = repository.key, name = command.name.trim(), capabilities = command.capabilities, secret = secret)
        val verification = evaluate(command, repository, newToken?.let { ScmToken.Plain(it) } ?: ScmToken.Stored(draft))
        val expires = command.secretExpiresAt ?: if (newToken != null) verification.tokenExpiresAt else current.secretExpiresAt
        val saved = connections.save(draft.copy(secretExpiresAt = expires, verification = verification, updatedAt = clock.now(), updatedBy = actor))
        val changes = AuditableChanges.changed(
            "name" to (current.name to saved.name),
            "repositoryKey" to (current.repositoryKey to saved.repositoryKey),
            "capabilities" to (names(current) to names(saved)),
            "secretExpiresAt" to (current.secretExpiresAt?.toString() to saved.secretExpiresAt?.toString()),
        ) + (if (newToken != null) mapOf("token" to "replaced", "keyId" to saved.secret.keyId) else emptyMap()) +
            mapOf("verification" to verification.status.name)
        audit.record(AuditEntry(if (newToken != null) ROTATE else UPDATE, ENTITY, id.toString(), projectId = projectId.value, details = changes))
        return view(saved, project)
    }

    override fun delete(projectId: ProjectId, id: ScmConnectionId) {
        gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value)
        val current = load(projectId, id)
        connections.delete(id)
        audit.record(AuditEntry(DELETE, ENTITY, id.toString(), projectId = projectId.value, details = describe(current)))
    }

    override fun verify(projectId: ProjectId, id: ScmConnectionId): ScmConnectionView {
        gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value)
        val project = loadProject(projectId)
        val current = load(projectId, id)
        val repository = project.repositories.firstOrNull { it.key == current.repositoryKey }
            ?: throw InvalidStateException("repository '${current.repositoryKey}' is no longer part of the project; delete this connection or add the repository again")
        val verification = ScmVerificationPolicy.evaluate(current.capabilities, probe.probe(current.provider, repository, ScmToken.Stored(current)), clock.now())
        val saved = connections.save(current.copy(verification = verification, secretExpiresAt = current.secretExpiresAt ?: verification.tokenExpiresAt))
        audit.record(AuditEntry(VERIFY, ENTITY, id.toString(), projectId = projectId.value, details = mapOf("name" to saved.name, "verification" to verification.status.name)))
        return view(saved, project)
    }

    override fun test(projectId: ProjectId, command: ScmConnectionCommand): ScmVerification {
        gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value)
        val repository = repositoryOf(loadProject(projectId), command.repositoryKey)
        val token = command.token?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException("is required", "token")
        val verification = evaluate(command, repository, ScmToken.Plain(token))
        audit.record(
            AuditEntry(
                TEST, ENTITY, null, projectId = projectId.value,
                details = mapOf("repositoryKey" to repository.key, "capabilities" to command.capabilities.map { it.name }.sorted(), "verification" to verification.status.name),
            ),
        )
        return verification
    }

    @SystemOperation("startup job of the worker role after a key rotation; changes no plaintext and no permissions")
    override fun rewrapScmSecrets(): Int {
        if (!sealer.configured) return 0
        var count = 0
        connections.findAll().forEach { connection ->
            val sealed = connection.secret
            val rewrapped = runCatching { sealer.rewrap(sealed, ScmConnection.secretAssociatedData(connection.id)) }.getOrNull() ?: return@forEach
            if (connections.replaceSecret(connection.id, sealed, rewrapped)) {
                count++
                audit.record(
                    AuditEntry(
                        REWRAP, ENTITY, connection.id.toString(), projectId = connection.projectId.value, actor = Actor.SYSTEM,
                        details = mapOf("name" to connection.name, "fromKeyId" to sealed.keyId, "toKeyId" to rewrapped.keyId),
                    ),
                )
            }
        }
        return count
    }

    private fun evaluate(command: ScmConnectionCommand, repository: GitRepository, token: ScmToken): ScmVerification {
        if (command.capabilities.isEmpty()) throw ValidationException("choose at least one", "capabilities")
        return ScmVerificationPolicy.evaluate(command.capabilities, probe.probe(command.provider, repository, token), clock.now())
    }

    private fun view(c: ScmConnection, project: Project) = ScmConnectionView(c, project.repositories.any { it.key == c.repositoryKey })

    private fun repositoryOf(project: Project, key: String): GitRepository =
        project.repositories.firstOrNull { it.key == key.trim() } ?: throw ValidationException("is not a repository of this project", "repositoryKey")

    private fun requireUniqueName(projectId: ProjectId, name: String, except: ScmConnectionId?) {
        if (connections.findByProject(projectId).any { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }) {
            throw ValidationException("is already used by another connection of this project", "name")
        }
    }

    private fun requireSealer() {
        if (!sealer.configured) {
            throw InvalidStateException(
                "BACKOFFICE_SECRETS_KEY is not configured, so tokens cannot be stored; set it (base64 of 32 random bytes, docs/guides/configuration.md) and restart",
            )
        }
    }

    private fun load(projectId: ProjectId, id: ScmConnectionId): ScmConnection =
        connections.findById(id)?.takeIf { it.projectId == projectId } ?: throw NotFoundException(ENTITY, id)

    private fun loadProject(id: ProjectId): Project = projects.findById(id) ?: throw NotFoundException("Project", id)

    private fun names(c: ScmConnection) = c.capabilities.map { it.name }.sorted()

    private fun describe(c: ScmConnection): Map<String, Any?> = mapOf(
        "name" to c.name, "repositoryKey" to c.repositoryKey, "provider" to c.provider.name, "capabilities" to names(c),
        "secretExpiresAt" to c.secretExpiresAt?.toString(), "keyId" to c.secret.keyId, "verification" to c.verification?.status?.name,
    )

    companion object {
        const val ENTITY = "ScmConnection"
        const val CREATE = "SCM_CONNECTION_CREATE"
        const val UPDATE = "SCM_CONNECTION_UPDATE"
        const val ROTATE = "SCM_CONNECTION_ROTATE"
        const val DELETE = "SCM_CONNECTION_DELETE"
        const val VERIFY = "SCM_CONNECTION_VERIFY"
        const val TEST = "SCM_CONNECTION_TEST"
        const val REWRAP = "SCM_CONNECTION_SECRET_REWRAP"
    }
}
