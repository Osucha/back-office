package pl.mos.backoffice.identity.application.port.`in`

import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.PasswordPolicy
import pl.mos.backoffice.identity.domain.model.ResetPurpose
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant

// Local accounts (ADR 0021): e-mail + password login, TOTP, one-time set-password links.

/** The result of a login step. The adapter creates the session for [SignedIn] exactly like the OIDC login. */
sealed interface LoginOutcome {
    /** Uniform failure: the client gets `401 invalid-credentials`, the reason is only audited. */
    data object Failed : LoginOutcome

    data class MfaRequired(val mfaToken: String) : LoginOutcome

    /** [restriction] non-null = the session may only resolve it (password change, TOTP enrolment). */
    data class SignedIn(val user: User, val restriction: SessionRestriction?) : LoginOutcome
}

interface LocalLogin {
    fun login(email: String, password: String): LoginOutcome
}

interface CompleteMfaLogin {
    /** Exactly one of [code] (TOTP) and [recoveryCode]. */
    fun complete(mfaToken: String, code: String?, recoveryCode: String?): LoginOutcome
}

interface ChangePassword {
    fun change(command: Command): Result

    data class Command(
        val currentPassword: String?,
        val newPassword: String,
        /** The caller's session is restricted to the forced change right after login: no current password needed. */
        val forcedChange: Boolean = false,
        /** The caller's own session id (kept; every other session of the user is terminated). */
        val keepSessionId: String? = null,
    )

    /** [mfaEnrolmentRequired]: the platform requires MFA and the user has none yet. */
    data class Result(val user: User, val mfaEnrolmentRequired: Boolean, val terminatedSessions: Int)
}

data class IssuedResetLink(val token: String, val expiresAt: Instant, val purpose: ResetPurpose)

data class ResetLinkInfo(val email: String, val expiresAt: Instant, val purpose: ResetPurpose, val policy: PasswordPolicy)

interface PasswordResetLinks {
    /** Admin: a one-time link; the current password stops working at once and the user's sessions end. */
    fun issue(userId: UserId): IssuedResetLink

    /** Public: `NotFoundException` when the link is unknown, used or expired. */
    fun check(token: String): ResetLinkInfo

    /** Public: sets the password and signs the user in (MFA step first when TOTP is active). */
    fun complete(token: String, newPassword: String): LoginOutcome
}

data class TotpEnrolmentView(val secretBase32: String, val otpauthUri: String)

interface ManageMyMfa {
    fun startTotp(): TotpEnrolmentView
    /** Activates the pending authenticator and returns 10 recovery codes (shown once). */
    fun confirmTotp(code: String): List<String>
    fun disableTotp(currentPassword: String)
    fun regenerateRecoveryCodes(currentPassword: String): List<String>
}

interface AdminLocalAccounts {
    /** LOCAL/BOTH without a password returns [UserDetails.setPasswordToken]. */
    fun setAuthMode(userId: UserId, mode: AuthMode): UserDetails
    fun unlock(userId: UserId): UserDetails
    fun resetMfa(userId: UserId)
}

data class LoginRecord(val at: Instant, val method: String, val result: AuditResult, val sourceIp: String?)

data class MySecurityView(
    val authMode: AuthMode,
    val hasPassword: Boolean,
    val passwordChangedAt: Instant?,
    val mfaEnabled: Boolean,
    val mfaRequired: Boolean,
    val recoveryCodesRemaining: Int,
    val lastLoginAt: Instant?,
    val lastLoginMethod: LoginMethod?,
    val recentLogins: List<LoginRecord>,
    val policy: PasswordPolicy,
)

interface GetMySecurity {
    fun mySecurity(): MySecurityView
}

/** What the public login page needs (`GET /auth/config`). */
data class LoginOptions(val localLoginEnabled: Boolean, val passwordPolicy: PasswordPolicy)

interface GetLoginOptions {
    fun loginOptions(): LoginOptions
}

/** Startup: creates the configured local platform admin when no user exists yet. */
interface BootstrapLocalAdmin {
    /** The created user, or `null` when nothing was configured or users already exist. */
    fun bootstrap(): User?
}
