package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ConnectorTest
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind

/**
 * ADR 0064: connectors, the tools agents use. Platform connectors are seen with `library.view` and managed with
 * `library.manage` (administrators); a project's connectors are seen with `project.view` and managed with
 * `project.connections.manage` (owners). Secrets are write-only.
 */
interface ManageConnectors {
    /** The platform connectors (when the caller may see the library) and, with [projectId], that project's connectors. */
    fun list(projectId: ProjectId?): List<Connector>
    fun create(command: ConnectorCommand): Connector
    /** [ConnectorCommand.secret] null keeps the stored secret, "" removes it; key and scope cannot change. */
    fun update(id: ConnectorId, command: ConnectorCommand): Connector
    fun delete(id: ConnectorId)
    /** Tests the connector from Back Office and keeps the result on it. */
    fun test(id: ConnectorId): ConnectorTest

    data class ConnectorCommand(
        val key: String? = null,
        val name: String,
        val description: String = "",
        val kind: ConnectorKind,
        /** null = platform scope. */
        val projectId: ProjectId?,
        val url: String? = null,
        val auth: ConnectorAuth = ConnectorAuth.NONE,
        val headerName: String? = null,
        val secret: String? = null,
        val repository: String? = null,
    ) {
        override fun toString() = "ConnectorCommand(key=$key, name=$name, kind=$kind, projectId=$projectId, url=$url, auth=$auth, secret=${if (secret == null) "null" else "***"})"
    }
}

/** ADR 0064: startup job of the worker role after a key rotation (like [RewrapScmConnectionSecrets]). */
fun interface RewrapConnectorSecrets {
    fun rewrapConnectorSecrets(): Int
}
