package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.EffectivePermissionsQuery
import pl.mos.backoffice.identity.application.port.`in`.EffectivePermissionsView
import pl.mos.backoffice.identity.application.port.`in`.GrantSetView
import pl.mos.backoffice.identity.application.port.`in`.GroupView
import pl.mos.backoffice.identity.application.port.`in`.ManageGrants
import pl.mos.backoffice.identity.application.port.`in`.ManageGroups
import pl.mos.backoffice.identity.application.port.`in`.ManageRoles
import pl.mos.backoffice.identity.application.port.`in`.SeedIdentity
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.security.UserId

class GroupAdminService(
    private val groups: GroupRepository,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val grants: GrantRepository,
    private val memberships: ProjectMembershipRepository,
    private val admins: PlatformAdmins,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val clock: Clock,
    private val gate: AuthorizationGate,
) : ManageGroups {

    override fun list(): List<GroupView> {
        gate.require(IdentityPermissions.USERS_VIEW)
        return groups.findAll().sortedBy { it.name.lowercase() }.map(::view)
    }

    override fun get(id: GroupId): GroupView {
        gate.require(IdentityPermissions.USERS_VIEW)
        return view(load(id))
    }

    override fun create(command: ManageGroups.GroupCommand): GroupView {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val platformRoles = command.platformRoles ?: emptySet()
        if (platformRoles.isNotEmpty()) gate.require(IdentityPermissions.ROLES_MANAGE)
        val name = command.name.trim()
        if (groups.findByName(name) != null) throw InvalidStateException("a group named $name already exists")
        requireUsers(command.memberIds)
        requirePlatformRoles(platformRoles)
        val group = groups.save(
            Group(GroupId.new(), name, command.description?.trim()?.ifEmpty { null }, command.memberIds, command.externalGroup?.trim()?.ifEmpty { null }, clock.now()),
        )
        if (platformRoles.isNotEmpty()) grants.save(GrantSet(GrantLevel.GROUP, group.id.value, platformRoles))
        audit.record(
            AuditEntry(
                IdentityAudit.GROUP_CREATE, IdentityAudit.ENTITY_GROUP, group.id.toString(),
                details = mapOf(
                    "name" to name, "memberIds" to group.memberIds.map { it.toString() }.sorted(),
                    "platformRoles" to platformRoles.sorted(), "externalGroup" to group.externalGroup,
                ),
            ),
        )
        accessVersion.increment()
        return view(group)
    }

    override fun update(id: GroupId, command: ManageGroups.GroupCommand): GroupView {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val group = load(id)
        val currentRoles = grants.find(GrantLevel.GROUP, id.value).roles
        val newRoles = command.platformRoles ?: currentRoles
        if (newRoles != currentRoles) {
            gate.require(IdentityPermissions.ROLES_MANAGE)
            requirePlatformRoles(newRoles)
        }
        val name = command.name.trim()
        if (!name.equals(group.name, ignoreCase = true) && groups.findByName(name) != null) throw InvalidStateException("a group named $name already exists")
        if (command.memberIds != group.memberIds) requireUsers(command.memberIds)
        val updated = group.copy(
            name = name,
            description = command.description?.trim()?.ifEmpty { null },
            memberIds = command.memberIds,
            externalGroup = command.externalGroup?.trim()?.ifEmpty { null },
        )
        val changes = AuditableChanges.changed(
            "name" to (group.name to updated.name),
            "description" to (group.description to updated.description),
            "memberIds" to (group.memberIds.map { it.toString() }.sorted() to updated.memberIds.map { it.toString() }.sorted()),
            "externalGroup" to (group.externalGroup to updated.externalGroup),
            "platformRoles" to (currentRoles.sorted() to newRoles.sorted()),
        )
        if (changes.isEmpty()) return view(group)
        admins.ensureRemains(PlatformAdmins.Change(groupRoles = id to newRoles, groupMembers = id to updated.memberIds))
        val saved = groups.save(updated)
        if (newRoles != currentRoles) grants.save(grants.find(GrantLevel.GROUP, id.value).copy(roles = newRoles))
        audit.record(AuditEntry(IdentityAudit.GROUP_UPDATE, IdentityAudit.ENTITY_GROUP, id.toString(), details = changes))
        accessVersion.increment()
        return view(saved)
    }

    override fun delete(id: GroupId) {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val group = load(id)
        val projects = memberships.findByPrincipal(PrincipalType.GROUP, id.value)
        if (projects.isNotEmpty()) {
            throw InvalidStateException("group ${group.name} is a member of projects ${projects.map { it.projectKey }.sorted()}; remove those memberships first")
        }
        admins.ensureRemains(PlatformAdmins.Change(deletedGroup = id))
        grants.deleteTarget(GrantLevel.GROUP, id.value)
        groups.delete(id)
        audit.record(AuditEntry(IdentityAudit.GROUP_DELETE, IdentityAudit.ENTITY_GROUP, id.toString(), details = mapOf("name" to group.name)))
        accessVersion.increment()
    }

    private fun load(id: GroupId): Group = groups.findById(id) ?: throw NotFoundException("group", id)

    private fun view(group: Group) = GroupView(group, grants.find(GrantLevel.GROUP, group.id.value).roles)

    private fun requireUsers(ids: Set<UserId>) {
        val missing = ids - users.findByIds(ids).map { it.id }.toSet()
        if (missing.isNotEmpty()) throw ValidationException("unknown users $missing", "memberIds")
    }

    private fun requirePlatformRoles(keys: Set<String>) {
        val roleMap = roles.findAll().associateBy { it.key }
        val invalid = keys.filter { roleMap[it]?.isPlatform() != true }
        if (invalid.isNotEmpty()) throw ValidationException("unknown platform roles $invalid", "platformRoles")
    }
}

