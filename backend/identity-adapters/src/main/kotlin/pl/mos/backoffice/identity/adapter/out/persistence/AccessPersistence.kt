package pl.mos.backoffice.identity.adapter.out.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.instant
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.strings
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.uuid
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.ProjectKeyLookup
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.domain.model.Grant
import pl.mos.backoffice.identity.domain.model.GrantEffect
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.LockoutSettings
import pl.mos.backoffice.identity.domain.model.PasswordPolicy
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class JdbcRoleRepository(private val jdbc: JdbcClient) : RoleRepository {

    override fun findAll(): List<Role> = jdbc.sql("SELECT * FROM identity_role ORDER BY key").query { rs, _ -> row(rs) }.list()

    override fun findByKey(key: String): Role? =
        jdbc.sql("SELECT * FROM identity_role WHERE key = :key").param("key", key).query { rs, _ -> row(rs) }.optional().orElse(null)

    override fun save(role: Role): Role {
        jdbc.sql(
            """
            INSERT INTO identity_role (key, name, description, scope, permissions, built_in)
            VALUES (:key, :name, :description, :scope, string_to_array(:permissions, ','), :builtIn)
            ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                scope = EXCLUDED.scope, permissions = EXCLUDED.permissions, built_in = EXCLUDED.built_in
            """.trimIndent(),
        )
            .param("key", role.key).param("name", role.name).param("description", role.description).param("scope", role.scope.name)
            .param("permissions", JdbcSupport.csv(role.permissions)).param("builtIn", role.builtIn)
            .update()
        return role
    }

    override fun delete(key: String) {
        jdbc.sql("DELETE FROM identity_role WHERE key = :key").param("key", key).update()
    }

    private fun row(rs: ResultSet) = Role(
        key = rs.getString("key"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        scope = PermissionScope.valueOf(rs.getString("scope")),
        permissions = rs.strings("permissions"),
        builtIn = rs.getBoolean("built_in"),
    )
}

class JdbcGrantRepository(private val jdbc: JdbcClient) : GrantRepository {

    override fun find(level: GrantLevel, targetId: UUID?): GrantSet {
        val target = targetId ?: JdbcSupport.SYSTEM_TARGET
        val roles = jdbc.sql("SELECT role_key FROM identity_role_assignment WHERE level = :level AND target_id = :target")
            .param("level", level.name).param("target", target).query(String::class.java).list().filterNotNull().toSet()
        val grants = jdbc.sql("SELECT permission, effect FROM identity_grant WHERE level = :level AND target_id = :target ORDER BY permission")
            .param("level", level.name).param("target", target)
            .query { rs, _ -> Grant(rs.getString("permission"), GrantEffect.valueOf(rs.getString("effect"))) }.list()
        return GrantSet(level, if (level == GrantLevel.SYSTEM) null else targetId, roles, grants)
    }

    override fun findAll(): List<GrantSet> {
        val targets = jdbc.sql(
            "SELECT level, target_id FROM identity_role_assignment UNION SELECT level, target_id FROM identity_grant",
        ).query { rs, _ -> GrantLevel.valueOf(rs.getString("level")) to rs.uuid("target_id")!! }.list().distinct()
        return targets.map { (level, target) -> find(level, if (level == GrantLevel.SYSTEM) null else target) }
    }

    override fun save(set: GrantSet) {
        val target = set.targetId ?: JdbcSupport.SYSTEM_TARGET
        jdbc.sql("DELETE FROM identity_role_assignment WHERE level = :level AND target_id = :target").param("level", set.level.name).param("target", target).update()
        jdbc.sql("DELETE FROM identity_grant WHERE level = :level AND target_id = :target").param("level", set.level.name).param("target", target).update()
        set.roles.forEach {
            jdbc.sql("INSERT INTO identity_role_assignment (level, target_id, role_key) VALUES (:level, :target, :role)")
                .param("level", set.level.name).param("target", target).param("role", it).update()
        }
        set.grants.forEach {
            jdbc.sql("INSERT INTO identity_grant (level, target_id, permission, effect) VALUES (:level, :target, :permission, :effect)")
                .param("level", set.level.name).param("target", target).param("permission", it.permission).param("effect", it.effect.name).update()
        }
    }

    override fun deleteTarget(level: GrantLevel, targetId: UUID) = save(GrantSet(level, targetId))

    override fun targetsWithRole(roleKey: String): List<GrantSet> =
        jdbc.sql("SELECT level, target_id FROM identity_role_assignment WHERE role_key = :role").param("role", roleKey)
            .query { rs, _ -> GrantLevel.valueOf(rs.getString("level")) to rs.uuid("target_id")!! }.list()
            .map { (level, target) -> find(level, if (level == GrantLevel.SYSTEM) null else target) }
}

class JdbcProjectMembershipRepository(private val jdbc: JdbcClient) : ProjectMembershipRepository {

    override fun findById(id: MembershipId): ProjectMembership? = many("WHERE id = :id", mapOf("id" to id.value)).firstOrNull()

    override fun findByProject(projectId: UUID): List<ProjectMembership> = many("WHERE project_id = :p ORDER BY added_at", mapOf("p" to projectId))

    override fun findByPrincipal(type: PrincipalType, principalId: UUID): List<ProjectMembership> =
        many("WHERE principal_type = :type AND principal_id = :id ORDER BY project_key", mapOf("type" to type.name, "id" to principalId))

    override fun findForUser(userId: UserId, groupIds: Collection<GroupId>): List<ProjectMembership> {
        val direct = findByPrincipal(PrincipalType.USER, userId.value)
        if (groupIds.isEmpty()) return direct
        return direct + many("WHERE principal_type = 'GROUP' AND principal_id IN (:ids)", mapOf("ids" to groupIds.map { it.value }))
    }

    override fun findByRole(roleKey: String): List<ProjectMembership> = many("WHERE role_key = :role", mapOf("role" to roleKey))

    override fun save(membership: ProjectMembership): ProjectMembership {
        jdbc.sql(
            """
            INSERT INTO identity_project_membership (id, project_id, project_key, principal_type, principal_id, role_key, added_at,
                                                     added_by_kind, added_by_id, added_by_name)
            VALUES (:id, :projectId, :projectKey, :type, :principalId, :role, :addedAt, :byKind, :byId, :byName)
            ON CONFLICT (id) DO UPDATE SET role_key = EXCLUDED.role_key, project_key = EXCLUDED.project_key
            """.trimIndent(),
        )
            .param("id", membership.id.value).param("projectId", membership.projectId).param("projectKey", membership.projectKey)
            .param("type", membership.principalType.name).param("principalId", membership.principalId).param("role", membership.roleKey)
            .param("addedAt", JdbcSupport.ts(membership.addedAt)).param("byKind", membership.addedBy.kind.name)
            .param("byId", membership.addedBy.userId).param("byName", membership.addedBy.displayName)
            .update()
        return membership
    }

    override fun delete(id: MembershipId) {
        jdbc.sql("DELETE FROM identity_project_membership WHERE id = :id").param("id", id.value).update()
    }

    override fun deleteByProject(projectId: UUID): Int =
        jdbc.sql("DELETE FROM identity_project_membership WHERE project_id = :p").param("p", projectId).update()

    override fun deleteByPrincipal(type: PrincipalType, principalId: UUID): Int =
        jdbc.sql("DELETE FROM identity_project_membership WHERE principal_type = :type AND principal_id = :id")
            .param("type", type.name).param("id", principalId).update()

    private fun many(clause: String, params: Map<String, Any>): List<ProjectMembership> =
        jdbc.sql("SELECT * FROM identity_project_membership $clause").params(params).query { rs, _ ->
            ProjectMembership(
                id = MembershipId(rs.uuid("id")!!),
                projectId = rs.uuid("project_id")!!,
                projectKey = rs.getString("project_key"),
                principalType = PrincipalType.valueOf(rs.getString("principal_type")),
                principalId = rs.uuid("principal_id")!!,
                roleKey = rs.getString("role_key"),
                addedAt = rs.instant("added_at")!!,
                addedBy = JdbcSupport.actor(rs, "added_by"),
            )
        }.list()
}

class JdbcPersonalTokenRepository(private val jdbc: JdbcClient) : PersonalTokenRepository {

    override fun findById(id: TokenId): PersonalAccessToken? = many("WHERE id = :id", mapOf("id" to id.value)).firstOrNull()

    override fun findByHash(hash: String): PersonalAccessToken? = many("WHERE token_hash = :hash", mapOf("hash" to hash)).firstOrNull()

    override fun findByUser(userId: UserId): List<PersonalAccessToken> = many("WHERE user_id = :u ORDER BY created_at DESC", mapOf("u" to userId.value))

    override fun save(token: PersonalAccessToken): PersonalAccessToken {
        jdbc.sql(
            """
            INSERT INTO identity_personal_token (id, user_id, name, token_hash, prefix, permissions, expires_at, created_at, last_used_at, revoked_at,
                mcp_gate_decisions)
            VALUES (:id, :userId, :name, :hash, :prefix, string_to_array(:permissions, ','), :expiresAt, :createdAt, :lastUsedAt, :revokedAt,
                :mcpGateDecisions)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, last_used_at = EXCLUDED.last_used_at, revoked_at = EXCLUDED.revoked_at
            """.trimIndent(),
        )
            .param("id", token.id.value).param("userId", token.userId.value).param("name", token.name).param("hash", token.hash)
            .param("prefix", token.prefix).param("permissions", JdbcSupport.csv(token.permissions))
            .param("expiresAt", JdbcSupport.ts(token.expiresAt)).param("createdAt", JdbcSupport.ts(token.createdAt))
            .param("lastUsedAt", JdbcSupport.ts(token.lastUsedAt)).param("revokedAt", JdbcSupport.ts(token.revokedAt))
            .param("mcpGateDecisions", token.allowMcpGateDecisions)
            .update()
        return token
    }

    override fun revokeAllOf(userId: UserId, at: Instant): Int =
        jdbc.sql("UPDATE identity_personal_token SET revoked_at = :at WHERE user_id = :u AND revoked_at IS NULL")
            .param("at", JdbcSupport.ts(at)).param("u", userId.value).update()

    override fun touch(id: TokenId, at: Instant) {
        jdbc.sql("UPDATE identity_personal_token SET last_used_at = :at WHERE id = :id").param("at", JdbcSupport.ts(at)).param("id", id.value).update()
    }

    private fun many(clause: String, params: Map<String, Any>): List<PersonalAccessToken> =
        jdbc.sql("SELECT * FROM identity_personal_token $clause").params(params).query { rs, _ ->
            PersonalAccessToken(
                id = TokenId(rs.uuid("id")!!),
                userId = UserId(rs.uuid("user_id")!!),
                name = rs.getString("name"),
                hash = rs.getString("token_hash"),
                prefix = rs.getString("prefix"),
                permissions = rs.strings("permissions"),
                expiresAt = rs.instant("expires_at")!!,
                createdAt = rs.instant("created_at")!!,
                lastUsedAt = rs.instant("last_used_at"),
                revokedAt = rs.instant("revoked_at"),
                allowMcpGateDecisions = rs.getBoolean("mcp_gate_decisions"),
            )
        }.list()
}

class JdbcPlatformSettingsRepository(private val jdbc: JdbcClient, private val clock: Clock, private val json: JsonMapper) : PlatformSettingsRepository {

    override fun load(): PlatformSettings? = jdbc.sql("SELECT * FROM identity_platform_settings WHERE id = 1").query { rs, _ ->
        PlatformSettings(
            registration = RegistrationMode.valueOf(rs.getString("registration")),
            allowedEmailDomains = rs.strings("allowed_email_domains").sorted(),
            syncGroupsFromIdentityProvider = rs.getBoolean("sync_groups"),
            personalTokenMaxDays = rs.getInt("personal_token_max_days"),
            localLoginEnabled = rs.getBoolean("local_login_enabled"),
            mfaRequiredForLocal = rs.getBoolean("mfa_required_local"),
            passwordPolicy = readPolicy(rs.getString("password_policy")),
            lockout = LockoutSettings(rs.getInt("lockout_max_failures"), rs.getInt("lockout_minutes")),
            specKitAllowed = rs.getBoolean("spec_kit_allowed"),
        )
    }.optional().orElse(null)

    override fun save(settings: PlatformSettings) {
        jdbc.sql(
            """
            INSERT INTO identity_platform_settings (id, registration, allowed_email_domains, sync_groups, personal_token_max_days, updated_at,
                                                    local_login_enabled, mfa_required_local, password_policy, lockout_max_failures, lockout_minutes,
                                                    spec_kit_allowed)
            VALUES (1, :registration, string_to_array(:domains, ','), :sync, :maxDays, :updatedAt,
                    :localLogin, :mfaRequired, CAST(:policy AS jsonb), :maxFailures, :lockMinutes, :specKitAllowed)
            ON CONFLICT (id) DO UPDATE SET registration = EXCLUDED.registration, allowed_email_domains = EXCLUDED.allowed_email_domains,
                sync_groups = EXCLUDED.sync_groups, personal_token_max_days = EXCLUDED.personal_token_max_days, updated_at = EXCLUDED.updated_at,
                local_login_enabled = EXCLUDED.local_login_enabled, mfa_required_local = EXCLUDED.mfa_required_local,
                password_policy = EXCLUDED.password_policy, lockout_max_failures = EXCLUDED.lockout_max_failures, lockout_minutes = EXCLUDED.lockout_minutes,
                spec_kit_allowed = EXCLUDED.spec_kit_allowed
            """.trimIndent(),
        )
            .param("registration", settings.registration.name).param("domains", JdbcSupport.csv(settings.allowedEmailDomains))
            .param("sync", settings.syncGroupsFromIdentityProvider).param("maxDays", settings.personalTokenMaxDays)
            .param("updatedAt", JdbcSupport.ts(clock.now()))
            .param("localLogin", settings.localLoginEnabled).param("mfaRequired", settings.mfaRequiredForLocal)
            .param("policy", writePolicy(settings.passwordPolicy))
            .param("specKitAllowed", settings.specKitAllowed)
            .param("maxFailures", settings.lockout.maxFailures).param("lockMinutes", settings.lockout.lockMinutes)
            .update()
    }

    private fun writePolicy(p: PasswordPolicy): String = json.writeValueAsString(
        mapOf("minLength" to p.minLength, "maxLength" to p.maxLength, "rejectCommon" to p.rejectCommon, "rejectContainingEmail" to p.rejectContainingEmail),
    )

    private fun readPolicy(text: String?): PasswordPolicy {
        if (text.isNullOrBlank()) return PasswordPolicy()
        return runCatching {
            val map = json.readValue(text, Map::class.java)
            val defaults = PasswordPolicy()
            PasswordPolicy(
                minLength = (map["minLength"] as? Number)?.toInt() ?: defaults.minLength,
                maxLength = (map["maxLength"] as? Number)?.toInt() ?: defaults.maxLength,
                rejectCommon = map["rejectCommon"] as? Boolean ?: defaults.rejectCommon,
                rejectContainingEmail = map["rejectContainingEmail"] as? Boolean ?: defaults.rejectContainingEmail,
            )
        }.getOrDefault(PasswordPolicy())
    }
}

/**
 * Global access version: incremented in the caller's transaction (so other instances see it after commit)
 * and locally at once (so this instance invalidates its cache immediately). Reads hit the database at most
 * every [refreshMillis] ms.
 */
class JdbcAccessVersion(private val jdbc: JdbcClient, private val refreshMillis: Long = 2_000, private val signals: ClusterSignals? = null) : AccessVersion {

    private val local = AtomicLong(0)
    @Volatile private var readAt = 0L

    init {
        // another replica changed access: re-read the version at the next request instead of after refreshMillis
        signals?.subscribe(TOPIC) { readAt = 0L }
    }

    override fun current(): Long {
        val now = System.currentTimeMillis()
        if (now - readAt > refreshMillis) {
            runCatching { jdbc.sql("SELECT version FROM identity_access_version WHERE id = 1").query(Long::class.javaObjectType).single() }
                .onSuccess { db -> local.accumulateAndGet(db) { a, b -> maxOf(a, b) } }
            readAt = now
        }
        return local.get()
    }

    override fun increment() {
        jdbc.sql("UPDATE identity_access_version SET version = version + 1 WHERE id = 1").update()
        local.incrementAndGet()
        signals?.publish(TOPIC, "")
    }

    companion object {
        /** Cluster signal topic (`backoffice_cache`) announcing an access change. */
        const val TOPIC = "identity-access-version"
    }
}

/**
 * Project keys for projects identity has not seen yet through a membership (created before iteration 3):
 * a read-only lookup in the Projects context's `project` table. Only the key column is read; if that table
 * is not there (identity tests) the lookup returns `null`.
 */
class JdbcProjectKeyLookup(private val jdbc: JdbcClient) : ProjectKeyLookup {
    private val log = LoggerFactory.getLogger(JdbcProjectKeyLookup::class.java)

    override fun keyOf(projectId: UUID): String? = runCatching {
        // checked first: a failing statement would abort the caller's PostgreSQL transaction
        val present = jdbc.sql("SELECT to_regclass('project') IS NOT NULL").query(Boolean::class.javaObjectType).single()
        if (!present) null
        else jdbc.sql("SELECT key FROM project WHERE id = :id").param("id", projectId).query(String::class.java).optional().orElse(null)
    }.onFailure { log.debug("project key lookup failed projectId={} error={}", projectId, it.message) }.getOrNull()

    override fun isPersonal(projectId: UUID): Boolean = runCatching {
        val present = jdbc.sql("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'project' AND column_name = 'personal')").query(Boolean::class.javaObjectType).single()
        present && jdbc.sql("SELECT personal FROM project WHERE id = :id").param("id", projectId).query(Boolean::class.javaObjectType).optional().orElse(false)
    }.onFailure { log.debug("project privacy lookup failed projectId={} error={}", projectId, it.message) }.getOrDefault(false)
}
