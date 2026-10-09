package pl.mos.backoffice.identity.application.port.`in`

import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.service.ProjectAccess
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.UserId

/** Turns the claims of a successful OIDC login into a Back Office user (JIT provisioning, invitations). */
interface ResolveLogin {
    fun resolve(claims: LoginClaims): LoginResult

    data class LoginClaims(
        val issuer: String,
        val subject: String,
        val email: String?,
        /** `email_verified`; `null` when the provider does not send it. */
        val emailVerified: Boolean?,
        val name: String?,
        val preferredUsername: String?,
        val groups: List<String> = emptyList(),
    )

    sealed interface LoginResult {
        data class Success(val user: User, val firstLogin: Boolean) : LoginResult

        /** [code] goes to `/login?error=<code>`. */
        data class Rejected(val code: String, val reason: String) : LoginResult
    }

    companion object {
        const val NOT_INVITED = "not-invited"
        const val DEACTIVATED = "deactivated"
        const val EMAIL_MISSING = "email-missing"
        const val EMAIL_NOT_VERIFIED = "email-not-verified"
        const val IDENTITY_CONFLICT = "identity-conflict"
        /** The user's `authMode` is LOCAL. */
        const val METHOD_NOT_ALLOWED = "method-not-allowed"
    }
}

/** Builds the per-request [CurrentUser]; the adapter caches the result keyed by user and access version. */
interface LoadCurrentUser {
    /** `null` when the user does not exist or is not ACTIVE. */
    fun forUser(userId: UserId): CurrentUser?

    /**
     * `null` when the token is malformed, unknown, expired or revoked. Updates `lastUsedAt` (throttled).
     * The caller then resolves the owner (through its cache) and narrows it with [forPersonalToken].
     */
    fun verifyPersonalToken(plaintext: String): PersonalAccessToken?

    /**
     * The owner's effective permissions narrowed to the token's subset (all of them when the subset is
     * empty). A narrowed token keeps `allProjects` only when the subset contains `admin.projects.all`.
     */
    fun forPersonalToken(owner: CurrentUser, token: PersonalAccessToken): CurrentUser

    /** A project CI token verified by the Projects context: `ci.execute` and `ci.signal` on its project. */
    fun forCiToken(identity: CiTokenIdentity): CurrentUser
}

/** Authentication events that are not use-case mutations (recorded by the adapter's handlers). */
interface RecordAuthEvent {
    fun loggedOut(user: CurrentUser)
    fun sessionExpired(userId: UserId, reason: String)

    /** The provider or the code exchange failed before claims were available (e.g. `access_denied`, clock skew). */
    fun loginFailed(code: String, detail: String?)

    /** A local login, MFA or set-password request was refused by the per-IP throttle (429). */
    fun loginThrottled(path: String)
}

data class MeView(
    val user: User,
    val current: CurrentUser,
    val groups: List<Group>,
    val projects: List<ProjectAccess>,
)

interface GetMe {
    fun me(): MeView
}

interface UpdateMyPreferences {
    fun update(preferences: UserPreferences): UserPreferences
}

/** Public login page data. */
interface GetRegistrationMode {
    fun registrationMode(): RegistrationMode
}
