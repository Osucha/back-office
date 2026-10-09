package pl.mos.backoffice.identity.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant

/** How a user may sign in (ADR 0021). OIDC users never have a password; LOCAL users cannot use the provider. */
enum class AuthMode {
    LOCAL,
    OIDC,
    BOTH,
    ;

    val allowsLocal: Boolean get() = this == LOCAL || this == BOTH
    val allowsOidc: Boolean get() = this == OIDC || this == BOTH
}

/** The method of a successful sign-in, kept on the user and in the session. */
enum class LoginMethod { LOCAL, OIDC }

/**
 * The state a session is restricted to right after a local login: only the endpoints that resolve it (and
 * `/me`, logout) are allowed until the user changed the password or enrolled TOTP.
 */
enum class SessionRestriction { PASSWORD_CHANGE, MFA_ENROLMENT }

/** Lockout parameters: [maxFailures] consecutive failures lock the account for [lockDuration], doubling up to [maxLockDuration]. */
data class LockoutPolicy(
    val maxFailures: Int = 5,
    val lockDuration: Duration = Duration.ofMinutes(15),
    val maxLockDuration: Duration = Duration.ofHours(24),
) {
    /** Duration of the lockout number [lockoutCount] (0-based): 15 min, 30 min, 60 min ... capped at 24 h. */
    fun durationOf(lockoutCount: Int): Duration {
        var duration = lockDuration
        repeat(lockoutCount.coerceAtMost(MAX_DOUBLINGS)) {
            duration = duration.multipliedBy(2)
            if (duration >= maxLockDuration) return maxLockDuration
        }
        return if (duration > maxLockDuration) maxLockDuration else duration
    }

    private companion object {
        const val MAX_DOUBLINGS = 20
    }
}

/**
 * The local password of a user and its brute-force state. [passwordHash] is `null` when no password is set
 * (invited local user, or right after an administrator issued a reset link: the old password stops working).
 */
data class LocalCredential(
    val userId: UserId,
    val passwordHash: String? = null,
    val passwordChangedAt: Instant? = null,
    val mustChangePassword: Boolean = false,
    val failedAttempts: Int = 0,
    val lockedUntil: Instant? = null,
    /** How many lockouts happened since the last successful login or unlock; each doubles the next one. */
    val lockoutCount: Int = 0,
) {
    val hasPassword: Boolean get() = passwordHash != null

    fun isLocked(now: Instant): Boolean = lockedUntil != null && now.isBefore(lockedUntil)

    /** A wrong password or MFA code. Returns the new state and whether this failure locked the account. */
    fun failed(now: Instant, policy: LockoutPolicy): Pair<LocalCredential, Boolean> {
        val attempts = failedAttempts + 1
        if (attempts < policy.maxFailures) return copy(failedAttempts = attempts) to false
        val locked = copy(failedAttempts = 0, lockedUntil = now.plus(policy.durationOf(lockoutCount)), lockoutCount = lockoutCount + 1)
        return locked to true
    }

    fun succeeded(): LocalCredential = copy(failedAttempts = 0, lockedUntil = null, lockoutCount = 0)

    fun unlocked(): LocalCredential = copy(failedAttempts = 0, lockedUntil = null, lockoutCount = 0)

    fun withPassword(hash: String, now: Instant, mustChange: Boolean = false): LocalCredential =
        copy(passwordHash = hash, passwordChangedAt = now, mustChangePassword = mustChange, failedAttempts = 0, lockedUntil = null, lockoutCount = 0)

    fun withoutPassword(): LocalCredential = copy(passwordHash = null, mustChangePassword = false)

    companion object {
        fun empty(userId: UserId) = LocalCredential(userId)
    }
}

/** A TOTP authenticator. [confirmedAt] `null` = enrolment started but not confirmed (not active). */
data class MfaTotp(
    val userId: UserId,
    /** AES-GCM encrypted base32 secret (adapter format). */
    val encryptedSecret: String,
    val createdAt: Instant,
    val confirmedAt: Instant? = null,
    /** Last accepted 30-second step; a code of this step or an earlier one is rejected (replay). */
    val lastUsedStep: Long? = null,
) {
    val isActive: Boolean get() = confirmedAt != null
}

/** One single-use recovery code; only a keyed hash is stored. */
data class RecoveryCode(val userId: UserId, val hash: String, val createdAt: Instant, val usedAt: Instant? = null)

enum class ResetPurpose { SET_INITIAL, RESET }

/** A one-time set-password link (256-bit token, SHA-256 at rest). */
data class PasswordResetToken(
    val hash: String,
    val userId: UserId,
    val purpose: ResetPurpose,
    val expiresAt: Instant,
    val createdAt: Instant,
    val createdBy: Actor,
    val usedAt: Instant? = null,
) {
    fun isUsable(now: Instant): Boolean = usedAt == null && now.isBefore(expiresAt)
}

