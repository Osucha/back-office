package pl.mos.backoffice.identity.application.port.out

import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant
import java.util.UUID

data class UserQuery(val q: String? = null, val status: UserStatus? = null, val groupId: GroupId? = null, val page: Int = 0, val size: Int = 20)

data class Page<T>(val content: List<T>, val page: Int, val size: Int, val totalElements: Long)

interface UserRepository {
    fun findById(id: UserId): User?
    fun findByIds(ids: Collection<UserId>): List<User>
    fun findByIdentity(issuer: String, subject: String): User?
    /** Case-insensitive. */
    fun findByEmail(email: String): User?
    fun search(query: UserQuery): Page<User>
    /** Minimal search in e-mail and display name for pickers. */
    fun lookup(q: String, limit: Int): List<User>
    fun findByStatus(status: UserStatus): List<User>
    /** Saves the user and replaces its group memberships with [User.groupIds]. */
    fun save(user: User): User
    /** True when no user exists at all (bootstrap of a local admin). */
    fun isEmpty(): Boolean = search(UserQuery(size = 1)).totalElements == 0L
}

interface GroupRepository {
    fun findById(id: GroupId): Group?
    fun findByIds(ids: Collection<GroupId>): List<Group>
    fun findAll(): List<Group>
    fun findByName(name: String): Group?
    fun lookup(q: String?, limit: Int): List<Group>
    /** Saves the group and replaces its members with [Group.memberIds]. */
    fun save(group: Group): Group
    fun delete(id: GroupId)
}

interface RoleRepository {
    fun findAll(): List<Role>
    fun findByKey(key: String): Role?
    fun save(role: Role): Role
    fun delete(key: String)
}

/** Platform grants and role assignments, one [GrantSet] per (level, target). */
interface GrantRepository {
    /** Empty set when nothing is stored. */
    fun find(level: GrantLevel, targetId: UUID?): GrantSet
    fun findAll(): List<GrantSet>
    /** Replaces everything stored for the set's target (an empty set deletes it). */
    fun save(set: GrantSet)
    fun deleteTarget(level: GrantLevel, targetId: UUID)
    /** Targets that have [roleKey] assigned. */
    fun targetsWithRole(roleKey: String): List<GrantSet>
}

interface ProjectMembershipRepository {
    fun findById(id: MembershipId): ProjectMembership?
    fun findByProject(projectId: UUID): List<ProjectMembership>
    fun findByPrincipal(type: PrincipalType, principalId: UUID): List<ProjectMembership>
    /** Direct memberships of the user plus memberships of its groups. */
    fun findForUser(userId: UserId, groupIds: Collection<GroupId>): List<ProjectMembership>
    fun findByRole(roleKey: String): List<ProjectMembership>
    fun save(membership: ProjectMembership): ProjectMembership
    fun delete(id: MembershipId)
    fun deleteByProject(projectId: UUID): Int
    fun deleteByPrincipal(type: PrincipalType, principalId: UUID): Int
}

/**
 * Key of a project for display (`/me`, member lists). Identity learns keys from `ProjectAccessProvisioner`
 * calls and memberships; the adapter may fall back to other sources for projects created earlier.
 */
fun interface ProjectKeyLookup {
    fun keyOf(projectId: UUID): String?

    /** ADR 0038: the project is a private project of its creator (members cannot be added); false when unknown. */
    fun isPersonal(projectId: UUID): Boolean = false
}

interface PersonalTokenRepository {
    fun findById(id: TokenId): PersonalAccessToken?
    fun findByHash(hash: String): PersonalAccessToken?
    fun findByUser(userId: UserId): List<PersonalAccessToken>
    fun save(token: PersonalAccessToken): PersonalAccessToken
    /** Revokes every active token of the user; returns how many were revoked. */
    fun revokeAllOf(userId: UserId, at: Instant): Int
    /** Updates `lastUsedAt` (best effort, outside business transactions). */
    fun touch(id: TokenId, at: Instant)
}

interface PlatformSettingsRepository {
    /** `null` when an administrator has never saved settings (the configured defaults apply). */
    fun load(): PlatformSettings?
    fun save(settings: PlatformSettings)
}

data class SessionSummary(
    val id: String,
    val createdAt: Instant,
    val lastAccessAt: Instant,
    val sourceIp: String?,
    val userAgent: String?,
)

/** Server-side sessions of users (Spring Session in the adapter). */
interface SessionTerminator {
    fun sessionsOf(userId: UserId): List<SessionSummary>
    /** Deletes all sessions of the user; returns how many were deleted. */
    fun terminateAll(userId: UserId): Int
    /** Deletes all sessions of the user except [keepSessionId] (the caller's own, opaque id). */
    fun terminateOthers(userId: UserId, keepSessionId: String?): Int = terminateAll(userId)
}

/**
 * A global counter incremented by every access change (users, groups, roles, grants, memberships, tokens).
 * The adapter keys its effective-permission cache by it, so a change takes effect on the next request.
 */
interface AccessVersion {
    fun current(): Long
    fun increment()
}

/** All permission definitions contributed by the modules (`PermissionCatalogContribution` beans). */
fun interface PermissionCatalog {
    fun definitions(): List<PermissionDefinition>
}

data class AuditQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val actorId: UUID? = null,
    val operation: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val projectId: UUID? = null,
    val result: AuditResult? = null,
    val correlationId: String? = null,
    /** Opaque keyset cursor returned as [AuditRecordPage.nextCursor]. */
    val cursor: String? = null,
    val size: Int = 50,
)

data class AuditRecord(
    val id: UUID,
    val eventTime: Instant,
    val actor: Actor?,
    val operation: String,
    val entityType: String,
    val entityId: String?,
    val projectId: UUID?,
    val result: AuditResult,
    val details: Map<String, Any?>,
    val correlationId: String?,
    val sourceIp: String?,
)

data class AuditRecordPage(val content: List<AuditRecord>, val nextCursor: String?)

/** Read side of the audit log (the write side is the shared, write-only `AuditPort`). */
fun interface AuditLogReader {
    fun search(query: AuditQuery): AuditRecordPage
}
