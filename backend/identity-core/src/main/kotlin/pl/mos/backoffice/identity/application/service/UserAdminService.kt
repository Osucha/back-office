package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.ManageUserSessions
import pl.mos.backoffice.identity.application.port.`in`.ManageUsers
import pl.mos.backoffice.identity.application.port.`in`.UserDetails
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.Page
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.GrantLevel
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
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.UserId

/**
 * User administration. Viewing needs `admin.users.view`, changes `admin.users.manage`; changing platform
 * roles additionally needs `admin.roles.manage` (a role assignment is a grant), and inviting with project
 * memberships needs `project.members.manage` on each project.
 */
class UserAdminService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val roles: RoleRepository,
    private val grants: GrantRepository,
    private val memberships: ProjectMembershipRepository,
    private val tokens: PersonalTokenRepository,
    private val sessions: SessionTerminator,
    private val projectKeys: ProjectKeys,
    private val admins: PlatformAdmins,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    /** Local accounts (ADR 0021); `null` in tests that only cover OIDC users. */
    private val local: LocalAccountSupport? = null,
) : ManageUsers, ManageUserSessions {

    private val assembler = UserDetailsAssembler(groups, grants, memberships, local)

    override fun list(query: UserQuery): Page<UserDetails> {
        gate.require(IdentityPermissions.USERS_VIEW)
        val page = users.search(query.copy(size = query.size.coerceIn(1, 200), page = query.page.coerceAtLeast(0)))
        return Page(page.content.map(::details), page.page, page.size, page.totalElements)
    }

    override fun get(id: UserId): UserDetails {
        gate.require(IdentityPermissions.USERS_VIEW)
        return details(load(id))
    }

    override fun invite(command: ManageUsers.InviteUser): UserDetails {
        val me = gate.require(IdentityPermissions.USERS_MANAGE)
        if (command.platformRoles.isNotEmpty()) gate.require(IdentityPermissions.ROLES_MANAGE)
        command.projectMemberships.map { it.projectId }.distinct().forEach { gate.require(IdentityPermissions.PROJECT_MEMBERS_MANAGE, it) }

        val email = User.validEmail(command.email)
        if (users.findByEmail(email) != null) throw InvalidStateException("a user with e-mail $email already exists")
        requireGroups(command.groupIds)
        requirePlatformRoles(command.platformRoles)
        val roleMap = roles.findAll().associateBy { it.key }
        command.projectMemberships.forEach {
            if (roleMap[it.role]?.isProject() != true) throw ValidationException("unknown project role ${it.role}", "projectMemberships")
        }
        val authMode = command.authMode ?: if (local == null || local.options.local.oidcConfigured) AuthMode.OIDC else AuthMode.LOCAL
        val now = clock.now()
        val user = users.save(User.invite(email, command.displayName, command.groupIds, now, me.actor, authMode))
        if (command.platformRoles.isNotEmpty()) grants.save(grants.find(GrantLevel.USER, user.id.value).copy(roles = command.platformRoles))
        val added = command.projectMemberships.distinctBy { it.projectId }.map {
            memberships.save(
                ProjectMembership(
                    id = MembershipId.new(),
                    projectId = it.projectId,
                    projectKey = projectKeys.keyOf(it.projectId) ?: throw NotFoundException("project", it.projectId),
                    principalType = PrincipalType.USER,
                    principalId = user.id.value,
                    roleKey = it.role,
                    addedAt = now,
                    addedBy = me.actor,
                ),
            )
        }
        audit.record(
            AuditEntry(
                IdentityAudit.USER_INVITE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf(
                    "email" to email, "displayName" to user.displayName, "authMode" to authMode.name,
                    "groupIds" to command.groupIds.map { it.toString() }.sorted(), "platformRoles" to command.platformRoles.sorted(),
                ),
            ),
        )
        added.forEach { memberAdded(it) }
        // a local user sets the first password through a one-time link the administrator hands over
        val link = if (authMode.allowsLocal && local != null) local.issueLink(user, me.actor) else null
        accessVersion.increment()
        return assembler.details(user, link?.token)
    }

    override fun update(id: UserId, command: ManageUsers.UpdateUser): UserDetails {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val user = load(id)
        val currentRoles = grants.find(GrantLevel.USER, id.value).roles
        val newRoles = command.platformRoles ?: currentRoles
        if (newRoles != currentRoles) {
            gate.require(IdentityPermissions.ROLES_MANAGE)
            requirePlatformRoles(newRoles)
        }
        val newGroups = command.groupIds ?: user.groupIds
        if (newGroups != user.groupIds) requireGroups(newGroups)
        val newName = command.displayName?.trim()?.also { if (it.isEmpty()) throw ValidationException("displayName must not be blank", "displayName") }
            ?: user.displayName

        val changes = AuditableChanges.changed(
            "displayName" to (user.displayName to newName),
            "groupIds" to (user.groupIds.map { it.toString() }.sorted() to newGroups.map { it.toString() }.sorted()),
            "platformRoles" to (currentRoles.sorted() to newRoles.sorted()),
        )
        if (changes.isEmpty()) return details(user)
        admins.ensureRemains(PlatformAdmins.Change(userRoles = id to newRoles, userGroups = id to newGroups))

        val saved = users.save(user.copy(displayName = newName, groupIds = newGroups))
        if (newRoles != currentRoles) grants.save(grants.find(GrantLevel.USER, id.value).copy(roles = newRoles))
        audit.record(AuditEntry(IdentityAudit.USER_UPDATE, IdentityAudit.ENTITY_USER, id.toString(), details = changes))
        accessVersion.increment()
        return details(saved)
    }

    override fun activate(id: UserId): UserDetails {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val user = load(id)
        if (user.status != UserStatus.DEACTIVATED) return details(user)
        val saved = users.save(user.activate(hasPassword = local?.credentials?.find(id)?.hasPassword == true))
        audit.record(
            AuditEntry(
                IdentityAudit.USER_ACTIVATE, IdentityAudit.ENTITY_USER, id.toString(),
                details = AuditableChanges.changed("status" to (user.status.name to saved.status.name)),
            ),
        )
        accessVersion.increment()
        return details(saved)
    }

    override fun deactivate(id: UserId): UserDetails {
        val me = gate.require(IdentityPermissions.USERS_MANAGE)
        if (me.userId == id) throw InvalidStateException("you cannot deactivate yourself")
        val user = load(id)
        if (user.status == UserStatus.DEACTIVATED) return details(user)
        admins.ensureRemains(PlatformAdmins.Change(deactivated = id))
        val saved = users.save(user.deactivate())
        val now = clock.now()
        val revoked = tokens.revokeAllOf(id, now)
        val terminated = sessions.terminateAll(id)
        audit.record(
            AuditEntry(
                IdentityAudit.USER_DEACTIVATE, IdentityAudit.ENTITY_USER, id.toString(),
                details = AuditableChanges.changed("status" to (user.status.name to saved.status.name)) +
                    mapOf("revokedTokens" to revoked, "terminatedSessions" to terminated),
            ),
        )
        accessVersion.increment()
        return details(saved)
    }

    override fun list(userId: UserId): List<SessionSummary> {
        gate.require(IdentityPermissions.USERS_VIEW)
        load(userId)
        return sessions.sessionsOf(userId)
    }

    override fun terminateAll(userId: UserId): Int {
        gate.require(IdentityPermissions.USERS_MANAGE)
        load(userId)
        val terminated = sessions.terminateAll(userId)
        audit.record(AuditEntry(IdentityAudit.SESSIONS_TERMINATE, IdentityAudit.ENTITY_USER, userId.toString(), details = mapOf("terminatedSessions" to terminated)))
        accessVersion.increment()
        return terminated
    }

    private fun load(id: UserId): User = users.findById(id) ?: throw NotFoundException("user", id)

    private fun requireGroups(ids: Set<GroupId>) {
        val found = groups.findByIds(ids).map { it.id }.toSet()
        val missing = ids - found
        if (missing.isNotEmpty()) throw ValidationException("unknown groups $missing", "groupIds")
    }

    private fun requirePlatformRoles(keys: Set<String>) {
        val roleMap = roles.findAll().associateBy { it.key }
        val invalid = keys.filter { roleMap[it]?.isPlatform() != true }
        if (invalid.isNotEmpty()) throw ValidationException("unknown platform roles $invalid", "platformRoles")
    }

    private fun memberAdded(m: ProjectMembership) = audit.record(
        AuditEntry(
            IdentityAudit.MEMBER_ADD, IdentityAudit.ENTITY_MEMBERSHIP, m.id.toString(), projectId = m.projectId,
            details = mapOf("principalType" to m.principalType.name, "principalId" to m.principalId.toString(), "role" to m.roleKey),
        ),
    )

    private fun details(user: User): UserDetails = assembler.details(user)
}
