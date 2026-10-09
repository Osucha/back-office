package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.`in`.RecordAuthEvent
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin.LoginClaims
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin.LoginResult
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.TokenCodec
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.TokenCapabilities
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant

/**
 * Just-in-time provisioning (08-identity-and-access.md, "Users and provisioning"):
 * 1. a user bound to (issuer, subject) logs in;
 * 2. otherwise an invited user with the same e-mail is bound and activated;
 * 3. otherwise a new ACTIVE user is created when the e-mail is a bootstrap admin, registration is OPEN, or
 *    (INVITE_ONLY) its domain is allowed; everyone else is rejected with `not-invited`.
 * Deactivated users are rejected. Bootstrap admins receive `platform-admin` on their first login. Groups are
 * synchronised from the groups claim when the platform setting is on.
 */
class ResolveLoginService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val grants: GrantRepository,
    private val settings: SettingsProvider,
    private val options: IdentityOptions,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val clock: Clock,
) : ResolveLogin {

    @SystemOperation("called by the OIDC login success handler before any principal exists")
    override fun resolve(claims: LoginClaims): LoginResult {
        val email = claims.email?.let(User::normaliseEmail)?.takeIf { it.isNotEmpty() }
        val now = clock.now()
        val current = settings.current()

        val match = when (val m = matchUser(claims, email, current, now)) {
            is UserMatch.Rejected -> return m.result
            is UserMatch.Found -> m
        }
        var user = match.user
        var accessChanged = match.accessChanged
        if (!user.authMode.allowsOidc) return reject(ResolveLogin.METHOD_NOT_ALLOWED, MSG_LOCAL_ONLY, claims, user)
        if (user.status == UserStatus.DEACTIVATED) return reject(ResolveLogin.DEACTIVATED, MSG_DEACTIVATED, claims, user)

        val firstLogin = user.lastLoginAt == null
        if (email != null && email != user.email && users.findByEmail(email) == null) user = user.copy(email = email)
        if (current.syncGroupsFromIdentityProvider) {
            val synced = syncGroups(user, claims.groups)
            if (synced.groupIds != user.groupIds) accessChanged = true
            user = synced
        }
        user = users.save(user.loggedIn(now, LoginMethod.OIDC))
        val actor = Actor(PrincipalKind.USER, user.id.value, user.displayName)

        if (match.provisioned) {
            audit.record(AuditEntry(IdentityAudit.USER_PROVISION, IdentityAudit.ENTITY_USER, user.id.toString(), details = mapOf("email" to user.email), actor = actor))
        }
        if (firstLogin && user.email in options.normalisedBootstrapAdmins && grantBootstrapAdmin(user)) accessChanged = true
        if (accessChanged) accessVersion.increment()
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_SUCCESS, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf("method" to LoginMethod.OIDC.name, "email" to user.email, "issuer" to claims.issuer, "firstLogin" to firstLogin),
                actor = actor,
            ),
        )
        return LoginResult.Success(user, firstLogin)
    }

    /** The user of a login: found by identity, an invited user bound now, a newly provisioned user, or a recorded rejection. */
    private sealed interface UserMatch {
        data class Found(val user: User, val provisioned: Boolean = false, val accessChanged: Boolean = false) : UserMatch
        data class Rejected(val result: LoginResult) : UserMatch
    }

    private fun matchUser(claims: LoginClaims, email: String?, current: PlatformSettings, now: Instant): UserMatch {
        users.findByIdentity(claims.issuer, claims.subject)?.let { return UserMatch.Found(it) }
        if (email == null) return rejected(ResolveLogin.EMAIL_MISSING, "the identity provider sent no e-mail", claims, null)
        if (claims.emailVerified == false) return rejected(ResolveLogin.EMAIL_NOT_VERIFIED, "e-mail not verified by the provider", claims, null)
        val invited = users.findByEmail(email)
        return when {
            invited != null && invited.isBound -> rejected(ResolveLogin.IDENTITY_CONFLICT, "e-mail already bound to another identity", claims, invited)
            invited != null -> bindInvited(invited, claims)
            mayProvision(email, current) ->
                UserMatch.Found(User.provision(claims.issuer, claims.subject, email, displayName(claims, email), now), provisioned = true, accessChanged = true)
            else -> rejected(ResolveLogin.NOT_INVITED, "e-mail is not invited and its domain is not allowed", claims, null)
        }
    }

    /** An invited (unbound) user signs in with OIDC for the first time: bind the identity. */
    private fun bindInvited(invited: User, claims: LoginClaims): UserMatch {
        if (!invited.authMode.allowsOidc) return rejected(ResolveLogin.METHOD_NOT_ALLOWED, MSG_LOCAL_ONLY, claims, invited)
        if (invited.status == UserStatus.DEACTIVATED) return rejected(ResolveLogin.DEACTIVATED, MSG_DEACTIVATED, claims, invited)
        return UserMatch.Found(invited.bind(claims.issuer, claims.subject), accessChanged = true)
    }

    /** Bootstrap admins, OPEN registration, or (INVITE_ONLY) an allowed e-mail domain. */
    private fun mayProvision(email: String, current: PlatformSettings): Boolean =
        email in options.normalisedBootstrapAdmins || current.registration == RegistrationMode.OPEN || current.allowsDomainOf(email)

    /** Gives a bootstrap admin `platform-admin` on the first login; true when the grant was added. */
    private fun grantBootstrapAdmin(user: User): Boolean {
        val set = grants.find(GrantLevel.USER, user.id.value)
        if (BuiltInRoles.PLATFORM_ADMIN in set.roles) return false
        grants.save(set.copy(roles = set.roles + BuiltInRoles.PLATFORM_ADMIN))
        audit.record(
            AuditEntry(
                IdentityAudit.ROLE_ASSIGN, IdentityAudit.ENTITY_GRANT, user.id.toString(),
                details = mapOf("level" to "USER", "role" to BuiltInRoles.PLATFORM_ADMIN, "reason" to "bootstrap-admin"),
                actor = Actor.SYSTEM,
            ),
        )
        return true
    }

    private fun rejected(code: String, reason: String, claims: LoginClaims, user: User?): UserMatch =
        UserMatch.Rejected(reject(code, reason, claims, user))

    private fun syncGroups(user: User, claimGroups: List<String>): User {
        val external = groups.findAll().filter { it.externalGroup != null }
        val matching = external.filter { it.matchesClaim(claimGroups) }.map { it.id }.toSet()
        return user.copy(groupIds = user.groupIds - external.map { it.id }.toSet() + matching)
    }

    private fun displayName(claims: LoginClaims, email: String): String =
        claims.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: claims.preferredUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?: email.substringBefore('@')

    private fun reject(code: String, reason: String, claims: LoginClaims, user: User?): LoginResult {
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_FAILURE, IdentityAudit.ENTITY_USER, user?.id?.toString(),
                result = AuditResult.FAILURE,
                details = mapOf("reason" to code, "method" to LoginMethod.OIDC.name, "message" to reason, "email" to claims.email, "issuer" to claims.issuer, "subject" to claims.subject),
                actor = Actor(PrincipalKind.USER, user?.id?.value, user?.displayName ?: claims.email ?: claims.subject),
            ),
        )
        return LoginResult.Rejected(code, reason)
    }

    private companion object {
        const val MSG_LOCAL_ONLY = "user may only sign in locally"
        const val MSG_DEACTIVATED = "user is deactivated"
    }
}

