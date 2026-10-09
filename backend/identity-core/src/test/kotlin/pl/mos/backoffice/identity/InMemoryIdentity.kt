package pl.mos.backoffice.identity

import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.Page
import pl.mos.backoffice.identity.application.port.out.PermissionCatalog
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.ProjectKeyLookup
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.application.service.AccessResolver
import pl.mos.backoffice.identity.application.service.CatalogSupport
import pl.mos.backoffice.identity.application.service.IdentityOptions
import pl.mos.backoffice.identity.application.service.PlatformAdmins
import pl.mos.backoffice.identity.application.service.ProjectKeys
import pl.mos.backoffice.identity.application.service.SettingsProvider
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant
import java.util.UUID

/** In-memory ports for identity-core unit tests; group membership is kept on both sides like the database does. */
class InMemoryIdentity(val now: Instant = Instant.parse("2026-09-27T10:00:00Z")) {

    val userMap = linkedMapOf<UserId, User>()
    val groupMap = linkedMapOf<GroupId, Group>()
    private val memberRows = mutableSetOf<Pair<GroupId, UserId>>()
    val roleMap = linkedMapOf<String, Role>()
    val grantMap = linkedMapOf<Pair<GrantLevel, UUID?>, GrantSet>()
    val membershipMap = linkedMapOf<MembershipId, ProjectMembership>()
    val tokenMap = linkedMapOf<TokenId, PersonalAccessToken>()
    var storedSettings: PlatformSettings? = null
    val projectKeyMap = mutableMapOf<UUID, String>()
    var version = 0L
    val terminatedSessions = mutableListOf<UserId>()
    val touched = mutableListOf<TokenId>()

    val clock = Clock.fixed(now)

    val catalogDefinitions: MutableList<PermissionDefinition> = (
        IdentityPermissions.definitions +
            (BuiltInRoles.ALL_PLATFORM - IdentityPermissions.definitions.map { it.key }.toSet())
                .map { PermissionDefinition(it, PermissionScope.PLATFORM, "test", it) } +
            listOf(
                "project.view", "project.manage", "project.ci.manage", "workflow.edit", "run.start", "run.cancel", "run.approve", "run.assign",
                "run.signal", "library.project.manage", "project.usage.view", "ci.execute", "ci.signal", "project.connections.manage",
            ).map { PermissionDefinition(it, PermissionScope.PROJECT, "test", it) }
        ).toMutableList()

    private fun withMembers(group: Group) = group.copy(memberIds = memberRows.filter { it.first == group.id }.map { it.second }.toSet())
    private fun withGroups(user: User) = user.copy(groupIds = memberRows.filter { it.second == user.id }.map { it.first }.toSet())

    val users = object : UserRepository {
        override fun findById(id: UserId) = userMap[id]?.let(::withGroups)
        override fun findByIds(ids: Collection<UserId>) = ids.mapNotNull { findById(it) }
        override fun findByIdentity(issuer: String, subject: String) = userMap.values.firstOrNull { it.issuer == issuer && it.subject == subject }?.let(::withGroups)
        override fun findByEmail(email: String) = userMap.values.firstOrNull { it.email == User.normaliseEmail(email) }?.let(::withGroups)
        override fun search(query: UserQuery): Page<User> {
            val all = userMap.values.map(::withGroups).filter {
                (query.q == null || it.email.contains(query.q!!) || it.displayName.contains(query.q!!, ignoreCase = true)) &&
                    (query.status == null || it.status == query.status) && (query.groupId == null || query.groupId in it.groupIds)
            }
            return Page(all.drop(query.page * query.size).take(query.size), query.page, query.size, all.size.toLong())
        }
        override fun lookup(q: String, limit: Int) = userMap.values.filter { it.email.contains(q) || it.displayName.contains(q, true) }.take(limit)
        override fun findByStatus(status: UserStatus) = userMap.values.filter { it.status == status }.map(::withGroups)
        override fun save(user: User): User {
            userMap[user.id] = user
            memberRows.removeIf { it.second == user.id }
            user.groupIds.forEach { memberRows += it to user.id }
            return user
        }
    }

    val groups = object : GroupRepository {
        override fun findById(id: GroupId) = groupMap[id]?.let(::withMembers)
        override fun findByIds(ids: Collection<GroupId>) = ids.mapNotNull { findById(it) }
        override fun findAll() = groupMap.values.map(::withMembers)
        override fun findByName(name: String) = groupMap.values.firstOrNull { it.name.equals(name, true) }?.let(::withMembers)
        override fun lookup(q: String?, limit: Int) = findAll().filter { q == null || it.name.contains(q, true) }.take(limit)
        override fun save(group: Group): Group {
            groupMap[group.id] = group
            memberRows.removeIf { it.first == group.id }
            group.memberIds.forEach { memberRows += group.id to it }
            return group
        }
        override fun delete(id: GroupId) {
            groupMap.remove(id)
            memberRows.removeIf { it.first == id }
        }
    }

    val roles = object : RoleRepository {
        override fun findAll() = roleMap.values.toList()
        override fun findByKey(key: String) = roleMap[key]
        override fun save(role: Role) = role.also { roleMap[it.key] = it }
        override fun delete(key: String) {
            roleMap.remove(key)
        }
    }