class RoleAdminService(
    private val roles: RoleRepository,
    private val grants: GrantRepository,
    private val memberships: ProjectMembershipRepository,
    private val catalog: CatalogSupport,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val gate: AuthorizationGate,
) : ManageRoles {

    @PublicOperation("role names and bundles are needed by every member and token picker; they are not sensitive")
    override fun list(scope: PermissionScope?): List<Role> =
        roles.findAll().filter { scope == null || it.scope == scope }.sortedWith(compareBy({ it.scope }, { !it.builtIn }, { it.key }))

    override fun create(command: ManageRoles.RoleCommand): Role {
        gate.require(IdentityPermissions.ROLES_MANAGE)
        if (roles.findByKey(command.key) != null) throw InvalidStateException("role ${command.key} already exists")
        val role = Role(command.key, command.name.trim(), command.description?.trim()?.ifEmpty { null }, command.scope, command.permissions, builtIn = false)
        catalog.validator().validateRole(role)
        val saved = roles.save(role)
        audit.record(
            AuditEntry(
                IdentityAudit.ROLE_CREATE, IdentityAudit.ENTITY_ROLE, role.key,
                details = mapOf("name" to role.name, "scope" to role.scope.name, "permissions" to role.permissions.sorted()),
            ),
        )
        accessVersion.increment()
        return saved
    }

    override fun update(key: String, command: ManageRoles.RoleCommand): Role {
        gate.require(IdentityPermissions.ROLES_MANAGE)
        val existing = roles.findByKey(key) ?: throw NotFoundException("role", key)
        if (command.key != key) throw ValidationException("the role key cannot be changed", "key")
        if (command.scope != existing.scope) throw ValidationException("the role scope cannot be changed", "scope")
        val updated = existing.copy(name = command.name.trim(), description = command.description?.trim()?.ifEmpty { null }, permissions = command.permissions)
        catalog.validator().validateRole(updated, previous = existing)
        val changes = AuditableChanges.changed(
            "name" to (existing.name to updated.name),
            "description" to (existing.description to updated.description),
            "permissions" to (existing.permissions.sorted() to updated.permissions.sorted()),
        )
        if (changes.isEmpty()) return existing
        val saved = roles.save(updated)
        audit.record(AuditEntry(IdentityAudit.ROLE_UPDATE, IdentityAudit.ENTITY_ROLE, key, details = changes))
        accessVersion.increment()
        return saved
    }

    override fun delete(key: String) {
        gate.require(IdentityPermissions.ROLES_MANAGE)
        val existing = roles.findByKey(key) ?: throw NotFoundException("role", key)
        if (existing.builtIn) throw InvalidStateException("built-in role $key cannot be deleted")
        if (grants.targetsWithRole(key).isNotEmpty() || memberships.findByRole(key).isNotEmpty()) {
            throw InvalidStateException("role $key is still assigned; unassign it first")
        }
        roles.delete(key)
        audit.record(AuditEntry(IdentityAudit.ROLE_DELETE, IdentityAudit.ENTITY_ROLE, key, details = mapOf("name" to existing.name)))
        accessVersion.increment()
    }
}

