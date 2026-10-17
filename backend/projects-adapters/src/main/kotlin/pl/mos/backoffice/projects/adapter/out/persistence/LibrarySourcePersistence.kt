package pl.mos.backoffice.projects.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.SourceKind
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `library_source` (V116, ADR 0043) over JDBC. */
@Component
@Transactional
class LibrarySourcePersistence(private val jdbc: JdbcClient) : LibrarySourceRepository {

    @Transactional(readOnly = true)
    override fun findAll(): List<LibrarySource> = jdbc.sql("SELECT * FROM library_source ORDER BY key").query { rs, _ -> map(rs) }.list()

    @Transactional(readOnly = true)
    override fun findById(id: LibrarySourceId): LibrarySource? =
        jdbc.sql("SELECT * FROM library_source WHERE id = :id").param("id", id.value).query { rs, _ -> map(rs) }.optional().orElse(null)

    @Transactional(readOnly = true)
    override fun findByKey(key: String): LibrarySource? =
        jdbc.sql("SELECT * FROM library_source WHERE key = :key").param("key", key).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(source: LibrarySource): LibrarySource {
        jdbc.sql(
            """
            INSERT INTO library_source (id, key, name, description, kind, url, default_branch, purpose, created_at, updated_at,
                                        updated_by_kind, updated_by_id, updated_by_name)
            VALUES (:id, :key, :name, :description, :kind, :url, :branch, :purpose, :created, :updated, :byKind, :byId, :byName)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, kind = EXCLUDED.kind, url = EXCLUDED.url,
                default_branch = EXCLUDED.default_branch, purpose = EXCLUDED.purpose, updated_at = EXCLUDED.updated_at,
                updated_by_kind = EXCLUDED.updated_by_kind, updated_by_id = EXCLUDED.updated_by_id, updated_by_name = EXCLUDED.updated_by_name
            """.trimIndent(),
        )
            .param("id", source.id.value).param("key", source.key).param("name", source.name).param("description", source.description)
            .param("kind", source.kind.name).param("url", source.url).param("branch", source.defaultBranch).param("purpose", source.purpose.name)
            .param("created", source.createdAt.atOffset(ZoneOffset.UTC)).param("updated", source.updatedAt.atOffset(ZoneOffset.UTC))
            .param("byKind", source.updatedBy?.kind?.name).param("byId", source.updatedBy?.userId).param("byName", source.updatedBy?.displayName)
            .update()
        return source
    }

    override fun delete(id: LibrarySourceId) {
        jdbc.sql("DELETE FROM library_source WHERE id = :id").param("id", id.value).update()
    }

    private fun map(rs: ResultSet): LibrarySource = LibrarySource(
        id = LibrarySourceId(rs.getObject("id", UUID::class.java)),
        key = rs.getString("key"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        kind = SourceKind.entries.firstOrNull { it.name == rs.getString("kind") } ?: SourceKind.GIT,
        url = rs.getString("url"),
        defaultBranch = rs.getString("default_branch"),
        purpose = RepositoryPurpose.entries.firstOrNull { it.name == rs.getString("purpose") } ?: RepositoryPurpose.OTHER,
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        updatedBy = rs.getString("updated_by_name")?.let { name ->
            Actor(PrincipalKind.entries.firstOrNull { it.name == rs.getString("updated_by_kind") } ?: PrincipalKind.SYSTEM, rs.getObject("updated_by_id", UUID::class.java), name)
        },
    )
}
