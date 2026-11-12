package pl.mos.backoffice.projects

import pl.mos.backoffice.projects.application.port.out.ConnectorRepository
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.security.Actor

/** Test doubles and builders for connectors (ADR 0064). */
object ConnectorFakes {

    fun mcp(
        key: String,
        projectId: ProjectId? = null,
        url: String = "https://mcp.example.com/$key",
        auth: ConnectorAuth = ConnectorAuth.NONE,
        headerName: String? = null,
        secret: SealedSecret? = if (auth == ConnectorAuth.NONE) null else SealedSecret("k1", "wrapped-$key", "cipher-$key"),
    ) = Connector(
        id = ConnectorId.new(), key = key, name = key, description = "", kind = ConnectorKind.MCP_HTTP, projectId = projectId,
        url = url, auth = auth, headerName = headerName, secret = secret,
        createdAt = Fixtures.now, createdBy = Actor.SYSTEM, updatedAt = Fixtures.now, updatedBy = Actor.SYSTEM,
    )

    fun gitlabRead(projectId: ProjectId, key: String = "gitlab-read", repository: String? = null) = Connector(
        id = ConnectorId.new(), key = key, name = "GitLab (read only)", description = "", kind = ConnectorKind.GITLAB_READ, projectId = projectId,
        repository = repository, createdAt = Fixtures.now, createdBy = Actor.SYSTEM, updatedAt = Fixtures.now, updatedBy = Actor.SYSTEM,
    )

    class InMemoryConnectors(vararg initial: Connector) : ConnectorRepository {
        val rows = linkedMapOf<ConnectorId, Connector>().apply { initial.forEach { put(it.id, it) } }
        override fun save(connector: Connector) = connector.also { rows[it.id] = it }
        override fun findById(id: ConnectorId) = rows[id]
        override fun findPlatform() = rows.values.filter { it.projectId == null }
        override fun findByProject(projectId: ProjectId) = rows.values.filter { it.projectId == projectId }
        override fun findAll() = rows.values.toList()
        override fun delete(id: ConnectorId) { rows.remove(id) }
        override fun replaceSecret(id: ConnectorId, expected: SealedSecret, replacement: SealedSecret): Boolean {
            val current = rows[id] ?: return false
            if (current.secret != expected) return false
            rows[id] = current.copy(secret = replacement)
            return true
        }
    }
}