class GrantAdminService(
    private val grants: GrantRepository,
    private val roles: RoleRepository,
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val memberships: ProjectMembershipRepository,
    private val access: AccessResolver,
    private val projectKeys: ProjectKeys,
    private val catalog: CatalogSupport,
    private val admins: PlatformAdmins,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val gate: AuthorizationGate,
) : ManageGrants, EffectivePermissionsQuery {

    override fun list(level: GrantLevel?, targetId: java.util.UUID?): List<GrantSetView> {
        gate.require(IdentityPermissions.USERS_VIEW)
        val sets = when {
            level == GrantLevel.SYSTEM -> listOf(grants.find(GrantLevel.SYSTEM, null))
            level != null && targetId != null -> listOf(grants.find(level, targetId))
            else -> grants.findAll().filter { (level == null || it.level == level) && (targetId == null || it.targetId == targetId) }
                .ifEmpty { if (level == null && targetId == null) listOf(grants.find(GrantLevel.SYSTEM, null)) else emptyList() }
        }
        return sets.sortedWith(compareBy({ it.level }, { it.targetId?.toString() ?: "" })).map { GrantSetView(it, targetName(it)) }
    }

    override fun replace(set: GrantSet): GrantSetView {
        gate.require(IdentityPermissions.ROLES_MANAGE)
        val targetName = when (set.level) {
            GrantLevel.SYSTEM -> null
            GrantLevel.GROUP -> (groups.findById(GroupId(set.targetId!!)) ?: throw NotFoundException("group", set.targetId)).name
            GrantLevel.USER -> (users.findById(UserId(set.targetId!!)) ?: throw NotFoundException("user", set.targetId)).displayName
        }
        val roleMap = roles.findAll().associateBy { it.key }
        catalog.validator().validateGrantSet(set, roleMap)
        val previous = grants.find(set.level, set.targetId)
        if (previous.roles != set.roles) {
            when (set.level) {
                GrantLevel.USER -> admins.ensureRemains(PlatformAdmins.Change(userRoles = UserId(set.targetId!!) to set.roles))
                GrantLevel.GROUP -> admins.ensureRemains(PlatformAdmins.Change(groupRoles = GroupId(set.targetId!!) to set.roles))
                GrantLevel.SYSTEM -> Unit
            }
        }
        grants.save(set)

        val entityId = set.targetId?.toString() ?: "SYSTEM"
        val base = mapOf("level" to set.level.name, "targetId" to set.targetId?.toString(), "targetName" to targetName)
        (previous.grants.map { it.permission } + set.grants.map { it.permission }).toSortedSet().forEach { permission ->
            val old = previous.effectOf(permission)
            val new = set.effectOf(permission)
            if (old != new) {
                audit.record(
                    AuditEntry(
                        IdentityAudit.GRANT_CHANGE, IdentityAudit.ENTITY_GRANT, entityId,
                        details = base + mapOf("permission" to permission, "old" to (old?.name ?: "NONE"), "new" to (new?.name ?: "NONE")),
                    ),
                )
            }
        }
        (set.roles - previous.roles).sorted().forEach {
            audit.record(AuditEntry(IdentityAudit.ROLE_ASSIGN, IdentityAudit.ENTITY_GRANT, entityId, details = base + ("role" to it)))
        }
        (previous.roles - set.roles).sorted().forEach {
            audit.record(AuditEntry(IdentityAudit.ROLE_UNASSIGN, IdentityAudit.ENTITY_GRANT, entityId, details = base + ("role" to it)))
        }
        accessVersion.increment()
        return GrantSetView(grants.find(set.level, set.targetId), targetName)
    }

    override fun of(userId: UserId): EffectivePermissionsView {
        gate.require(IdentityPermissions.USERS_VIEW)
        val user = users.findById(userId) ?: throw NotFoundException("user", userId)
        val resolved = access.resolve(user)
        val descriptions = catalog.validator().let { validator ->
            resolved.platform.rows.associate { it.permission to (validator.definition(it.permission)?.description ?: "") }
        }
        val known = memberships.findForUser(user.id, user.groupIds).associate { it.projectId to it.projectKey }
        val projects = projectKeys.describe(
            resolved.projects.projects.mapValues { it.value.permissions },
            resolved.projects.projects.mapValues { it.value.roles },
            known,
        )
        return EffectivePermissionsView(userId, resolved.platform.rows, descriptions, projects)
    }

    private fun targetName(set: GrantSet): String? = when (set.level) {
        GrantLevel.SYSTEM -> null
        GrantLevel.GROUP -> set.targetId?.let { groups.findById(GroupId(it))?.name }
        GrantLevel.USER -> set.targetId?.let { users.findById(UserId(it))?.displayName }
    }
}