/**
 * Password rules (NIST SP 800-63B): length only, no composition rules; rejects common passwords and
 * passwords containing the local part of the e-mail address (case-insensitive, when it has at least 3 characters).
 */
data class PasswordPolicy(
    val minLength: Int = DEFAULT_MIN,
    val maxLength: Int = ABSOLUTE_MAX,
    val rejectCommon: Boolean = true,
    val rejectContainingEmail: Boolean = true,
) {
    init {
        if (minLength !in ABSOLUTE_MIN..ABSOLUTE_MAX) throw ValidationException("minLength must be between $ABSOLUTE_MIN and $ABSOLUTE_MAX", "passwordPolicy.minLength")
        if (maxLength !in maxOf(minLength, MIN_OF_MAX)..ABSOLUTE_MAX) {
            throw ValidationException("maxLength must be between ${maxOf(minLength, MIN_OF_MAX)} and $ABSOLUTE_MAX", "passwordPolicy.maxLength")
        }
    }

    /** Human-readable violations, empty when the password is acceptable. */
    fun violations(password: String, email: String?, isCommon: (String) -> Boolean): List<String> {
        val problems = mutableListOf<String>()
        val length = password.codePointCount(0, password.length)
        if (length < minLength) problems += "must have at least $minLength characters"
        if (length > maxLength) problems += "must have at most $maxLength characters"
        if (password.isBlank()) problems += "must not be blank"
        if (rejectCommon && password.isNotEmpty() && commonVariants(password).any(isCommon)) problems += "is too common"
        val localPart = email?.substringBefore('@')?.trim()?.lowercase()
        if (rejectContainingEmail && localPart != null && localPart.length >= MIN_LOCAL_PART && password.lowercase().contains(localPart)) {
            problems += "must not contain your e-mail name"
        }
        return problems
    }

    fun validate(password: String, email: String?, isCommon: (String) -> Boolean, field: String = "newPassword") {
        val problems = violations(password, email, isCommon)
        if (problems.isNotEmpty()) throw ValidationException(problems.map { ValidationException.FieldError(field, "password $it") })
    }

    companion object {
        private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')
        private const val MIN_CORE = 4

        /**
         * The password itself plus the "core" users typically decorate to pass a length rule: lower-cased,
         * leading/trailing digits and symbols stripped, leetspeak undone, and a core repeated to reach the
         * length (`dragondragon`). Common-password lists hold short words, so without this a 12-character
         * minimum makes the list almost useless (`Password2026!` would pass).
         */
        fun commonVariants(password: String): Set<String> {
            val lower = password.lowercase()
            val core = lower.trim { !it.isLetter() }
            // strip the decoration first, then undo leetspeak inside the core (p4ssw0rd123456 -> password)
            val deLeet = core.map { LEET[it] ?: it }.joinToString("").trim { !it.isLetter() }
            val variants = linkedSetOf(password, lower)
            for (c in listOf(core, deLeet)) {
                if (c.length < MIN_CORE) continue
                variants += c
                for (unit in 1..c.length / 2) {
                    if (c.length % unit == 0 && c.chunked(unit).toSet().size == 1) {
                        variants += c.substring(0, unit)
                        break
                    }
                }
            }
            return variants
        }

        const val DEFAULT_MIN = 12
        const val ABSOLUTE_MIN = 8
        const val MIN_OF_MAX = 64
        const val ABSOLUTE_MAX = 128
        const val MIN_LOCAL_PART = 3
    }
}

/** Lockout settings as the administrator edits them (`PlatformSettings.lockout`). */
data class LockoutSettings(val maxFailures: Int = 5, val lockMinutes: Int = 15) {
    init {
        if (maxFailures !in 1..MAX_FAILURES) throw ValidationException("lockout.maxFailures must be between 1 and $MAX_FAILURES", "lockout.maxFailures")
        if (lockMinutes !in 1..MAX_LOCK_MINUTES) throw ValidationException("lockout.lockMinutes must be between 1 and $MAX_LOCK_MINUTES", "lockout.lockMinutes")
    }

    fun policy(): LockoutPolicy = LockoutPolicy(maxFailures, Duration.ofMinutes(lockMinutes.toLong()))

    companion object {
        const val MAX_FAILURES = 100
        const val MAX_LOCK_MINUTES = 1440
    }
}

/**
 * Wrong credentials (current password, MFA code). Not a `DomainException` (that hierarchy is sealed in the
 * shared kernel); the identity adapter maps it to `401 invalid-credentials`. [reason] is for the audit log only.
 */
class InvalidCredentialsException(val reason: String) : RuntimeException("invalid credentials")
