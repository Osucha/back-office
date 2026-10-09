package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.LookupDirectory
import pl.mos.backoffice.identity.application.port.`in`.ManageProjectMembers
import pl.mos.backoffice.identity.application.port.`in`.MemberView
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.ProjectAccessDirectory
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.security.UserId
import java.util.UUID

/**
 * Project members. Listing needs `project.view` on the project (every member may see who else is in it);
 * changes need `project.members.manage`. The last owner of a project cannot be removed or demoted.
 * Also implements the shared [ProjectAccessProvisioner] used by the Projects context.
 */
class ProjectMembershipService(
    private val memberships: ProjectMembershipRepository,
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val roles: RoleRepository,
    private val projectKeys: ProjectKeys,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val clock: Clock,
    private val gate: AuthorizationGate,
) : ManageProjectMembers, ProjectAccessProvisioner {

    override fun list(projectId: UUID): List<MemberView> {
        gate.require(PROJECT_VIEW, projectId)
        return memberships.findByProject(projectId).map(::view).sortedWith(compareBy({ it.membership.principalType }, { it.principalName.lowercase() }))
    }

    override fun add(projectId: UUID, command: ManageProjectMembers.AddMember): MemberView {
        val me = gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, projectId)
        requireProjectRole(command.role)
        requirePrincipal(command.principalType, command.principalId)
        val existing = memberships.findByProject(projectId)
        if (existing.any { it.principalType == command.principalType && it.principalId == command.principalId }) {
            throw InvalidStateException("${command.principalType.name.lowercase()} ${command.principalId} is already a member of this project")
        }
        val key = existing.firstOrNull()?.projectKey ?: projectKeys.keyOf(projectId) ?: throw NotFoundException("project", projectId)
        val saved = memberships.save(
            ProjectMembership(MembershipId.new(), projectId, key, command.principalType, command.principalId, command.role, clock.now(), me.actor),
        )
        audit.record(
            AuditEntry(
                IdentityAudit.MEMBER_ADD, IdentityAudit.ENTITY_MEMBERSHIP, saved.id.toString(), projectId = projectId,
                details = mapOf("principalType" to saved.principalType.name, "principalId" to saved.principalId.toString(), "role" to saved.roleKey),
            ),
        )
        accessVersion.increment()
        return view(saved)
    }

    override fun changeRole(projectId: UUID, memberId: MembershipId, role: String): MemberView {
        gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, projectId)
        requireProjectRole(role)
        val membership = load(projectId, memberId)
        if (membership.roleKey == role) return view(membership)
        if (membership.roleKey == BuiltInRoles.OWNER) ensureAnotherOwner(projectId, membership, "demoted")
        val saved = memberships.save(membership.copy(roleKey = role))
        audit.record(
            AuditEntry(
                IdentityAudit.MEMBER_ROLE_CHANGE, IdentityAudit.ENTITY_MEMBERSHIP, memberId.toString(), projectId = projectId,
                details = AuditableChanges.changed("role" to (membership.roleKey to role)) +
                    mapOf("principalType" to membership.principalType.name, "principalId" to membership.principalId.toString()),
            ),
        )
        accessVersion.increment()
        return view(saved)
    }

    override fun remove(projectId: UUID, memberId: MembershipId) {
        gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, projectId)
        val membership = load(projectId, memberId)
        if (membership.roleKey == BuiltInRoles.OWNER) ensureAnotherOwner(projectId, membership, "removed")
        memberships.delete(memberId)
        audit.record(
            AuditEntry(
                IdentityAudit.MEMBER_REMOVE, IdentityAudit.ENTITY_MEMBERSHIP, memberId.toString(), projectId = projectId,
                details = mapOf("principalType" to membership.principalType.name, "principalId" to membership.principalId.toString(), "role" to membership.roleKey),
            ),
        )
        accessVersion.increment()
    }

    @SystemOperation("called by the Projects context when a project is created")
    override fun grantCreator(projectId: UUID, projectKey: String, creator: Actor) {
        val userId = creator.userId ?: return
        val existing = memberships.findByProject(projectId)
        if (existing.any { it.principalType == PrincipalType.USER && it.principalId == userId }) return
        val saved = memberships.save(
            ProjectMembership(MembershipId.new(), projectId, projectKey, PrincipalType.USER, userId, BuiltInRoles.OWNER, clock.now(), creator),
        )
        audit.record(
            AuditEntry(
                IdentityAudit.MEMBER_ADD, IdentityAudit.ENTITY_MEMBERSHIP, saved.id.toString(), projectId = projectId,
                details = mapOf("principalType" to "USER", "principalId" to userId.toString(), "role" to BuiltInRoles.OWNER, "reason" to "project-created"),
            ),
        )
        accessVersion.increment()
    }

    @SystemOperation("called by the Projects context when a project is deleted")
    override fun projectDeleted(projectId: UUID) {
        val removed = memberships.deleteByProject(projectId)
        if (removed == 0) return
        audit.record(
            AuditEntry(
                IdentityAudit.MEMBER_REMOVE, IdentityAudit.ENTITY_MEMBERSHIP, null, projectId = projectId,
                details = mapOf("reason" to "project-deleted", "removed" to removed),
            ),
        )
        accessVersion.increment()
    }

    private fun load(projectId: UUID, id: MembershipId): ProjectMembership =
        memberships.findById(id)?.takeIf { it.projectId == projectId } ?: throw NotFoundException("project member", id)

    private fun ensureAnotherOwner(projectId: UUID, membership: ProjectMembership, what: String) {
        val owners = memberships.findByProject(projectId).count { it.roleKey == BuiltInRoles.OWNER && it.id != membership.id }
        if (owners == 0) throw InvalidStateException("the last owner of a project cannot be $what")
    }

    private fun requireProjectRole(key: String) {
        if (roles.findByKey(key)?.isProject() != true) throw ValidationException("unknown project role $key", "role")
    }

    private fun requirePrincipal(type: PrincipalType, id: UUID) {
        when (type) {
            PrincipalType.USER -> users.findById(UserId(id)) ?: throw NotFoundException("user", id)
            PrincipalType.GROUP -> groups.findById(GroupId(id)) ?: throw NotFoundException("group", id)
        }
    }

    private fun view(m: ProjectMembership): MemberView = when (m.principalType) {
        PrincipalType.USER -> users.findById(UserId(m.principalId)).let { MemberView(m, it?.displayName ?: m.principalId.toString(), it?.email) }
        PrincipalType.GROUP -> MemberView(m, groups.findById(GroupId(m.principalId))?.name ?: m.principalId.toString(), null)
    }

    companion object {
        /** Owned by the Projects context; spelled out because identity-core cannot depend on it. */
        const val PROJECT_VIEW = "project.view"
    }
}

