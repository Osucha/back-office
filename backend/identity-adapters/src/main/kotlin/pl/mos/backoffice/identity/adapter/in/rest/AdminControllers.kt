package pl.mos.backoffice.identity.adapter.`in`.rest

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.identity.adapter.config.IdentityWebValues
import pl.mos.backoffice.identity.application.port.`in`.EffectivePermissionsQuery
import pl.mos.backoffice.identity.application.port.`in`.LookupDirectory
import pl.mos.backoffice.identity.application.port.`in`.ManageGrants
import pl.mos.backoffice.identity.application.port.`in`.ManageGroups
import pl.mos.backoffice.identity.application.port.`in`.ManagePersonalTokens
import pl.mos.backoffice.identity.application.port.`in`.ManagePlatformSettings
import pl.mos.backoffice.identity.application.port.`in`.ManageProjectMembers
import pl.mos.backoffice.identity.application.port.`in`.ManageRoles
import pl.mos.backoffice.identity.application.port.`in`.ManageUserSessions
import pl.mos.backoffice.identity.application.port.`in`.ManageUsers
import pl.mos.backoffice.identity.application.port.`in`.PermissionCatalogQuery
import pl.mos.backoffice.identity.application.port.`in`.SearchAuditLog
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.domain.model.Grant
import pl.mos.backoffice.identity.domain.model.GrantEffect
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.usage.SortSpec
import java.time.Instant
import java.util.UUID

private inline fun <reified E : Enum<E>> enumOf(value: String, field: String): E =
    runCatching { enumValueOf<E>(value.trim().uppercase()) }.getOrElse { throw ValidationException("unknown value $value", field) }

@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController(
    private val users: ManageUsers,
    private val sessions: ManageUserSessions,
    private val effective: EffectivePermissionsQuery,
    private val web: IdentityWebValues,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: UserStatus?,
        @RequestParam(required = false) groupId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) sort: String?,
    ): UserPageDto {
        val result = users.list(UserQuery(q, status, groupId?.let(::GroupId), page, size, SortSpec.parse(sort, UserQuery.SORT_FIELDS)))
        return UserPageDto(result.content.map { UserDetailDto.of(it, web.publicUrl) }, result.page, result.size, result.totalElements)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@RequestBody body: UserInviteDto): UserDetailDto = UserDetailDto.of(
        users.invite(
            ManageUsers.InviteUser(
                email = body.email,
                displayName = body.displayName,
                authMode = body.authMode,
                groupIds = body.groupIds.orEmpty().map(::GroupId).toSet(),
                platformRoles = body.platformRoles.orEmpty().toSet(),
                projectMemberships = body.projectMemberships.orEmpty().map { ManageUsers.InitialMembership(it.projectId, it.role) },
            ),
        ),
        web.publicUrl,
    )

    @GetMapping("/{userId}")
    fun get(@PathVariable userId: UUID): UserDetailDto = UserDetailDto.of(users.get(UserId(userId)), web.publicUrl)

    @PutMapping("/{userId}")
    fun update(@PathVariable userId: UUID, @RequestBody body: UserUpdateDto): UserDetailDto = UserDetailDto.of(
        users.update(UserId(userId), ManageUsers.UpdateUser(body.displayName, body.groupIds?.map(::GroupId)?.toSet(), body.platformRoles?.toSet())),
        web.publicUrl,
    )

    @PostMapping("/{userId}/activate")
    fun activate(@PathVariable userId: UUID): UserDetailDto = UserDetailDto.of(users.activate(UserId(userId)), web.publicUrl)

    @PostMapping("/{userId}/deactivate")
    fun deactivate(@PathVariable userId: UUID): UserDetailDto = UserDetailDto.of(users.deactivate(UserId(userId)), web.publicUrl)

    @GetMapping("/{userId}/effective-permissions")
    fun effectivePermissions(@PathVariable userId: UUID): EffectivePermissionsDto = EffectivePermissionsDto.of(effective.of(UserId(userId)))

    @GetMapping("/{userId}/sessions")
    fun sessions(@PathVariable userId: UUID): List<SessionSummaryDto> = sessions.list(UserId(userId)).map(SessionSummaryDto::of)

    @DeleteMapping("/{userId}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun terminateSessions(@PathVariable userId: UUID) {
        sessions.terminateAll(UserId(userId))
    }
}

@RestController
@RequestMapping("/api/v1/admin/groups")
class AdminGroupController(private val groups: ManageGroups) {

    @GetMapping
    fun list(): List<GroupDto> = groups.list().map(GroupDto::of)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: GroupInputDto): GroupDto = GroupDto.of(groups.create(command(body)))

    @GetMapping("/{groupId}")
    fun get(@PathVariable groupId: UUID): GroupDto = GroupDto.of(groups.get(GroupId(groupId)))

    @PutMapping("/{groupId}")
    fun update(@PathVariable groupId: UUID, @RequestBody body: GroupInputDto): GroupDto = GroupDto.of(groups.update(GroupId(groupId), command(body)))

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable groupId: UUID) = groups.delete(GroupId(groupId))

    private fun command(body: GroupInputDto) = ManageGroups.GroupCommand(
        name = body.name,
        description = body.description,
        memberIds = body.memberIds.orEmpty().map(::UserId).toSet(),
        platformRoles = body.platformRoles?.toSet(),
        externalGroup = body.externalGroup,
    )
}

