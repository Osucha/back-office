package pl.mos.backoffice.identity.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant
import java.util.UUID

/** A named bundle of permissions. Built-in roles are seeded, editable, never deletable. */
data class Role(
    val key: String,
    val name: String,
    val description: String?,
    val scope: PermissionScope,
    val permissions: Set<String>,
    val builtIn: Boolean = false,
) {
    init {
        if (!KEY.matches(key)) throw ValidationException("role key must match ${KEY.pattern}", "key")
        if (name.isBlank()) throw ValidationException("role name must not be blank", "name")
    }

    companion object {
        val KEY = Regex("^[a-z][a-z0-9-]{1,39}$")
    }
}

enum class GrantEffect { ALLOW, DENY }

/** Closest level wins: USER > GROUP > SYSTEM (ADR 0017). The ordinal is the precedence. */
enum class GrantLevel { SYSTEM, GROUP, USER }

data class Grant(val permission: String, val effect: GrantEffect)

/** A platform role assigned to everyone (SYSTEM), a group or a user. Equivalent to ALLOW grants at that level. */
data class RoleAssignment(val level: GrantLevel, val targetId: UUID?, val roleKey: String)

/**
 * The platform grants and role assignments of one target: SYSTEM (targetId `null`), a group or a user.
 * Replaced as a whole by the admin UI (`PUT /admin/grants`).
 */
data class GrantSet(
    val level: GrantLevel,
    val targetId: UUID?,
    val roles: Set<String> = emptySet(),
    val grants: List<Grant> = emptyList(),
) {
    init {
        if ((level == GrantLevel.SYSTEM) != (targetId == null)) {
            throw ValidationException("targetId must be null for SYSTEM and set for GROUP and USER", "targetId")
        }
        val duplicates = grants.groupBy { it.permission }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw ValidationException("duplicate grants for $duplicates", "grants")
    }

    val isEmpty: Boolean get() = roles.isEmpty() && grants.isEmpty()

    fun effectOf(permission: String): GrantEffect? = grants.firstOrNull { it.permission == permission }?.effect

    fun assignments(): List<RoleAssignment> = roles.map { RoleAssignment(level, targetId, it) }

    companion object {
        fun empty(level: GrantLevel, targetId: UUID?) = GrantSet(level, targetId)
        fun system(roles: Set<String> = emptySet(), grants: List<Grant> = emptyList()) = GrantSet(GrantLevel.SYSTEM, null, roles, grants)
    }
}

enum class PrincipalType { USER, GROUP }

/** A user or group holding a project role on one project (project-scoped authorisation). */
data class ProjectMembership(
    val id: MembershipId,
    val projectId: UUID,
    val projectKey: String,
    val principalType: PrincipalType,
    val principalId: UUID,
    val roleKey: String,
    val addedAt: Instant,
    val addedBy: Actor,
)

/** `bo_pat_...`: only the SHA-256 hash is stored; the plaintext is shown once. */
data class PersonalAccessToken(
    val id: TokenId,
    val userId: UserId,
    val name: String,
    val hash: String,
    val prefix: String,
    /** Empty = all of the owner's effective permissions. */
    val permissions: Set<String>,
    val expiresAt: Instant,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    /**
     * Opt-in: the token may decide human gates through the MCP server (ADR 0029). Off by default, because an
     * IDE agent holding the token can be steered by prompt injection in what it reads.
     */
    val allowMcpGateDecisions: Boolean = false,
) {
    fun isUsable(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)
    fun revoke(now: Instant): PersonalAccessToken = if (revokedAt != null) this else copy(revokedAt = now)
}

enum class RegistrationMode { OPEN, INVITE_ONLY }

data class PlatformSettings(
    val registration: RegistrationMode = RegistrationMode.INVITE_ONLY,
    val allowedEmailDomains: List<String> = emptyList(),
    val syncGroupsFromIdentityProvider: Boolean = false,
    val personalTokenMaxDays: Int = 90,
    /** Local e-mail + password login (ADR 0021); also needs the property backoffice.auth.local.enabled. */
    val localLoginEnabled: Boolean = true,
    /** Local users must enrol TOTP at their next login. */
    val mfaRequiredForLocal: Boolean = false,
    val passwordPolicy: PasswordPolicy = PasswordPolicy(),
    val lockout: LockoutSettings = LockoutSettings(),
    /** ADR 0036: projects may turn Spec Kit on (default true); off = no project can use Spec Kit steps. */
    val specKitAllowed: Boolean = true,
) {
    init {
        if (personalTokenMaxDays !in 1..MAX_TOKEN_DAYS) {
            throw ValidationException("personalTokenMaxDays must be between 1 and $MAX_TOKEN_DAYS", "personalTokenMaxDays")
        }
        allowedEmailDomains.map(::normaliseDomain).filter { it.isNotEmpty() }.forEach {
            if (!DOMAIN.matches(it)) throw ValidationException("not a valid e-mail domain: $it", "allowedEmailDomains")
        }
    }

    fun allowsDomainOf(email: String): Boolean =
        User.normaliseEmail(email).substringAfterLast('@', "").let { domain -> allowedEmailDomains.any { normaliseDomain(it) == domain } }

    /** Lower-cased, without `@`, deduplicated. */
    fun normalised(): PlatformSettings = copy(
        allowedEmailDomains = allowedEmailDomains.map(::normaliseDomain).filter { it.isNotEmpty() }.distinct(),
    )

    companion object {
        fun normaliseDomain(domain: String): String = domain.trim().trimStart('@').lowercase()

        const val MAX_TOKEN_DAYS = 365
        private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    }
}
