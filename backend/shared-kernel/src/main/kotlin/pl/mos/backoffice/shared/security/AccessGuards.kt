package pl.mos.backoffice.shared.security

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.EffectivePermissions
import com.osucha.portpermissions.Permission
import com.osucha.portpermissions.PermissionCatalog
import com.osucha.portpermissions.PrincipalKind as GuardPrincipalKind
import com.osucha.portpermissions.ScopeFilter
import com.osucha.portpermissions.ScopeSet
import com.osucha.portpermissions.ScopedPermission
import com.osucha.portpermissions.SystemOnlyOperationException
import pl.mos.backoffice.shared.InsufficientPermissionException
import java.util.UUID

/**
 * Who is calling, as a Back Office principal: user id, display name, roles per project and token
 * capabilities (approver rules, `createdBy` stamps, MCP capabilities). Permission checks go through the
 * [AccessGuard]; this port only answers "who am I" (ADR 0066).
 */
interface CurrentPrincipal {
    /** The caller; fails with 401 when nobody is authenticated. */
    fun currentUser(): CurrentUser
}

/** The library principal kind of a Back Office principal. */
val PrincipalKind.guardKind: GuardPrincipalKind
    get() = when (this) {
        PrincipalKind.USER -> GuardPrincipalKind.USER
        PrincipalKind.PERSONAL_TOKEN -> GuardPrincipalKind.DELEGATED
        PrincipalKind.CI_TOKEN -> GuardPrincipalKind.MACHINE
        PrincipalKind.SYSTEM -> GuardPrincipalKind.SYSTEM
    }

/**
 * This principal's access as a port-permissions snapshot:
 * - global: [CurrentUser.platformPermissions];
 * - per project-scoped key: every project ([ScopeSet.allExcept] with nothing excluded) for [CurrentUser.allProjects]
 *   holders, otherwise exactly the projects holding it;
 * - the system principal holds nothing (ADR 0067): system work goes through system ports and `requireSystem()`.
 */
fun CurrentUser.effectivePermissions(catalog: PermissionCatalog): EffectivePermissions {
    if (kind == PrincipalKind.SYSTEM) return EffectivePermissions.SYSTEM
    val global = platformPermissions
    val held: Map<String, Set<UUID>> = projectPermissions
        .flatMap { (projectId, keys) -> keys.map { it to projectId } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }
    val scoped: Map<String, ScopeSet> = if (allProjects) {
        (catalog.permissions.filter { it.scope != null }.map { it.key } + held.keys).associateWith { ScopeSet.allExcept() }
    } else {
        held.mapValues { (_, ids) -> ScopeSet.only(ids.map(PROJECT_SCOPE::key).toSet()) }
    }
    return EffectivePermissions(kind.guardKind, global, scoped)
}

/**
 * [AccessGuard] over a [CurrentUser] (ADR 0066): the checks read the principal directly, with the same
 * result as [effective] (`effectivePermissions`), and fail with [InsufficientPermissionException] (403 with
 * `permission` / `projectId`). The system principal holds no permissions (ADR 0067): inside `asSystem` every
 * permission check fails and only [requireSystem] passes, so `asSystem` never escalates. Subclasses say where the current
 * user comes from and how `asSystem` switches to [CurrentUser.SYSTEM].
 */
abstract class CurrentUserAccessGuard(private val catalog: PermissionCatalog) : AccessGuard, CurrentPrincipal {

    override fun effective(): EffectivePermissions = currentUser().effectivePermissions(catalog)

    override fun isGranted(permission: Permission): Boolean {
        val user = currentUser()
        return user.kind != PrincipalKind.SYSTEM && user.can(permission.key)
    }

    override fun <S : Any> isGranted(permission: ScopedPermission<S>, scope: S): Boolean {
        val user = currentUser()
        return user.kind != PrincipalKind.SYSTEM && scope is UUID && user.can(permission.key, scope)
    }

    override fun require(permission: Permission) {
        if (!isGranted(permission)) throw InsufficientPermissionException(permission.key)
    }

    override fun <S : Any> require(permission: ScopedPermission<S>, scope: S) {
        if (!isGranted(permission, scope)) throw InsufficientPermissionException(permission.key, scope as? UUID)
    }

    override fun <S : Any> scopesWith(permission: ScopedPermission<S>): ScopeFilter<S> {
        val user = currentUser()
        if (user.kind == PrincipalKind.SYSTEM) return ScopeFilter.Only(emptySet())
        if (user.allProjects) return ScopeFilter.All
        val projects = user.projectPermissions.filterValues { permission.key in it }.keys
        return ScopeFilter.Only(projects.map { permission.scopeType.parse(PROJECT_SCOPE.key(it)) }.toSet())
    }

    override fun requireSystem() {
        val kind = currentUser().kind
        if (kind != PrincipalKind.SYSTEM) throw SystemOnlyOperationException(kind.guardKind)
    }
}

/** True when the filter lets no project through: the caller holds the permission nowhere. */
val <S : Any> ScopeFilter<S>.isNothing: Boolean
    get() = this is ScopeFilter.Only && ids.isEmpty()
