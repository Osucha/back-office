package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.LocalAccountView
import pl.mos.backoffice.identity.application.port.`in`.UserDetails
import pl.mos.backoffice.identity.application.port.`in`.UserMembershipView
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.PermissionCatalog
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.ProjectKeyLookup
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.CatalogValidator
import pl.mos.backoffice.identity.domain.service.PermissionResolver
import pl.mos.backoffice.identity.domain.service.PlatformResolution
import pl.mos.backoffice.identity.domain.service.ProjectAccess
import pl.mos.backoffice.identity.domain.service.ProjectPermissionResolver
import pl.mos.backoffice.identity.domain.service.ProjectResolution
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.util.UUID

/** Configuration handed to the identity use cases by the adapter (from `backoffice.auth.*`). */
data class IdentityOptions(
    /** E-mails that receive `platform-admin` on their first login (normalised to lower case). */
    val bootstrapAdmins: Set<String> = emptySet(),
    /** Used until an administrator saves platform settings. */
    val defaultSettings: PlatformSettings = PlatformSettings(),
    val local: LocalAuthOptions = LocalAuthOptions(),
) {
    val normalisedBootstrapAdmins: Set<String> = bootstrapAdmins.map(User::normaliseEmail).filter { it.isNotEmpty() }.toSet()
}

/** `backoffice.auth.local.*` plus what the use cases need to know about OIDC (ADR 0021). */
data class LocalAuthOptions(
    /** Hard switch: when false, local login is unavailable whatever the platform setting says. */
    val enabled: Boolean = true,
    /** An OIDC issuer is configured (then OIDC login is available). */
    val oidcConfigured: Boolean = false,
    val resetLinkValidity: Duration = Duration.ofHours(24),
    val bootstrapAdminEmail: String? = null,
    val bootstrapAdminPassword: String? = null,
    /** Issuer label in authenticator apps. */
    val totpIssuer: String = "Back Office",
)

/** Stored platform settings, or the configured defaults when an administrator never saved any. */
class SettingsProvider(private val repository: PlatformSettingsRepository, private val options: IdentityOptions) {
    fun current(): PlatformSettings = repository.load() ?: options.defaultSettings.normalised()
}

/** The catalogue validator over the current catalogue (contributions are fixed after startup). */
class CatalogSupport(private val catalog: PermissionCatalog) {
    fun validator(): CatalogValidator = CatalogValidator(catalog.definitions())
    fun platformKeys(): List<String> = catalog.definitions().filter { it.scope == PermissionScope.PLATFORM }.map { it.key }
    fun projectKeys(): List<String> = catalog.definitions().filter { it.scope == PermissionScope.PROJECT }.map { it.key }
}

/** A user's platform and project permissions, resolved from grants, roles and memberships. */
data class ResolvedAccess(
    val user: User,
    val groups: List<Group>,
    val platform: PlatformResolution,
    val projects: ProjectResolution,
) {
    fun toCurrentUser(): CurrentUser = CurrentUser(
        kind = PrincipalKind.USER,
        userId = user.id,
        login = user.email,
        displayName = user.displayName,
        platformPermissions = platform.effective,
        projectPermissions = projects.projects.mapValues { it.value.permissions },
        allProjects = projects.allProjects,
        projectRoles = projects.projects.mapValues { it.value.roles },
    )
}

/** Loads everything the resolvers need for one user. Not a use case: callers check permissions. */
class AccessResolver(
    private val groups: GroupRepository,
    private val roles: RoleRepository,
    private val grants: GrantRepository,
    private val memberships: ProjectMembershipRepository,
    private val catalog: CatalogSupport,
) {
    private val platformResolver = PermissionResolver()
    private val projectResolver = ProjectPermissionResolver()

    fun resolve(user: User): ResolvedAccess {
        val userGroups = groups.findByIds(user.groupIds).sortedBy { it.name.lowercase() }
        val roleMap = roles.findAll().associateBy { it.key }
        val platform = platformResolver.resolve(
            catalogue = catalog.platformKeys(),
            system = grants.find(GrantLevel.SYSTEM, null),
            groups = userGroups.map { it to grants.find(GrantLevel.GROUP, it.id.value) },
            user = grants.find(GrantLevel.USER, user.id.value),
            roles = roleMap,
        )
        val projects = projectResolver.resolve(memberships.findForUser(user.id, user.groupIds), roleMap, platform.effective)
        return ResolvedAccess(user, userGroups, platform, projects)
    }
}

