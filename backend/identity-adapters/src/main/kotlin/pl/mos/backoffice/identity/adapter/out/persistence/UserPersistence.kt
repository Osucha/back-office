package pl.mos.backoffice.identity.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.instant
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.uuid
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.Page
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.Theme
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.security.UserId
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

class JdbcUserRepository(private val jdbc: JdbcClient, private val json: JsonMapper) : UserRepository {

    override fun findById(id: UserId): User? = one("WHERE u.id = :id", mapOf("id" to id.value))

    override fun findByIds(ids: Collection<UserId>): List<User> =
        if (ids.isEmpty()) emptyList() else many("WHERE u.id IN (:ids)", mapOf("ids" to ids.map { it.value }))

    override fun findByIdentity(issuer: String, subject: String): User? =
        one("WHERE u.issuer = :issuer AND u.subject = :subject", mapOf("issuer" to issuer, "subject" to subject))

    override fun findByEmail(email: String): User? = one("WHERE lower(u.email) = :email", mapOf("email" to User.normaliseEmail(email)))

    override fun search(query: UserQuery): Page<User> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        query.q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(lower(u.email) LIKE :q ESCAPE '\\' OR lower(u.display_name) LIKE :q ESCAPE '\\')"
            params["q"] = JdbcSupport.likePattern(it)
        }
        query.status?.let { where += "u.status = :status"; params["status"] = it.name }
        query.groupId?.let {
            where += "EXISTS (SELECT 1 FROM identity_group_member m WHERE m.user_id = u.id AND m.group_id = :groupId)"
            params["groupId"] = it.value
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = jdbc.sql("SELECT count(*) FROM identity_user u $clause").params(params).query(Long::class.javaObjectType).single()
        val content = many("$clause ORDER BY ${orderBy(query)} LIMIT :limit OFFSET :offset", params + mapOf("limit" to query.size, "offset" to query.page.toLong() * query.size))
        return Page(content, query.page, query.size, total)
    }

    /** Column of each [UserQuery.SORT_FIELDS] entry, with the id as a stable tiebreaker; never-signed-in users last. */
    private fun orderBy(query: UserQuery): String {
        val sort = query.sort ?: return "lower(u.display_name), u.id"
        val direction = if (sort.descending) "DESC" else "ASC"
        val column = when (sort.field) {
            "name" -> "lower(u.display_name) $direction"
            "email" -> "lower(u.email) $direction"
            "status" -> "u.status $direction"
            "lastLoginAt" -> "u.last_login_at $direction NULLS LAST"
            else -> "lower(u.display_name) $direction"
        }
        return "$column, u.id $direction"
    }

    override fun lookup(q: String, limit: Int): List<User> = many(
        "WHERE u.status <> 'DEACTIVATED' AND (lower(u.email) LIKE :q ESCAPE '\\' OR lower(u.display_name) LIKE :q ESCAPE '\\') ORDER BY lower(u.display_name) LIMIT :limit",
        mapOf("q" to JdbcSupport.likePattern(q), "limit" to limit),
    )

    override fun findByStatus(status: UserStatus): List<User> = many("WHERE u.status = :status", mapOf("status" to status.name))

    override fun save(user: User): User {
        jdbc.sql(
            """
            INSERT INTO identity_user (id, issuer, subject, email, display_name, status, preferences, last_login_at, created_at,
                                       created_by_kind, created_by_id, created_by_name, auth_mode, last_login_method)
            VALUES (:id, :issuer, :subject, :email, :displayName, :status, CAST(:preferences AS jsonb), :lastLoginAt, :createdAt,
                    :createdByKind, :createdById, :createdByName, :authMode, :lastLoginMethod)
            ON CONFLICT (id) DO UPDATE SET issuer = EXCLUDED.issuer, subject = EXCLUDED.subject, email = EXCLUDED.email,
                display_name = EXCLUDED.display_name, status = EXCLUDED.status, preferences = EXCLUDED.preferences,
                last_login_at = EXCLUDED.last_login_at, auth_mode = EXCLUDED.auth_mode, last_login_method = EXCLUDED.last_login_method
            """.trimIndent(),
        )
            .param("id", user.id.value).param("issuer", user.issuer).param("subject", user.subject).param("email", user.email)
            .param("displayName", user.displayName).param("status", user.status.name).param("preferences", writePreferences(user.preferences))
            .param("lastLoginAt", JdbcSupport.ts(user.lastLoginAt)).param("createdAt", JdbcSupport.ts(user.createdAt))
            .param("createdByKind", user.createdBy.kind.name).param("createdById", user.createdBy.userId).param("createdByName", user.createdBy.displayName)
            .param("authMode", user.authMode.name).param("lastLoginMethod", user.lastLoginMethod?.name)
            .update()
        jdbc.sql("DELETE FROM identity_group_member WHERE user_id = :id").param("id", user.id.value).update()
        user.groupIds.forEach {
            jdbc.sql("INSERT INTO identity_group_member (group_id, user_id) VALUES (:groupId, :userId) ON CONFLICT DO NOTHING")
                .param("groupId", it.value).param("userId", user.id.value).update()
        }
        return user
    }

    private fun one(clause: String, params: Map<String, Any>): User? = many(clause, params).firstOrNull()

    private fun many(clause: String, params: Map<String, Any>): List<User> {
        val rows = jdbc.sql("SELECT u.* FROM identity_user u $clause").params(params).query { rs, _ -> row(rs) }.list()
        if (rows.isEmpty()) return emptyList()
        val groups = jdbc.sql("SELECT user_id, group_id FROM identity_group_member WHERE user_id IN (:ids)")
            .param("ids", rows.map { it.id.value })
            .query { rs, _ -> rs.uuid("user_id")!! to GroupId(rs.uuid("group_id")!!) }
            .list()
            .groupBy({ it.first }, { it.second })
        return rows.map { it.copy(groupIds = groups[it.id.value]?.toSet() ?: emptySet()) }
    }

    private fun row(rs: ResultSet) = User(
        id = UserId(rs.uuid("id")!!),
        issuer = rs.getString("issuer"),
        subject = rs.getString("subject"),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        status = UserStatus.valueOf(rs.getString("status")),
        preferences = readPreferences(rs.getString("preferences")),
        lastLoginAt = rs.instant("last_login_at"),
        createdAt = rs.instant("created_at")!!,
        createdBy = JdbcSupport.actor(rs, "created_by"),
        authMode = AuthMode.valueOf(rs.getString("auth_mode")),
        lastLoginMethod = rs.getString("last_login_method")?.let(LoginMethod::valueOf),
    )

    private fun writePreferences(p: UserPreferences): String =
        json.writeValueAsString(mapOf("theme" to p.theme.name, "defaultProjectId" to p.defaultProjectId?.toString(), "pageSize" to p.pageSize))

    private fun readPreferences(text: String?): UserPreferences {
        if (text.isNullOrBlank()) return UserPreferences()
        return runCatching {
            val map = json.readValue(text, Map::class.java)
            UserPreferences(
                theme = (map["theme"] as? String)?.let { Theme.valueOf(it) } ?: Theme.SYSTEM,
                defaultProjectId = (map["defaultProjectId"] as? String)?.let(UUID::fromString),
                pageSize = (map["pageSize"] as? Number)?.toInt() ?: 20,
            )
        }.getOrDefault(UserPreferences())
    }
}