/** Builds [CurrentUser] for sessions, personal tokens and CI tokens (cached by the adapter). */
class LoadCurrentUserService(
    private val users: UserRepository,
    private val tokens: PersonalTokenRepository,
    private val access: AccessResolver,
    private val clock: Clock,
) : LoadCurrentUser {

    @SystemOperation("authentication: builds the principal of every request")
    override fun forUser(userId: UserId): CurrentUser? {
        val user = users.findById(userId)?.takeIf { it.status == UserStatus.ACTIVE } ?: return null
        return access.resolve(user).toCurrentUser()
    }

    @SystemOperation("authentication: verifies a bearer personal access token")
    override fun verifyPersonalToken(plaintext: String): PersonalAccessToken? {
        if (!TokenCodec.looksLikePersonalToken(plaintext)) return null
        val token = tokens.findByHash(TokenCodec.hash(plaintext)) ?: return null
        val now = clock.now()
        if (!token.isUsable(now)) return null
        if (token.lastUsedAt == null || Duration.between(token.lastUsedAt, now) > TOUCH_INTERVAL) tokens.touch(token.id, now)
        return token
    }

    @SystemOperation("authentication: narrows the owner's permissions to the token's subset")
    override fun forPersonalToken(owner: CurrentUser, token: PersonalAccessToken): CurrentUser {
        val subset = token.permissions
        val capabilities = if (token.allowMcpGateDecisions) setOf(TokenCapabilities.MCP_GATE_DECISIONS) else emptySet()
        if (subset.isEmpty()) return owner.copy(kind = PrincipalKind.PERSONAL_TOKEN, tokenCapabilities = capabilities)
        return owner.copy(
            kind = PrincipalKind.PERSONAL_TOKEN,
            tokenCapabilities = capabilities,
            platformPermissions = owner.platformPermissions intersect subset,
            projectPermissions = owner.projectPermissions.mapValues { it.value intersect subset }.filterValues { it.isNotEmpty() },
            allProjects = owner.allProjects && IdentityPermissions.PROJECTS_ALL in subset,
        )
    }

    @SystemOperation("authentication: a CI token verified by the Projects context")
    override fun forCiToken(identity: CiTokenIdentity): CurrentUser = CurrentUser(
        kind = PrincipalKind.CI_TOKEN,
        userId = null,
        login = "ci:${identity.projectKey}",
        displayName = "CI token ${identity.tokenName} (${identity.projectKey})",
        platformPermissions = emptySet(),
        projectPermissions = mapOf(identity.projectId to CI_PERMISSIONS),
    )

    companion object {
        val CI_PERMISSIONS = setOf("ci.execute", "ci.signal")
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

class RecordAuthEventService(private val audit: AuditPort) : RecordAuthEvent {

    @SystemOperation("authentication event recorded by the logout endpoint")
    override fun loggedOut(user: CurrentUser) {
        audit.record(AuditEntry(IdentityAudit.LOGOUT, IdentityAudit.ENTITY_USER, user.userId?.toString(), actor = user.actor))
    }

    @SystemOperation("authentication event recorded by the session filter")
    override fun sessionExpired(userId: UserId, reason: String) {
        audit.record(
            AuditEntry(
                IdentityAudit.SESSION_EXPIRED, IdentityAudit.ENTITY_USER, userId.toString(),
                details = mapOf("reason" to reason),
                actor = Actor(PrincipalKind.USER, userId.value, userId.toString()),
            ),
        )
    }

    @SystemOperation("authentication event recorded by the login throttle")
    override fun loginThrottled(path: String) {
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_FAILURE, IdentityAudit.ENTITY_USER, null,
                result = AuditResult.FAILURE,
                details = mapOf("reason" to IdentityAudit.FailureReason.THROTTLED, "method" to LoginMethod.LOCAL.name, "path" to path.take(200)),
                actor = Actor(PrincipalKind.USER, null, "unknown"),
            ),
        )
    }

    @SystemOperation("authentication event recorded by the OIDC login failure handler")
    override fun loginFailed(code: String, detail: String?) {
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_FAILURE, IdentityAudit.ENTITY_USER, null,
                result = AuditResult.FAILURE,
                details = mapOf("reason" to code, "message" to detail?.take(500)),
                actor = Actor(PrincipalKind.USER, null, "unknown"),
            ),
        )
    }
}
