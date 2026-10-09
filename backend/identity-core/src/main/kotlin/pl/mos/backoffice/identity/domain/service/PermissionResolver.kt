package pl.mos.backoffice.identity.domain.service

import pl.mos.backoffice.identity.domain.model.GrantEffect
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.shared.security.PermissionScope
import java.util.UUID

/** What one group contributes to one permission (the "Group(s)" column of the admin table). */
data class GroupDecision(val groupId: GroupId, val groupName: String, val effect: GrantEffect?)

/** One row of the System | Group(s) | User | Effective table. `null` effect = NONE (no grant at that level). */
data class PermissionDecision(
    val permission: String,
    val system: GrantEffect?,
    val groups: List<GroupDecision>,
    val user: GrantEffect?,
    val effective: Boolean,
    /** Which level decided: `USER`, `GROUP:<names>`, `SYSTEM`; `null` when nothing granted it (denied by default). */
    val source: String?,
)

data class PlatformResolution(val effective: Set<String>, val rows: List<PermissionDecision>)

/**
 * Resolves effective **platform** permissions from three-level grants (ADR 0017):
 * - per permission, the closest level that says anything wins: USER > GROUP (any of the user's groups) > SYSTEM;
 * - a role assigned at a level is an ALLOW for each of its permissions at that level;
 * - within one level DENY wins over ALLOW (across all of the user's groups at the GROUP level);
 * - no grant anywhere = denied.
 * Pure logic, no I/O.
 */
class PermissionResolver {

    fun resolve(
        catalogue: Collection<String>,
        system: GrantSet?,
        groups: List<Pair<Group, GrantSet?>>,
        user: GrantSet?,
        roles: Map<String, Role>,
    ): PlatformResolution {
        val keys = (catalogue + mentioned(system, roles) + groups.flatMap { mentioned(it.second, roles) } + mentioned(user, roles))
            .toSortedSet()
        val rows = keys.map { permission ->
            val systemEffect = effectAt(system, permission, roles)
            val groupDecisions = groups.map { (group, set) -> GroupDecision(group.id, group.name, effectAt(set, permission, roles)) }
            val groupEffect = combine(groupDecisions.map { it.effect })
            val userEffect = effectAt(user, permission, roles)
            val (winner, source) = when {
                userEffect != null -> userEffect to "USER"
                groupEffect != null -> groupEffect to "GROUP:" + groupDecisions.filter { it.effect == groupEffect }.joinToString(",") { it.groupName }
                systemEffect != null -> systemEffect to "SYSTEM"
                else -> null to null
            }
            PermissionDecision(permission, systemEffect, groupDecisions, userEffect, winner == GrantEffect.ALLOW, source)
        }
        return PlatformResolution(rows.filter { it.effective }.map { it.permission }.toSet(), rows)
    }

    private fun mentioned(set: GrantSet?, roles: Map<String, Role>): Set<String> {
        if (set == null) return emptySet()
        return set.grants.map { it.permission }.toSet() + set.roles.flatMap { platformRole(it, roles)?.permissions ?: emptySet() }
    }

    private fun effectAt(set: GrantSet?, permission: String, roles: Map<String, Role>): GrantEffect? {
        if (set == null) return null
        val explicit = set.effectOf(permission)
        if (explicit == GrantEffect.DENY) return GrantEffect.DENY
        val viaRole = set.roles.any { platformRole(it, roles)?.permissions?.contains(permission) == true }
        return if (explicit == GrantEffect.ALLOW || viaRole) GrantEffect.ALLOW else null
    }

    private fun platformRole(key: String, roles: Map<String, Role>): Role? =
        roles[key]?.takeIf { it.scope == PermissionScope.PLATFORM }

    private fun combine(effects: List<GrantEffect?>): GrantEffect? = when {
        GrantEffect.DENY in effects -> GrantEffect.DENY
        GrantEffect.ALLOW in effects -> GrantEffect.ALLOW
        else -> null
    }
}

/** A user's access to one project: role keys (direct and via groups) and the union of their permissions. */
data class ProjectAccess(val projectId: UUID, val projectKey: String, val roles: Set<String>, val permissions: Set<String>)

data class ProjectResolution(
    val projects: Map<UUID, ProjectAccess>,
    /** Holder of `admin.projects.all`: every project permission on every project. */
    val allProjects: Boolean,
)

/**
 * Effective project permissions = union of the project roles of the user's direct and group memberships.
 * `admin.projects.all` (a platform permission) turns on [ProjectResolution.allProjects].
 */
class ProjectPermissionResolver {

    fun resolve(memberships: List<ProjectMembership>, roles: Map<String, Role>, platformPermissions: Set<String>): ProjectResolution {
        val projects = memberships.groupBy { it.projectId }.mapValues { (projectId, list) ->
            val projectRoles = list.map { it.roleKey }.filter { roles[it]?.scope == PermissionScope.PROJECT }.toSet()
            ProjectAccess(
                projectId = projectId,
                projectKey = list.first().projectKey,
                roles = projectRoles,
                permissions = projectRoles.flatMap { roles.getValue(it).permissions }.toSet(),
            )
        }
        return ProjectResolution(projects, IdentityPermissions.PROJECTS_ALL in platformPermissions)
    }
}
