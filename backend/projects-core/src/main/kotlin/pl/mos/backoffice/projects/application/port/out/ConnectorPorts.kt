package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.SealedSecret

/** ADR 0064: connectors (platform: `projectId` null). */
interface ConnectorRepository {
    fun save(connector: Connector): Connector
    fun findById(id: ConnectorId): Connector?
    fun findPlatform(): List<Connector>
    fun findByProject(projectId: ProjectId): List<Connector>
    fun findAll(): List<Connector>
    fun delete(id: ConnectorId)

    /** Re-wrap after a key rotation: replaces the secret only when it is still [expected]; false when it changed meanwhile. */
    fun replaceSecret(id: ConnectorId, expected: SealedSecret, replacement: SealedSecret): Boolean
}

/** ADR 0064: what an MCP server answered to the `initialize` handshake and `tools/list` (null tools = not listed). */
data class McpProbe(val ok: Boolean, val message: String, val tools: List<String> = emptyList())

/**
 * ADR 0064: talks to the MCP server of an `MCP_HTTP` connector from Back Office (streamable HTTP, JSON-RPC `initialize`,
 * then `tools/list`) with the stored secret, which the adapter opens. Never throws; failures are a not-ok [McpProbe].
 */
fun interface McpProbePort {
    fun probe(connector: Connector): McpProbe
}