class JdbcGroupRepository(private val jdbc: JdbcClient) : GroupRepository {

    override fun findById(id: GroupId): Group? = many("WHERE g.id = :id", mapOf("id" to id.value)).firstOrNull()

    override fun findByIds(ids: Collection<GroupId>): List<Group> =
        if (ids.isEmpty()) emptyList() else many("WHERE g.id IN (:ids)", mapOf("ids" to ids.map { it.value }))

    override fun findAll(): List<Group> = many("ORDER BY lower(g.name)", emptyMap())

    override fun findByName(name: String): Group? = many("WHERE lower(g.name) = :name", mapOf("name" to name.trim().lowercase())).firstOrNull()

    override fun lookup(q: String?, limit: Int): List<Group> =
        if (q == null) many("ORDER BY lower(g.name) LIMIT :limit", mapOf("limit" to limit))
        else many("WHERE lower(g.name) LIKE :q ESCAPE '\\' ORDER BY lower(g.name) LIMIT :limit", mapOf("q" to JdbcSupport.likePattern(q), "limit" to limit))

    override fun save(group: Group): Group {
        jdbc.sql(
            """
            INSERT INTO identity_group (id, name, description, external_group, created_at)
            VALUES (:id, :name, :description, :externalGroup, :createdAt)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, external_group = EXCLUDED.external_group
            """.trimIndent(),
        )
            .param("id", group.id.value).param("name", group.name).param("description", group.description)
            .param("externalGroup", group.externalGroup).param("createdAt", JdbcSupport.ts(group.createdAt))
            .update()
        jdbc.sql("DELETE FROM identity_group_member WHERE group_id = :id").param("id", group.id.value).update()
        group.memberIds.forEach {
            jdbc.sql("INSERT INTO identity_group_member (group_id, user_id) VALUES (:groupId, :userId) ON CONFLICT DO NOTHING")
                .param("groupId", group.id.value).param("userId", it.value).update()
        }
        return group
    }

    override fun delete(id: GroupId) {
        jdbc.sql("DELETE FROM identity_group WHERE id = :id").param("id", id.value).update()
    }

    private fun many(clause: String, params: Map<String, Any>): List<Group> {
        val rows = jdbc.sql("SELECT g.* FROM identity_group g $clause").params(params).query { rs, _ ->
            Group(GroupId(rs.uuid("id")!!), rs.getString("name"), rs.getString("description"), emptySet(), rs.getString("external_group"), rs.instant("created_at")!!)
        }.list()
        if (rows.isEmpty()) return emptyList()
        val members = jdbc.sql("SELECT group_id, user_id FROM identity_group_member WHERE group_id IN (:ids)")
            .param("ids", rows.map { it.id.value })
            .query { rs, _ -> rs.uuid("group_id")!! to UserId(rs.uuid("user_id")!!) }
            .list()
            .groupBy({ it.first }, { it.second })
        return rows.map { it.copy(memberIds = members[it.id.value]?.toSet() ?: emptySet()) }
    }
}
