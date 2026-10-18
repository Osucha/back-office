package pl.mos.backoffice.projects.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.domain.model.WorkspaceSource
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `project_workspace` (V118, ADR 0044) over JDBC; sources and roles are jsonb. */
@Component
@Transactional
class ProjectWorkspacePersistence(private val jdbc: JdbcClient, private val json: JsonMapper) : ProjectWorkspaceRepository {

    private data class SourceDoc(val key: String = "", val library: Boolean = false)

    private val sourceList = object : TypeReference<List<SourceDoc>>() {}
    private val stringList = object : TypeReference<List<String>>() {}

    @Transactional(readOnly = true)
    override fun findByProjectId(projectId: ProjectId): List<ProjectWorkspace> =
        jdbc.sql("SELECT * FROM project_workspace WHERE project_id = :project ORDER BY key").param("project", projectId.value).query { rs, _ -> map(rs) }.list()

    @Transactional(readOnly = true)
    override fun findById(id: ProjectWorkspaceId): ProjectWorkspace? =
        jdbc.sql("SELECT * FROM project_workspace WHERE id = :id").param("id", id.value).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(workspace: ProjectWorkspace): ProjectWorkspace {
        jdbc.sql(
            """
            INSERT INTO project_workspace (id, project_id, key, name, description, sources, setup_command, roles, created_at, updated_at,
                                           updated_by_kind, updated_by_id, updated_by_name)
            VALUES (:id, :project, :key, :name, :description, CAST(:sources AS jsonb), :setup, CAST(:roles AS jsonb), :created, :updated,
                    :byKind, :byId, :byName)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, sources = EXCLUDED.sources,
                setup_command = EXCLUDED.setup_command, roles = EXCLUDED.roles, updated_at = EXCLUDED.updated_at,
                updated_by_kind = EXCLUDED.updated_by_kind, updated_by_id = EXCLUDED.updated_by_id, updated_by_name = EXCLUDED.updated_by_name
            """.trimIndent(),
        )
            .param("id", workspace.id.value).param("project", workspace.projectId.value).param("key", workspace.key).param("name", workspace.name)
            .param("description", workspace.description)
            .param("sources", json.writeValueAsString(workspace.sources.map { SourceDoc(it.key, it.library) }))
            .param("setup", workspace.setupCommand).param("roles", json.writeValueAsString(workspace.roles))
            .param("created", workspace.createdAt.atOffset(ZoneOffset.UTC)).param("updated", workspace.updatedAt.atOffset(ZoneOffset.UTC))
            .param("byKind", workspace.updatedBy?.kind?.name).param("byId", workspace.updatedBy?.userId).param("byName", workspace.updatedBy?.displayName)
            .update()
        return workspace
    }

    override fun delete(id: ProjectWorkspaceId) {
        jdbc.sql("DELETE FROM project_workspace WHERE id = :id").param("id", id.value).update()
    }

    private fun map(rs: ResultSet): ProjectWorkspace = ProjectWorkspace(
        id = ProjectWorkspaceId(rs.getObject("id", UUID::class.java)),
        projectId = ProjectId(rs.getObject("project_id", UUID::class.java)),
        key = rs.getString("key"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        sources = json.readValue(rs.getString("sources"), sourceList).map { WorkspaceSource(it.key, it.library) },
        setupCommand = rs.getString("setup_command"),
        roles = json.readValue(rs.getString("roles"), stringList),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        updatedBy = rs.getString("updated_by_name")?.let { name ->
            Actor(PrincipalKind.entries.firstOrNull { it.name == rs.getString("updated_by_kind") } ?: PrincipalKind.SYSTEM, rs.getObject("updated_by_id", UUID::class.java), name)
        },
    )
}