/** Project keys for display: from memberships first, then the adapter's lookup. */
class ProjectKeys(private val memberships: ProjectMembershipRepository, private val lookup: ProjectKeyLookup) {
    fun keyOf(projectId: UUID): String? =
        memberships.findByProject(projectId).firstOrNull()?.projectKey ?: lookup.keyOf(projectId)

    fun describe(access: Map<UUID, Set<String>>, roles: Map<UUID, Set<String>>, known: Map<UUID, String>): List<ProjectAccess> =
        (access.keys + roles.keys).distinct().map { id ->
            ProjectAccess(id, known[id] ?: keyOf(id) ?: id.toString(), roles[id] ?: emptySet(), access[id] ?: emptySet())
        }.sortedBy { it.projectKey }
}

/**
 * "An admin cannot deactivate themselves or remove the last platform-admin." Holders are ACTIVE users with
 * `platform-admin` assigned directly (USER level) or through a group (GROUP level). A change is refused when
 * there is at least one holder before it and none after it.
 */
class PlatformAdmins(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val grants: GrantRepository,
) {
    data class Change(
        val deactivated: UserId? = null,
        val userRoles: Pair<UserId, Set<String>>? = null,
        val userGroups: Pair<UserId, Set<GroupId>>? = null,
        val groupRoles: Pair<GroupId, Set<String>>? = null,
        val groupMembers: Pair<GroupId, Set<UserId>>? = null,
        val deletedGroup: GroupId? = null,
    )

    fun holders(change: Change = Change()): Set<UserId> {
        val assignments = grants.targetsWithRole(BuiltInRoles.PLATFORM_ADMIN)
        val direct = assignments.filter { it.level == GrantLevel.USER }.mapNotNull { it.targetId?.let(::UserId) }.toMutableSet()
        change.userRoles?.let { (user, roles) -> if (BuiltInRoles.PLATFORM_ADMIN in roles) direct += user else direct -= user }
        val adminGroups = assignments.filter { it.level == GrantLevel.GROUP }.mapNotNull { it.targetId?.let(::GroupId) }.toMutableSet()
        change.groupRoles?.let { (group, roles) -> if (BuiltInRoles.PLATFORM_ADMIN in roles) adminGroups += group else adminGroups -= group }
        change.deletedGroup?.let { adminGroups -= it }
        val viaGroups = adminGroups.flatMap { id ->
            if (change.groupMembers?.first == id) change.groupMembers.second else groups.findById(id)?.memberIds ?: emptySet()
        }.toMutableSet()
        change.userGroups?.let { (user, groupIds) -> if (groupIds.any { it in adminGroups }) viaGroups += user else viaGroups -= user }
        return users.findByIds(direct + viaGroups)
            .filter { it.status == UserStatus.ACTIVE && it.id != change.deactivated }
            .map { it.id }
            .toSet()
    }

    fun ensureRemains(change: Change) {
        if (holders().isNotEmpty() && holders(change).isEmpty()) {
            throw InvalidStateException("the last platform administrator cannot be removed or deactivated")
        }
    }
}

/** Builds [UserDetails] (groups, USER-level roles, memberships, local-account state). Not a use case. */
class UserDetailsAssembler(
    private val groups: GroupRepository,
    private val grants: GrantRepository,
    private val memberships: ProjectMembershipRepository,
    private val local: LocalAccountSupport?,
) {
    fun details(user: User, setPasswordToken: String? = null): UserDetails {
        val userGroups = groups.findByIds(user.groupIds).sortedBy { it.name.lowercase() }
        val direct = memberships.findByPrincipal(PrincipalType.USER, user.id.value).map { UserMembershipView(it, null) }
        val viaGroups = userGroups.flatMap { group -> memberships.findByPrincipal(PrincipalType.GROUP, group.id.value).map { UserMembershipView(it, group) } }
        return UserDetails(
            user = user,
            groups = userGroups,
            platformRoles = grants.find(GrantLevel.USER, user.id.value).roles,
            memberships = (direct + viaGroups).sortedBy { it.membership.projectKey },
            local = local?.view(user) ?: LocalAccountView.NONE,
            setPasswordToken = setPasswordToken,
        )
    }
}

internal fun Role.isPlatform() = scope == PermissionScope.PLATFORM
internal fun Role.isProject() = scope == PermissionScope.PROJECT