/** User and group pickers: `admin.users.view`, or `project.members.manage` on at least one project. */
class DirectoryService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val gate: AuthorizationGate,
) : LookupDirectory {

    override fun users(q: String): List<User> {
        val project = memberManagerProject()
        if (project == null) gate.require(IdentityPermissions.USERS_VIEW) else gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, project)
        val query = q.trim()
        if (query.length < 2) throw ValidationException("q must have at least 2 characters", "q")
        return users.lookup(query, LIMIT)
    }

    override fun groups(q: String?): List<Group> {
        val project = memberManagerProject()
        if (project == null) gate.require(IdentityPermissions.USERS_VIEW) else gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, project)
        return groups.lookup(q?.trim()?.ifEmpty { null }, LIMIT)
    }

    /** `null` when the caller may use `admin.users.view` (or has neither: the gate then denies). */
    private fun memberManagerProject(): UUID? {
        val current = gate.currentUser()
        if (current.can(IdentityPermissions.USERS_VIEW)) return null
        return current.projectPermissions.entries.firstOrNull { IdentityPermissions.PROJECT_MEMBERS_MANAGE in it.value }?.key
    }

    companion object {
        const val LIMIT = 20
    }
}

/**
 * Implements the shared [ProjectAccessDirectory] (ADR 0028): the members of a project (direct and through
 * groups) with their access resolved exactly like a request principal. Only ACTIVE users are returned.
 */
class ProjectAccessDirectoryService(
    private val memberships: ProjectMembershipRepository,
    private val users: UserRepository,
    private val access: AccessResolver,
) : ProjectAccessDirectory {

    @SystemOperation("lookup for the Projects context; its callers check run.assign / project.view")
    override fun members(projectId: UUID): List<CurrentUser> {
        val rows = memberships.findByProject(projectId)
        val direct = rows.filter { it.principalType == PrincipalType.USER }.map { UserId(it.principalId) }
        val viaGroups = rows.filter { it.principalType == PrincipalType.GROUP }.flatMap { m ->
            users.search(UserQuery(status = UserStatus.ACTIVE, groupId = GroupId(m.principalId), size = GROUP_LIMIT)).content.map { it.id }
        }
        return users.findByIds((direct + viaGroups).distinct())
            .filter { it.status == UserStatus.ACTIVE }
            .sortedBy { it.displayName.lowercase() }
            .map { access.resolve(it).toCurrentUser() }
    }

    @SystemOperation("lookup for the Projects context; its callers check run.assign")
    override fun user(userId: UUID): CurrentUser? =
        users.findById(UserId(userId))?.takeIf { it.status == UserStatus.ACTIVE }?.let { access.resolve(it).toCurrentUser() }

    @SystemOperation("display names for the Projects context (approval assignees)")
    override fun displayNames(userIds: Collection<UUID>): Map<UUID, String> =
        if (userIds.isEmpty()) emptyMap() else users.findByIds(userIds.distinct().map(::UserId)).associate { it.id.value to it.displayName }

    companion object {
        const val GROUP_LIMIT = 500
    }
}
