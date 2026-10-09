package pl.mos.backoffice.identity.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant
import java.util.UUID

/** `INVITED -> ACTIVE <-> DEACTIVATED` (08-identity-and-access.md, "Users and provisioning"). */
enum class UserStatus { INVITED, ACTIVE, DEACTIVATED }

enum class Theme { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    val theme: Theme = Theme.SYSTEM,
    val defaultProjectId: UUID? = null,
    val pageSize: Int = 20,
) {
    init {
        if (pageSize !in 10..200) throw ValidationException("pageSize must be between 10 and 200", "pageSize")
    }
}

/**
 * A person known to Back Office. The OIDC identity (`issuer`, `subject`) is bound on the first login; an
 * invited user has only an e-mail until then. Local accounts (ADR 0021) keep their password in a separate
 * [LocalCredential]; [authMode] decides which methods the user may use.
 */
data class User(
    val id: UserId,
    val issuer: String?,
    val subject: String?,
    val email: String,
    val displayName: String,
    val status: UserStatus,
    val groupIds: Set<GroupId> = emptySet(),
    val preferences: UserPreferences = UserPreferences(),
    val lastLoginAt: Instant? = null,
    val createdAt: Instant,
    val createdBy: Actor,
    /** How the user may sign in (ADR 0021); existing OIDC users are OIDC. */
    val authMode: AuthMode = AuthMode.OIDC,
    val lastLoginMethod: LoginMethod? = null,
) {
    init {
        require(email == normaliseEmail(email)) { "e-mail must be normalised: $email" }
        require((issuer == null) == (subject == null)) { "issuer and subject are bound together" }
    }

    val isBound: Boolean get() = subject != null

    /** First login of an invited user (or a user created before binding): bind the OIDC identity. */
    fun bind(issuer: String, subject: String): User {
        if (this.subject != null && (this.issuer != issuer || this.subject != subject)) {
            throw InvalidStateException("user $email is bound to another identity")
        }
        return copy(issuer = issuer, subject = subject, status = if (status == UserStatus.INVITED) UserStatus.ACTIVE else status)
    }

    fun loggedIn(at: Instant, method: LoginMethod = LoginMethod.OIDC): User = copy(lastLoginAt = at, lastLoginMethod = method)

    /** [hasPassword]: a local user who has set a password counts as signed up, like a bound OIDC user. */
    fun activate(hasPassword: Boolean = false): User = when (status) {
        UserStatus.DEACTIVATED -> copy(status = if (isBound || hasPassword) UserStatus.ACTIVE else UserStatus.INVITED)
        else -> this
    }

    /** A local user set the first password: INVITED becomes ACTIVE. */
    fun passwordSet(): User = if (status == UserStatus.INVITED) copy(status = UserStatus.ACTIVE) else this

    fun deactivate(): User = copy(status = UserStatus.DEACTIVATED)

    companion object {
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun normaliseEmail(email: String): String = email.trim().lowercase()

        /** Normalises and validates an e-mail address; throws `ValidationException` (400). */
        fun validEmail(email: String?, field: String = "email"): String {
            val normalised = normaliseEmail(email ?: "")
            if (!EMAIL.matches(normalised)) throw ValidationException("not a valid e-mail address", field)
            return normalised
        }

        fun invite(email: String, displayName: String?, groupIds: Set<GroupId>, now: Instant, by: Actor, authMode: AuthMode = AuthMode.OIDC): User {
            val normalised = validEmail(email)
            return User(
                id = UserId.new(),
                issuer = null,
                subject = null,
                email = normalised,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: normalised.substringBefore('@'),
                status = UserStatus.INVITED,
                groupIds = groupIds,
                createdAt = now,
                createdBy = by,
                authMode = authMode,
            )
        }

        /** Just-in-time provisioning on the first login (`registration = OPEN`, allowed domain, bootstrap). */
        fun provision(issuer: String, subject: String, email: String, displayName: String, now: Instant): User = User(
            id = UserId.new(),
            issuer = issuer,
            subject = subject,
            email = normaliseEmail(email),
            displayName = displayName,
            status = UserStatus.ACTIVE,
            createdAt = now,
            createdBy = Actor.SYSTEM,
        )
    }
}

/** A named set of users; may be synchronised from the identity provider's groups claim (`externalGroup`). */
data class Group(
    val id: GroupId,
    val name: String,
    val description: String?,
    val memberIds: Set<UserId>,
    val externalGroup: String?,
    val createdAt: Instant,
) {
    init {
        if (name.isBlank()) throw ValidationException("group name must not be blank", "name")
    }

    /** True when the OIDC groups claim contains this group's `externalGroup` (with or without a leading `/`). */
    fun matchesClaim(claimGroups: Collection<String>): Boolean {
        val external = externalGroup?.trim()?.trimStart('/') ?: return false
        return claimGroups.any { it.trim().trimStart('/') == external }
    }
}
