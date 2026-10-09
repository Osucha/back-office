package pl.mos.backoffice.identity.application.port.`in`

import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.AuditRecordPage
import pl.mos.backoffice.identity.application.port.out.Page
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.service.PermissionDecision
import pl.mos.backoffice.identity.domain.service.ProjectAccess
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.identity.domain.model.AuthMode
import java.time.Instant
import java.util.UUID

/** A project membership as shown on a user: direct or through [viaGroup]. */
data class UserMembershipView(val membership: ProjectMembership, val viaGroup: Group?)

/** Local-account state shown to administrators (ADR 0021). */
data class LocalAccountView(
    val hasPassword: Boolean = false,
    val mfaEnabled: Boolean = false,
    val lockedUntil: Instant? = null,
    val mustChangePassword: Boolean = false,
) {
    companion object {
        val NONE = LocalAccountView()
    }
}

data class UserDetails(
    val user: User,
    val groups: List<Group>,
    /** Platform roles assigned at USER level. */
    val platformRoles: Set<String>,
    val memberships: List<UserMembershipView>,
    val local: LocalAccountView = LocalAccountView.NONE,
    /** Plaintext of a SET_INITIAL link, only in the response that issued it (invite, auth-mode change). */
    val setPasswordToken: String? = null,
)

interface ManageUsers {
    fun list(query: UserQuery): Page<UserDetails>
    fun get(id: UserId): UserDetails
    fun invite(command: InviteUser): UserDetails
    fun update(id: UserId, command: UpdateUser): UserDetails
    fun activate(id: UserId): UserDetails
    fun deactivate(id: UserId): UserDetails

    data class InitialMembership(val projectId: UUID, val role: String)

    data class InviteUser(
        val email: String,
        val displayName: String? = null,
        /** `null` = OIDC when a provider is configured, otherwise LOCAL. */
        val authMode: AuthMode? = null,
        val groupIds: Set<GroupId> = emptySet(),
        val platformRoles: Set<String> = emptySet(),
        val projectMemberships: List<InitialMembership> = emptyList(),
    )

    /** `null` = unchanged. */
    data class UpdateUser(
        val displayName: String? = null,
        val groupIds: Set<GroupId>? = null,
        val platformRoles: Set<String>? = null,
    )
}

interface ManageUserSessions {
    fun list(userId: UserId): List<SessionSummary>
    fun terminateAll(userId: UserId): Int
}

data class GroupView(val group: Group, val platformRoles: Set<String>)

interface ManageGroups {
    fun list(): List<GroupView>
    fun get(id: GroupId): GroupView
    fun create(command: GroupCommand): GroupView
    fun update(id: GroupId, command: GroupCommand): GroupView
    fun delete(id: GroupId)

    data class GroupCommand(
        val name: String,
        val description: String? = null,
        val memberIds: Set<UserId> = emptySet(),
        /** `null` = leave the group's platform roles unchanged. */
        val platformRoles: Set<String>? = null,
        val externalGroup: String? = null,
    )
}

interface ManageRoles {
    fun list(scope: PermissionScope?): List<Role>
    fun create(command: RoleCommand): Role
    fun update(key: String, command: RoleCommand): Role
    fun delete(key: String)

    data class RoleCommand(
        val key: String,
        val name: String,
        val description: String?,
        val scope: PermissionScope,
        val permissions: Set<String>,
    )
}

data class GrantSetView(val set: GrantSet, val targetName: String?)

interface ManageGrants {
    fun list(level: GrantLevel?, targetId: UUID?): List<GrantSetView>
    fun replace(set: GrantSet): GrantSetView
}

data class EffectivePermissionsView(
    val userId: UserId,
    val rows: List<PermissionDecision>,
    val descriptions: Map<String, String>,
    val projects: List<ProjectAccess>,
)

interface EffectivePermissionsQuery {
    fun of(userId: UserId): EffectivePermissionsView
}

interface ManagePlatformSettings {
    fun get(): PlatformSettings
    fun update(settings: PlatformSettings): PlatformSettings
}

interface PermissionCatalogQuery {
    fun catalog(): List<PermissionDefinition>
}

interface SearchAuditLog {
    fun search(query: AuditQuery): AuditRecordPage
}

/** On startup: built-in roles and the SYSTEM default role (idempotent). */
interface SeedIdentity {
    fun seed()
}
