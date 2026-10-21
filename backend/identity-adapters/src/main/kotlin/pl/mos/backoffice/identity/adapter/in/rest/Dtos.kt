package pl.mos.backoffice.identity.adapter.`in`.rest

import pl.mos.backoffice.identity.application.port.`in`.EffectivePermissionsView
import pl.mos.backoffice.identity.application.port.`in`.GrantSetView
import pl.mos.backoffice.identity.application.port.`in`.GroupView
import pl.mos.backoffice.identity.application.port.`in`.MemberView
import pl.mos.backoffice.identity.application.port.`in`.UserDetails
import pl.mos.backoffice.identity.application.port.out.AuditRecord
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.`in`.MySecurityView
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.LockoutSettings
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.PasswordPolicy
import pl.mos.backoffice.identity.domain.model.ResetPurpose
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.identity.domain.model.Theme
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.ProjectAccess
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import java.time.Instant
import java.util.UUID

// DTOs follow docs/api/openapi.yaml v3 (schemas Me, UserRef, UserDetail, Group*, Role*, Grant*, ...).

data class ActorRefDto(val kind: String, val userId: UUID?, val displayName: String) {
    companion object {
        fun of(actor: Actor?) = actor?.let { ActorRefDto(it.kind.name, it.userId, it.displayName) }
    }
}

data class UserRefDto(val id: UUID, val email: String, val displayName: String, val status: UserStatus) {
    companion object {
        fun of(user: User) = UserRefDto(user.id.value, user.email, user.displayName, user.status)
    }
}

data class GroupRefDto(val id: UUID, val name: String) {
    companion object {
        fun of(group: Group) = GroupRefDto(group.id.value, group.name)
    }
}

data class ProjectAccessDto(val projectId: UUID, val projectKey: String, val roles: List<String>, val permissions: List<String>) {
    companion object {
        fun of(p: ProjectAccess) = ProjectAccessDto(p.projectId, p.projectKey, p.roles.sorted(), p.permissions.sorted())
    }
}

data class PreferencesDto(val theme: Theme? = Theme.SYSTEM, val defaultProjectId: UUID? = null, val pageSize: Int? = 20) {
    fun toDomain() = UserPreferences(theme ?: Theme.SYSTEM, defaultProjectId, pageSize ?: 20)

    companion object {
        fun of(p: UserPreferences) = PreferencesDto(p.theme, p.defaultProjectId, p.pageSize)
    }
}

data class MeDto(
    val user: UserRefDto,
    val kind: String,
    val platformPermissions: List<String>,
    val allProjects: Boolean,
    val projects: List<ProjectAccessDto>,
    val groups: List<GroupRefDto>,
    val preferences: PreferencesDto,
    val idleTimeoutSeconds: Long,
    val absoluteExpiresAt: Instant?,
    val authenticatedAt: Instant?,
)

data class AuthMethodsDto(val local: Boolean, val oidc: Boolean)

data class AuthConfigDto(
    val loginUrl: String?,
    val providerName: String,
    val registration: RegistrationMode,
    val accountUrl: String?,
    val methods: AuthMethodsDto,
    val passwordPolicy: PasswordPolicyDto,
)

data class LogoutDto(val logoutUrl: String?)

data class SessionInfoDto(val idleExpiresAt: Instant, val absoluteExpiresAt: Instant?, val idleTimeoutSeconds: Long)

data class SessionSummaryDto(val id: String, val createdAt: Instant, val lastAccessAt: Instant, val sourceIp: String?, val userAgent: String?) {
    companion object {
        fun of(s: SessionSummary) = SessionSummaryDto(s.id, s.createdAt, s.lastAccessAt, s.sourceIp, s.userAgent)
    }
}

data class UserMembershipDto(val projectId: UUID, val projectKey: String, val role: String, val viaGroup: String?)