@RestController
@RequestMapping("/api/v1/admin")
class AdminAccessController(
    private val roles: ManageRoles,
    private val catalog: PermissionCatalogQuery,
    private val grants: ManageGrants,
    private val settings: ManagePlatformSettings,
    private val audit: SearchAuditLog,
) {
    @GetMapping("/roles")
    fun roles(@RequestParam(required = false) scope: PermissionScope?): List<RoleDto> = roles.list(scope).map(RoleDto::of)

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRole(@RequestBody body: RoleInputDto): RoleDto = RoleDto.of(roles.create(roleCommand(body)))

    @PutMapping("/roles/{roleKey}")
    fun updateRole(@PathVariable roleKey: String, @RequestBody body: RoleInputDto): RoleDto = RoleDto.of(roles.update(roleKey, roleCommand(body)))

    @DeleteMapping("/roles/{roleKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRole(@PathVariable roleKey: String) = roles.delete(roleKey)

    @GetMapping("/permissions")
    fun permissions(): List<PermissionDefinitionDto> = catalog.catalog().map(PermissionDefinitionDto::of)

    @GetMapping("/grants")
    fun grants(@RequestParam(required = false) level: GrantLevel?, @RequestParam(required = false) targetId: UUID?): List<GrantSetDto> =
        grants.list(level, targetId).map(GrantSetDto::of)

    @PutMapping("/grants")
    fun replaceGrants(@RequestBody body: GrantSetDto): GrantSetDto = GrantSetDto.of(
        grants.replace(
            GrantSet(
                level = enumOf(body.level, "level"),
                targetId = body.targetId,
                roles = body.roles.orEmpty().toSet(),
                grants = body.grants.orEmpty().map { Grant(it.permission, enumOf<GrantEffect>(it.effect, "grants")) },
            ),
        ),
    )

    @GetMapping("/settings")
    fun settings(): PlatformSettingsDto = PlatformSettingsDto.of(settings.get())

    @PutMapping("/settings")
    fun updateSettings(@RequestBody body: PlatformSettingsDto): PlatformSettingsDto =
        PlatformSettingsDto.of(settings.update(body.toDomain(settings.get())))

    @GetMapping("/audit")
    fun audit(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: String?,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) result: AuditResult?,
        @RequestParam(required = false) correlationId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) sort: String?,
    ): AuditPageDto {
        val page = audit.search(
            AuditQuery(from, to, actorId, operation, entityType, entityId, projectId, result, correlationId, cursor, size, SortSpec.parse(sort, AuditQuery.SORT_FIELDS)),
        )
        return AuditPageDto(page.content.map(AuditEntryDto::of), page.nextCursor)
    }

    private fun roleCommand(body: RoleInputDto) =
        ManageRoles.RoleCommand(body.key, body.name, body.description, body.scope, body.permissions.toSet())
}

@RestController
@RequestMapping("/api/v1")
class DirectoryController(private val directory: LookupDirectory) {

    @GetMapping("/users/lookup")
    fun users(@RequestParam q: String): List<UserRefDto> = directory.users(q).map(UserRefDto::of)

    @GetMapping("/groups/lookup")
    fun groups(@RequestParam(required = false) q: String?): List<GroupRefDto> = directory.groups(q).map(GroupRefDto::of)
}

@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
class ProjectMemberController(private val members: ManageProjectMembers) {

    @GetMapping
    fun list(@PathVariable projectId: UUID): List<ProjectMemberDto> = members.list(projectId).map(ProjectMemberDto::of)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@PathVariable projectId: UUID, @RequestBody body: ProjectMemberInputDto): ProjectMemberDto = ProjectMemberDto.of(
        members.add(projectId, ManageProjectMembers.AddMember(enumOf<PrincipalType>(body.principalType, "principalType"), body.principalId, body.role)),
    )

    @PutMapping("/{memberId}")
    fun changeRole(@PathVariable projectId: UUID, @PathVariable memberId: UUID, @RequestBody body: RoleChangeDto): ProjectMemberDto =
        ProjectMemberDto.of(members.changeRole(projectId, MembershipId(memberId), body.role))

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable projectId: UUID, @PathVariable memberId: UUID) = members.remove(projectId, MembershipId(memberId))
}

@RestController
@RequestMapping("/api/v1/tokens")
class PersonalTokenController(private val tokens: ManagePersonalTokens) {

    @GetMapping
    fun list(): List<PersonalTokenDto> = tokens.list().map { PersonalTokenDto.of(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: PersonalTokenInputDto): PersonalTokenDto {
        val created = tokens.create(
            ManagePersonalTokens.CreateToken(
                body.name, body.expiresInDays, body.permissions.orEmpty().toSet(),
                allowMcpGateDecisions = body.allowMcpGateDecisions == true,
                allowMcpWorkflowEdits = body.allowMcpWorkflowEdits == true,
            ),
        )
        return PersonalTokenDto.of(created.token, created.plaintext)
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable tokenId: UUID) = tokens.revoke(TokenId(tokenId))
}