/**
 * Startup seeding (idempotent): creates missing built-in roles; on the very first start (no built-in role
 * stored yet) assigns the `member` role at SYSTEM level. Later admin edits are never overwritten.
 */
class SeedIdentityService(
    private val roles: RoleRepository,
    private val grants: GrantRepository,
    private val catalog: CatalogSupport,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
) : SeedIdentity {

    @SystemOperation("startup seeding of built-in roles")
    override fun seed() {
        val existing = roles.findAll().associateBy { it.key }
        val firstStart = existing.values.none { it.builtIn }
        var changed = false
        BuiltInRoles.all.filter { it.key !in existing }.forEach { builtIn ->
            val role = if (builtIn.key == BuiltInRoles.PLATFORM_ADMIN) builtIn.copy(permissions = builtIn.permissions + catalog.platformKeys()) else builtIn
            roles.save(role)
            changed = true
            audit.record(
                AuditEntry(
                    IdentityAudit.ROLE_CREATE, IdentityAudit.ENTITY_ROLE, role.key,
                    details = mapOf("builtIn" to true, "scope" to role.scope.name, "permissions" to role.permissions.sorted()),
                ),
            )
        }
        // platform-admin holds every platform permission: permissions contributed by modules added in a later
        // release (e.g. fleet.*) are granted on upgrade. Only additions; nothing is ever taken away.
        existing[BuiltInRoles.PLATFORM_ADMIN]?.let { admin ->
            val missing = catalog.platformKeys() - admin.permissions
            if (missing.isNotEmpty()) {
                roles.save(admin.copy(permissions = admin.permissions + missing))
                changed = true
                audit.record(
                    AuditEntry(
                        IdentityAudit.ROLE_UPDATE, IdentityAudit.ENTITY_ROLE, admin.key,
                        details = mapOf("added" to missing.sorted(), "reason" to "new platform permissions"),
                    ),
                )
            }
        }
        if (firstStart) {
            val system = grants.find(GrantLevel.SYSTEM, null)
            if (BuiltInRoles.SYSTEM_DEFAULT !in system.roles) {
                grants.save(system.copy(roles = system.roles + BuiltInRoles.SYSTEM_DEFAULT))
                changed = true
                audit.record(
                    AuditEntry(
                        IdentityAudit.ROLE_ASSIGN, IdentityAudit.ENTITY_GRANT, "SYSTEM",
                        details = mapOf("level" to "SYSTEM", "role" to BuiltInRoles.SYSTEM_DEFAULT, "reason" to "seed"),
                    ),
                )
            }
        }
        if (changed) accessVersion.increment()
    }
}