data class UserDetailDto(
    val id: UUID,
    val email: String,
    val displayName: String,
    val status: UserStatus,
    val issuer: String?,
    val subject: String?,
    val groups: List<GroupRefDto>,
    val platformRoles: List<String>,
    val projectMemberships: List<UserMembershipDto>,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
    val createdBy: ActorRefDto?,
    val signInUrl: String?,
    val authMode: AuthMode,
    val hasPassword: Boolean,
    val mfaEnabled: Boolean,
    val lockedUntil: Instant?,
    val mustChangePassword: Boolean,
    /** Only in the response that issued the link (invite, auth-mode change). */
    val setPasswordUrl: String?,
) {
    companion object {
        fun setPasswordUrl(publicUrl: String, token: String) = publicUrl.trimEnd('/') + "/set-password/" + token

        fun of(d: UserDetails, publicUrl: String) = UserDetailDto(
            id = d.user.id.value,
            email = d.user.email,
            displayName = d.user.displayName,
            status = d.user.status,
            issuer = d.user.issuer,
            subject = d.user.subject,
            groups = d.groups.map(GroupRefDto::of),
            platformRoles = d.platformRoles.sorted(),
            projectMemberships = d.memberships.map { UserMembershipDto(it.membership.projectId, it.membership.projectKey, it.membership.roleKey, it.viaGroup?.name) },
            lastLoginAt = d.user.lastLoginAt,
            createdAt = d.user.createdAt,
            createdBy = ActorRefDto.of(d.user.createdBy),
            signInUrl = if (d.user.status == UserStatus.INVITED) publicUrl.trimEnd('/') + "/login" else null,
            authMode = d.user.authMode,
            hasPassword = d.local.hasPassword,
            mfaEnabled = d.local.mfaEnabled,
            lockedUntil = d.local.lockedUntil,
            mustChangePassword = d.local.mustChangePassword,
            setPasswordUrl = d.setPasswordToken?.let { setPasswordUrl(publicUrl, it) },
        )
    }
}

data class UserPageDto(val content: List<UserDetailDto>, val page: Int, val size: Int, val totalElements: Long)

data class InitialMembershipDto(val projectId: UUID, val role: String)

data class UserInviteDto(
    val email: String,
    val displayName: String? = null,
    val authMode: AuthMode? = null,
    val groupIds: List<UUID>? = null,
    val platformRoles: List<String>? = null,
    val projectMemberships: List<InitialMembershipDto>? = null,
)

data class UserUpdateDto(val displayName: String? = null, val groupIds: List<UUID>? = null, val platformRoles: List<String>? = null)

data class GroupDto(
    val id: UUID,
    val name: String,
    val description: String?,
    val memberCount: Int,
    val memberIds: List<UUID>,
    val platformRoles: List<String>,
    val externalGroup: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(v: GroupView) = GroupDto(
            v.group.id.value, v.group.name, v.group.description, v.group.memberIds.size, v.group.memberIds.map { it.value }.sorted(),
            v.platformRoles.sorted(), v.group.externalGroup, v.group.createdAt,
        )
    }
}

data class GroupInputDto(
    val name: String,
    val description: String? = null,
    val memberIds: List<UUID>? = null,
    val platformRoles: List<String>? = null,
    val externalGroup: String? = null,
)

data class PermissionDefinitionDto(val key: String, val scope: PermissionScope, val module: String, val description: String) {
    companion object {
        fun of(d: PermissionDefinition) = PermissionDefinitionDto(d.key, d.scope, d.module, d.description)
    }
}

data class RoleDto(val key: String, val name: String, val description: String?, val scope: PermissionScope, val permissions: List<String>, val builtIn: Boolean) {
    companion object {
        fun of(r: Role) = RoleDto(r.key, r.name, r.description, r.scope, r.permissions.sorted(), r.builtIn)
    }
}

data class RoleInputDto(val key: String, val name: String, val description: String? = null, val scope: PermissionScope, val permissions: List<String> = emptyList())

data class GrantDto(val permission: String, val effect: String)

data class GrantSetDto(
    val level: String,
    val targetId: UUID? = null,
    val targetName: String? = null,
    val roles: List<String>? = null,
    val grants: List<GrantDto>? = null,
) {
    companion object {
        fun of(v: GrantSetView) = GrantSetDto(
            v.set.level.name, v.set.targetId, v.targetName, v.set.roles.sorted(), v.set.grants.sortedBy { it.permission }.map { GrantDto(it.permission, it.effect.name) },
        )
    }
}

data class GroupEffectDto(val groupId: UUID, val groupName: String, val effect: String)