    val grants = object : GrantRepository {
        override fun find(level: GrantLevel, targetId: UUID?) = grantMap[level to targetId] ?: GrantSet.empty(level, targetId)
        override fun findAll() = grantMap.values.toList()
        override fun save(set: GrantSet) {
            if (set.isEmpty) grantMap.remove(set.level to set.targetId) else grantMap[set.level to set.targetId] = set
        }
        override fun deleteTarget(level: GrantLevel, targetId: UUID) {
            grantMap.remove(level to targetId)
        }
        override fun targetsWithRole(roleKey: String) = grantMap.values.filter { roleKey in it.roles }
    }

    val memberships = object : ProjectMembershipRepository {
        override fun findById(id: MembershipId) = membershipMap[id]
        override fun findByProject(projectId: UUID) = membershipMap.values.filter { it.projectId == projectId }
        override fun findByPrincipal(type: PrincipalType, principalId: UUID) = membershipMap.values.filter { it.principalType == type && it.principalId == principalId }
        override fun findForUser(userId: UserId, groupIds: Collection<GroupId>) = membershipMap.values.filter {
            (it.principalType == PrincipalType.USER && it.principalId == userId.value) ||
                (it.principalType == PrincipalType.GROUP && GroupId(it.principalId) in groupIds)
        }
        override fun findByRole(roleKey: String) = membershipMap.values.filter { it.roleKey == roleKey }
        override fun save(membership: ProjectMembership) = membership.also { membershipMap[it.id] = it }
        override fun delete(id: MembershipId) {
            membershipMap.remove(id)
        }
        override fun deleteByProject(projectId: UUID): Int {
            val ids = findByProject(projectId).map { it.id }
            ids.forEach { membershipMap.remove(it) }
            return ids.size
        }
        override fun deleteByPrincipal(type: PrincipalType, principalId: UUID): Int {
            val ids = findByPrincipal(type, principalId).map { it.id }
            ids.forEach { membershipMap.remove(it) }
            return ids.size
        }
    }

    val tokens = object : PersonalTokenRepository {
        override fun findById(id: TokenId) = tokenMap[id]
        override fun findByHash(hash: String) = tokenMap.values.firstOrNull { it.hash == hash }
        override fun findByUser(userId: UserId) = tokenMap.values.filter { it.userId == userId }
        override fun save(token: PersonalAccessToken) = token.also { tokenMap[it.id] = it }
        override fun revokeAllOf(userId: UserId, at: Instant): Int {
            val active = tokenMap.values.filter { it.userId == userId && it.revokedAt == null }
            active.forEach { tokenMap[it.id] = it.revoke(at) }
            return active.size
        }
        override fun touch(id: TokenId, at: Instant) {
            touched += id
            tokenMap[id]?.let { tokenMap[id] = it.copy(lastUsedAt = at) }
        }
    }

    val settingsRepository = object : PlatformSettingsRepository {
        override fun load() = storedSettings
        override fun save(settings: PlatformSettings) {
            storedSettings = settings
        }
    }

    val sessions = object : SessionTerminator {
        override fun sessionsOf(userId: UserId) = listOf(SessionSummary("hash", now, now, "127.0.0.1", "test"))
        override fun terminateAll(userId: UserId): Int {
            terminatedSessions += userId
            return 2
        }
    }

    val accessVersion = object : AccessVersion {
        override fun current() = version
        override fun increment() {
            version++
        }
    }

    val catalog = PermissionCatalog { catalogDefinitions }
    val catalogSupport = CatalogSupport(catalog)
    val projectKeyLookup = ProjectKeyLookup { projectKeyMap[it] }
    val projectKeys = ProjectKeys(memberships, projectKeyLookup)
    val access = AccessResolver(groups, roles, grants, memberships, catalogSupport)
    val admins = PlatformAdmins(users, groups, grants)

    fun settingsProvider(options: IdentityOptions = IdentityOptions()) = SettingsProvider(settingsRepository, options)

    // ---- builders

    fun seedBuiltInRoles() = BuiltInRoles.all.forEach { roles.save(it) }

    fun user(email: String, status: UserStatus = UserStatus.ACTIVE, groups: Set<GroupId> = emptySet(), id: UserId = UserId.new()): User =
        users.save(
            User(
                id = id,
                issuer = if (status == UserStatus.INVITED) null else "https://idp",
                subject = if (status == UserStatus.INVITED) null else "sub-$email",
                email = email,
                displayName = email.substringBefore('@'),
                status = status,
                groupIds = groups,
                createdAt = now,
                createdBy = Actor.SYSTEM,
            ),
        )

    fun group(name: String, members: Set<UserId> = emptySet(), external: String? = null): Group =
        groups.save(Group(GroupId.new(), name, null, members, external, now))

    fun assign(level: GrantLevel, target: UUID?, vararg roleKeys: String) {
        val set = grants.find(level, target)
        grants.save(set.copy(roles = set.roles + roleKeys))
    }

    fun member(projectId: UUID, key: String, type: PrincipalType, principal: UUID, role: String): ProjectMembership =
        memberships.save(ProjectMembership(MembershipId.new(), projectId, key, type, principal, role, now, Actor.SYSTEM))
}
