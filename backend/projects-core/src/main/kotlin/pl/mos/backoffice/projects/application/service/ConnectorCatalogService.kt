package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.out.ConnectorRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.service.ConnectorResolution
import pl.mos.backoffice.shared.runner.ConnectorCatalog
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.runner.JobConnectors
import pl.mos.backoffice.shared.security.SystemOperation
import java.util.UUID

/**
 * ADR 0064: the connectors of other contexts' agent jobs (benchmarks give a candidate agent its connectors). Only
 * `MCP_HTTP` connectors: `GITLAB_READ` needs a project's repositories and their tokens, which only a workflow step has.
 * Platform-internal: called by the benchmark executor after an authorised start, never through a controller.
 */
class ConnectorCatalogService(private val connectors: ConnectorRepository) : ConnectorCatalog {

    @SystemOperation("benchmark executor resolving the connectors of a candidate agent; never exposed through a controller")
    override fun mcpConnectors(keys: List<String>, projectId: UUID?): JobConnectors {
        if (keys.isEmpty()) return JobConnectors.NONE
        val project = projectId?.let { connectors.findByProject(ProjectId(it)) }.orEmpty()
        val platform = connectors.findPlatform()
        val (mcp, other) = keys.distinct().partition { key -> ConnectorResolution.lookup(key, project, platform)?.kind != ConnectorKind.GITLAB_READ }
        val result = ConnectorResolution.resolve(mcp, project, platform, emptyList(), emptyList())
        return result.connectors.copy(notes = result.connectors.notes + other.map { "connector $it (GITLAB_READ) needs a workflow step's repository; skipped" })
    }
}