data class EffectiveRowDto(
    val permission: String,
    val description: String,
    val system: String,
    val groups: List<GroupEffectDto>,
    val user: String,
    val effective: Boolean,
    val source: String?,
)

data class EffectivePermissionsDto(val userId: UUID, val rows: List<EffectiveRowDto>, val projects: List<ProjectAccessDto>) {
    companion object {
        fun of(v: EffectivePermissionsView) = EffectivePermissionsDto(
            v.userId.value,
            v.rows.map { row ->
                EffectiveRowDto(
                    row.permission, v.descriptions[row.permission] ?: "", row.system?.name ?: "NONE",
                    row.groups.map { GroupEffectDto(it.groupId.value, it.groupName, it.effect?.name ?: "NONE") },
                    row.user?.name ?: "NONE", row.effective, row.source,
                )
            },
            v.projects.map(ProjectAccessDto::of),
        )
    }
}

data class PasswordPolicyDto(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val rejectCommon: Boolean? = null,
    val rejectContainingEmail: Boolean? = null,
) {
    fun toDomain(current: PasswordPolicy) = PasswordPolicy(
        minLength ?: current.minLength, maxLength ?: current.maxLength, rejectCommon ?: current.rejectCommon, rejectContainingEmail ?: current.rejectContainingEmail,
    )

    companion object {
        fun of(p: PasswordPolicy) = PasswordPolicyDto(p.minLength, p.maxLength, p.rejectCommon, p.rejectContainingEmail)
    }
}

data class LockoutDto(val maxFailures: Int? = null, val lockMinutes: Int? = null)

data class PlatformSettingsDto(
    val registration: RegistrationMode? = null,
    val allowedEmailDomains: List<String>? = null,
    val syncGroupsFromIdentityProvider: Boolean? = null,
    val personalTokenMaxDays: Int? = null,
    val localLoginEnabled: Boolean? = null,
    val mfaRequiredForLocal: Boolean? = null,
    val passwordPolicy: PasswordPolicyDto? = null,
    val lockout: LockoutDto? = null,
    /** ADR 0036: projects may turn Spec Kit on. */
    val specKitAllowed: Boolean? = null,
) {
    fun toDomain(current: PlatformSettings) = PlatformSettings(
        registration = registration ?: current.registration,
        allowedEmailDomains = allowedEmailDomains ?: current.allowedEmailDomains,
        syncGroupsFromIdentityProvider = syncGroupsFromIdentityProvider ?: current.syncGroupsFromIdentityProvider,
        personalTokenMaxDays = personalTokenMaxDays ?: current.personalTokenMaxDays,
        localLoginEnabled = localLoginEnabled ?: current.localLoginEnabled,
        mfaRequiredForLocal = mfaRequiredForLocal ?: current.mfaRequiredForLocal,
        passwordPolicy = passwordPolicy?.toDomain(current.passwordPolicy) ?: current.passwordPolicy,
        lockout = LockoutSettings(lockout?.maxFailures ?: current.lockout.maxFailures, lockout?.lockMinutes ?: current.lockout.lockMinutes),
        specKitAllowed = specKitAllowed ?: current.specKitAllowed,
    )

    companion object {
        fun of(s: PlatformSettings) = PlatformSettingsDto(
            s.registration, s.allowedEmailDomains, s.syncGroupsFromIdentityProvider, s.personalTokenMaxDays,
            s.localLoginEnabled, s.mfaRequiredForLocal, PasswordPolicyDto.of(s.passwordPolicy), LockoutDto(s.lockout.maxFailures, s.lockout.lockMinutes),
            s.specKitAllowed,
        )
    }
}

// ---- local accounts (ADR 0021)

data class LocalLoginRequestDto(val email: String? = null, val password: String? = null, val returnTo: String? = null)

data class MfaLoginRequestDto(val mfaToken: String? = null, val code: String? = null, val recoveryCode: String? = null)

enum class LoginStepStatus { AUTHENTICATED, MFA_REQUIRED, MFA_ENROLMENT_REQUIRED, PASSWORD_CHANGE_REQUIRED }

data class LoginStepResultDto(val status: LoginStepStatus, val mfaToken: String? = null, val returnTo: String? = null)

data class PasswordChangeRequestDto(val currentPassword: String? = null, val newPassword: String? = null)

data class NewPasswordDto(val newPassword: String? = null)

data class PasswordResetInfoDto(val email: String, val expiresAt: Instant, val purpose: ResetPurpose, val policy: PasswordPolicyDto)

data class PasswordResetLinkDto(val url: String, val expiresAt: Instant)

data class LoginRecordDto(val at: Instant, val method: String, val result: String, val sourceIp: String?)

data class MySecurityDto(
    val authMode: AuthMode,
    val hasPassword: Boolean,
    val passwordChangedAt: Instant?,
    val mfaEnabled: Boolean,
    val mfaRequired: Boolean,
    val recoveryCodesRemaining: Int,
    val lastLoginAt: Instant?,
    val lastLoginMethod: LoginMethod?,
    val recentLogins: List<LoginRecordDto>,
    val policy: PasswordPolicyDto,
) {
    companion object {
        fun of(v: MySecurityView) = MySecurityDto(
            v.authMode, v.hasPassword, v.passwordChangedAt, v.mfaEnabled, v.mfaRequired, v.recoveryCodesRemaining, v.lastLoginAt, v.lastLoginMethod,
            v.recentLogins.map { LoginRecordDto(it.at, it.method, it.result.name, it.sourceIp) }, PasswordPolicyDto.of(v.policy),
        )
    }
}

data class TotpEnrolmentDto(val secret: String, val otpauthUri: String, val qrSvg: String)

data class TotpCodeDto(val code: String? = null)

data class CurrentPasswordDto(val currentPassword: String? = null)

data class RecoveryCodesDto(val codes: List<String>)

data class AuthModeDto(val authMode: AuthMode? = null)

data class PrincipalDto(val type: String, val id: UUID, val name: String, val email: String?)

data class ProjectMemberDto(val id: UUID, val projectId: UUID, val principal: PrincipalDto, val role: String, val addedAt: Instant, val addedBy: ActorRefDto?) {
    companion object {
        fun of(v: MemberView) = ProjectMemberDto(
            v.membership.id.value, v.membership.projectId,
            PrincipalDto(v.membership.principalType.name, v.membership.principalId, v.principalName, v.principalEmail),
            v.membership.roleKey, v.membership.addedAt, ActorRefDto.of(v.membership.addedBy),
        )
    }
}

data class ProjectMemberInputDto(val principalType: String, val principalId: UUID, val role: String)

data class RoleChangeDto(val role: String)

data class PersonalTokenDto(
    val id: UUID,
    val name: String,
    val prefix: String,
    val permissions: List<String>,
    val expiresAt: Instant,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    /** Only in the creation response. */
    val token: String? = null,
    val allowMcpGateDecisions: Boolean = false,
    val allowMcpWorkflowEdits: Boolean = false,
) {
    companion object {
        fun of(t: PersonalAccessToken, plaintext: String? = null) =
            PersonalTokenDto(
                t.id.value, t.name, t.prefix, t.permissions.sorted(), t.expiresAt, t.createdAt, t.lastUsedAt, t.revokedAt, plaintext,
                t.allowMcpGateDecisions, t.allowMcpWorkflowEdits,
            )
    }
}

data class PersonalTokenInputDto(
    val name: String,
    val expiresInDays: Int,
    val permissions: List<String>? = null,
    val allowMcpGateDecisions: Boolean? = null,
    val allowMcpWorkflowEdits: Boolean? = null,
)

data class AuditEntryDto(
    val id: UUID,
    val eventTime: Instant,
    val actor: ActorRefDto?,
    val operation: String,
    val entityType: String,
    val entityId: String?,
    val projectId: UUID?,
    val result: String,
    val details: Map<String, Any?>,
    val correlationId: String?,
    val sourceIp: String?,
) {
    companion object {
        fun of(r: AuditRecord) = AuditEntryDto(
            r.id, r.eventTime, ActorRefDto.of(r.actor), r.operation, r.entityType, r.entityId, r.projectId, r.result.name, r.details, r.correlationId, r.sourceIp,
        )
    }
}

data class AuditPageDto(val content: List<AuditEntryDto>, val nextCursor: String?)
