package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.AdminLocalAccounts
import pl.mos.backoffice.identity.application.port.`in`.BootstrapLocalAdmin
import pl.mos.backoffice.identity.application.port.`in`.ChangePassword
import pl.mos.backoffice.identity.application.port.`in`.CompleteMfaLogin
import pl.mos.backoffice.identity.application.port.`in`.GetMySecurity
import pl.mos.backoffice.identity.application.port.`in`.IssuedResetLink
import pl.mos.backoffice.identity.application.port.`in`.LocalAccountView
import pl.mos.backoffice.identity.application.port.`in`.LocalLogin
import pl.mos.backoffice.identity.application.port.`in`.LoginOutcome
import pl.mos.backoffice.identity.application.port.`in`.LoginRecord
import pl.mos.backoffice.identity.application.port.`in`.ManageMyMfa
import pl.mos.backoffice.identity.application.port.`in`.MySecurityView
import pl.mos.backoffice.identity.application.port.`in`.PasswordResetLinks
import pl.mos.backoffice.identity.application.port.`in`.ResetLinkInfo
import pl.mos.backoffice.identity.application.port.`in`.TotpEnrolmentView
import pl.mos.backoffice.identity.application.port.`in`.UserDetails
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.CodeHasher
import pl.mos.backoffice.identity.application.port.out.CommonPasswords
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.LocalCredentialRepository
import pl.mos.backoffice.identity.application.port.out.MfaChallenges
import pl.mos.backoffice.identity.application.port.out.MfaRepository
import pl.mos.backoffice.identity.application.port.out.PasswordHasher
import pl.mos.backoffice.identity.application.port.out.PasswordResetTokenRepository
import pl.mos.backoffice.identity.application.port.out.SecretCipher
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.IdentityAudit.FailureReason
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.InvalidCredentialsException
import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.MfaTotp
import pl.mos.backoffice.identity.domain.model.PasswordResetToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.RecoveryCode
import pl.mos.backoffice.identity.domain.model.ResetPurpose
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.Base32
import pl.mos.backoffice.identity.domain.service.RecoveryCodeGenerator
import pl.mos.backoffice.identity.domain.service.ResetTokenCodec
import pl.mos.backoffice.identity.domain.service.TotpVerifier
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.security.UserId

/**
 * The ports and rules shared by the local-account use cases (ADR 0021). Not a use case itself: callers
 * decide about authorisation.
 */
class LocalAccountSupport(
    val users: UserRepository,
    val credentials: LocalCredentialRepository,
    val mfa: MfaRepository,
    val resetTokens: PasswordResetTokenRepository,
    val hasher: PasswordHasher,
    val cipher: SecretCipher,
    val codeHasher: CodeHasher,
    val challenges: MfaChallenges,
    val commonPasswords: CommonPasswords,
    val settings: SettingsProvider,
    val options: IdentityOptions,
    val audit: AuditPort,
    val clock: Clock,
) {
    fun localLoginAvailable(current: PlatformSettings = settings.current()): Boolean = options.local.enabled && current.localLoginEnabled

    fun view(user: User): LocalAccountView {
        val credential = credentials.find(user.id)
        val now = clock.now()
        return LocalAccountView(
            hasPassword = credential?.hasPassword == true,
            mfaEnabled = mfa.findTotp(user.id)?.isActive == true,
            lockedUntil = credential?.lockedUntil?.takeIf { now.isBefore(it) },
            mustChangePassword = credential?.mustChangePassword == true,
        )
    }

    fun validatePassword(password: String, email: String, field: String = "newPassword") =
        settings.current().passwordPolicy.validate(password, email, commonPasswords::contains, field)

    /**
     * A new one-time link for [user] (earlier unused links stop working). SET_INITIAL when the user never had
     * a password, RESET otherwise. Audited as `PASSWORD_RESET_ISSUE`; the token itself is never recorded.
     */
    fun issueLink(user: User, by: Actor): IssuedResetLink {
        val now = clock.now()
        val credential = credentials.find(user.id)
        val purpose = if (credential?.passwordChangedAt == null && credential?.hasPassword != true) ResetPurpose.SET_INITIAL else ResetPurpose.RESET
        resetTokens.invalidateAllOf(user.id, now)
        val token = ResetTokenCodec.generate()
        val expiresAt = now.plus(options.local.resetLinkValidity)
        resetTokens.save(PasswordResetToken(ResetTokenCodec.hash(token), user.id, purpose, expiresAt, now, by))
        audit.record(
            AuditEntry(
                IdentityAudit.PASSWORD_RESET_ISSUE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf("purpose" to purpose.name, "expiresAt" to expiresAt.toString(), "email" to user.email),
            ),
        )
        return IssuedResetLink(token, expiresAt, purpose)
    }

    /** After the password step: the MFA step when TOTP is active, otherwise the session. */
    fun afterPassword(user: User, credential: LocalCredential): LoginOutcome =
        if (mfa.findTotp(user.id)?.isActive == true) LoginOutcome.MfaRequired(challenges.issue(user.id)) else signIn(user, credential, mfaUsed = false)

    /** Every check passed: records the login and decides whether the session is restricted. */
    fun signIn(user: User, credential: LocalCredential, mfaUsed: Boolean): LoginOutcome.SignedIn {
        val now = clock.now()
        val saved = users.save(user.loggedIn(now, LoginMethod.LOCAL))
        val restriction = when {
            credential.mustChangePassword -> SessionRestriction.PASSWORD_CHANGE
            settings.current().mfaRequiredForLocal && mfa.findTotp(user.id)?.isActive != true -> SessionRestriction.MFA_ENROLMENT
            else -> null
        }
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_SUCCESS, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf(
                    "method" to LoginMethod.LOCAL.name, "email" to user.email, "mfa" to mfaUsed,
                    "firstLogin" to (user.lastLoginAt == null), "restriction" to restriction?.name,
                ),
                actor = actorOf(user),
            ),
        )
        return LoginOutcome.SignedIn(saved, restriction)
    }

    fun failed(reason: String, email: String?, user: User?): LoginOutcome.Failed {
        audit.record(
            AuditEntry(
                IdentityAudit.LOGIN_FAILURE, IdentityAudit.ENTITY_USER, user?.id?.toString(),
                result = AuditResult.FAILURE,
                details = mapOf("reason" to reason, "method" to LoginMethod.LOCAL.name, "email" to email),
                actor = user?.let(::actorOf) ?: Actor(PrincipalKind.USER, null, email?.take(EMAIL_MAX) ?: "unknown"),
            ),
        )
        return LoginOutcome.Failed
    }

    /** A wrong password or code: counts towards the lockout and audits `ACCOUNT_LOCKED` when it locks. */
    fun countFailure(user: User, credential: LocalCredential) {
        val now = clock.now()
        val (next, locked) = credential.failed(now, settings.current().lockout.policy())
        credentials.save(next)
        if (locked) {
            audit.record(
                AuditEntry(
                    IdentityAudit.ACCOUNT_LOCKED, IdentityAudit.ENTITY_USER, user.id.toString(),
                    result = AuditResult.FAILURE,
                    details = mapOf("lockedUntil" to next.lockedUntil.toString(), "lockoutCount" to next.lockoutCount, "email" to user.email),
                    actor = Actor.SYSTEM,
                ),
            )
        }
    }

    /** Verifies the caller's current password (self-service operations); throws [InvalidCredentialsException]. */
    fun verifyCurrentPassword(userId: UserId, password: String?) {
        val hash = credentials.find(userId)?.passwordHash
        if (password.isNullOrEmpty() || hash == null || !hasher.matches(password, hash)) throw InvalidCredentialsException(FailureReason.BAD_PASSWORD)
    }

    fun newRecoveryCodes(userId: UserId): List<String> {
        val now = clock.now()
        val codes = RecoveryCodeGenerator.generate()
        mfa.replaceRecoveryCodes(userId, codes.map { RecoveryCode(userId, codeHasher.hash(it), now) })
        return codes
    }

    fun actorOf(user: User) = Actor(PrincipalKind.USER, user.id.value, user.displayName)

    companion object {
        private const val EMAIL_MAX = 200
    }
}

/** Resolves the signed-in user of a self-service call (sessions of real users only). */
private fun selfOf(gate: AuthorizationGate, users: UserRepository): Pair<CurrentUser, User> {
    val me = gate.currentUser()
    val id = me.userId
    if (me.kind != PrincipalKind.USER || id == null) throw InvalidStateException("only a signed-in user can manage their own credentials")
    return me to (users.findById(id) ?: throw NotFoundException("user", id))
}

/**
 * `POST /auth/login` and `/auth/login/mfa`. Every failure is the same [LoginOutcome.Failed]; every password
 * attempt costs exactly one hash (a dummy hash for unknown e-mails) so timing does not reveal accounts.
 */
class LocalLoginService(private val local: LocalAccountSupport) : LocalLogin, CompleteMfaLogin {

    private val users get() = local.users

    @PublicOperation("anonymous callers sign in with e-mail and password; the outcome is uniform")
    override fun login(email: String, password: String): LoginOutcome {
        val normalised = User.normaliseEmail(email).takeIf { it.isNotEmpty() && it.length <= MAX_EMAIL }
        val user = normalised?.let(users::findByEmail)
        val credential = user?.let { local.credentials.find(it.id) }
        val hash = credential?.passwordHash
        val input = password.take(MAX_PASSWORD_INPUT)
        // exactly one hash computation on every path
        val matches = local.hasher.matches(input, hash ?: local.hasher.dummyHash) && hash != null
        val now = local.clock.now()
        val settings = local.settings.current()
        return when {
            user == null -> local.failed(FailureReason.UNKNOWN_USER, normalised ?: email.take(MAX_EMAIL), null)
            !local.localLoginAvailable(settings) || !user.authMode.allowsLocal -> local.failed(FailureReason.METHOD_NOT_ALLOWED, user.email, user)
            user.status != UserStatus.ACTIVE -> local.failed(FailureReason.INACTIVE, user.email, user)
            credential == null -> local.failed(FailureReason.BAD_PASSWORD, user.email, user)
            credential.isLocked(now) -> local.failed(FailureReason.LOCKED, user.email, user)
            !matches -> {
                if (hash != null) local.countFailure(user, credential)
                local.failed(FailureReason.BAD_PASSWORD, user.email, user)
            }
            else -> {
                var ok = credential.succeeded()
                if (local.hasher.needsRehash(hash!!)) ok = ok.copy(passwordHash = local.hasher.hash(input))
                if (ok != credential) local.credentials.save(ok)
                local.afterPassword(user, ok)
            }
        }
    }

    @PublicOperation("second login step; the signed single-use mfaToken proves the password step")
    override fun complete(mfaToken: String, code: String?, recoveryCode: String?): LoginOutcome {
        val userId = local.challenges.consume(mfaToken) ?: return local.failed(FailureReason.BAD_MFA, null, null)
        val user = users.findById(userId) ?: return local.failed(FailureReason.UNKNOWN_USER, null, null)
        val credential = local.credentials.find(userId) ?: return local.failed(FailureReason.BAD_PASSWORD, user.email, user)
        val totp = local.mfa.findTotp(userId)?.takeIf { it.isActive } ?: return local.failed(FailureReason.BAD_MFA, user.email, user)
        val now = local.clock.now()
        if (!local.localLoginAvailable() || !user.authMode.allowsLocal) return local.failed(FailureReason.METHOD_NOT_ALLOWED, user.email, user)
        if (user.status != UserStatus.ACTIVE) return local.failed(FailureReason.INACTIVE, user.email, user)
        if (credential.isLocked(now)) return local.failed(FailureReason.LOCKED, user.email, user)

        val accepted = when {
            !code.isNullOrBlank() -> {
                val step = TotpVerifier.verify(local.cipher.decrypt(totp.encryptedSecret), code, now, totp.lastUsedStep)
                if (step != null) local.mfa.saveTotp(totp.copy(lastUsedStep = step))
                step != null
            }
            !recoveryCode.isNullOrBlank() -> {
                val normalised = RecoveryCodeGenerator.normalise(recoveryCode)
                val used = normalised != null && local.mfa.useRecoveryCode(userId, local.codeHasher.hash(normalised), now)
                if (used) {
                    val remaining = local.mfa.recoveryCodes(userId).count { it.usedAt == null }
                    local.audit.record(
                        AuditEntry(
                            IdentityAudit.RECOVERY_CODE_USED, IdentityAudit.ENTITY_USER, userId.toString(),
                            details = mapOf("remaining" to remaining), actor = local.actorOf(user),
                        ),
                    )
                }
                used
            }
            else -> false
        }
        if (!accepted) {
            local.countFailure(user, credential)
            return local.failed(FailureReason.BAD_MFA, user.email, user)
        }
        val ok = credential.succeeded()
        if (ok != credential) local.credentials.save(ok)
        return local.signIn(user, ok, mfaUsed = true)
    }

    private companion object {
        const val MAX_EMAIL = 320
        /** Longer inputs are cut before hashing (the policy allows at most 128 characters anyway). */
        const val MAX_PASSWORD_INPUT = 1024
    }
}

class ChangePasswordService(
    private val local: LocalAccountSupport,
    private val sessions: SessionTerminator,
    private val gate: AuthorizationGate,
) : ChangePassword {

    @PublicOperation("users change their own password; the current password or the forced-change session proves it")
    override fun change(command: ChangePassword.Command): ChangePassword.Result {
        val (_, user) = selfOf(gate, local.users)
        if (!user.authMode.allowsLocal) throw InvalidStateException("this account signs in through the identity provider; change the password there")
        val credential = local.credentials.find(user.id)
        val forced = command.forcedChange && credential?.mustChangePassword == true
        if (!forced) {
            if (command.currentPassword.isNullOrEmpty()) throw ValidationException("currentPassword is required", "currentPassword")
            local.verifyCurrentPassword(user.id, command.currentPassword)
        }
        local.validatePassword(command.newPassword, user.email)
        val oldHash = credential?.passwordHash
        if (oldHash != null && local.hasher.matches(command.newPassword, oldHash)) {
            throw ValidationException("the new password must differ from the current one", "newPassword")
        }
        val now = local.clock.now()
        local.credentials.save((credential ?: LocalCredential.empty(user.id)).withPassword(local.hasher.hash(command.newPassword), now))
        local.resetTokens.invalidateAllOf(user.id, now)
        val terminated = sessions.terminateOthers(user.id, command.keepSessionId)
        local.audit.record(
            AuditEntry(
                IdentityAudit.PASSWORD_CHANGE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf("forced" to forced, "terminatedSessions" to terminated),
            ),
        )
        val mfaMissing = local.settings.current().mfaRequiredForLocal && local.mfa.findTotp(user.id)?.isActive != true
        return ChangePassword.Result(user, mfaMissing, terminated)
    }
}

class PasswordResetService(
    private val local: LocalAccountSupport,
    private val sessions: SessionTerminator,
    private val accessVersion: AccessVersion,
    private val gate: AuthorizationGate,
) : PasswordResetLinks {

    override fun issue(userId: UserId): IssuedResetLink {
        val me = gate.require(IdentityPermissions.USERS_MANAGE)
        val user = local.users.findById(userId) ?: throw NotFoundException("user", userId)
        if (!user.authMode.allowsLocal) throw InvalidStateException("user ${user.email} signs in through the identity provider only; switch the sign-in method to LOCAL or BOTH first")
        if (user.status == UserStatus.DEACTIVATED) throw InvalidStateException("user ${user.email} is deactivated")
        val link = local.issueLink(user, me.actor)
        val credential = local.credentials.find(userId)
        if (credential?.hasPassword == true) local.credentials.save(credential.withoutPassword())
        val terminated = sessions.terminateAll(userId)
        if (terminated > 0) {
            local.audit.record(AuditEntry(IdentityAudit.SESSIONS_TERMINATE, IdentityAudit.ENTITY_USER, userId.toString(), details = mapOf("terminatedSessions" to terminated, "reason" to "password-reset")))
            accessVersion.increment()
        }
        return link
    }

    @PublicOperation("the unguessable one-time link is the credential")
    override fun check(token: String): ResetLinkInfo {
        val (reset, user) = usable(token)
        return ResetLinkInfo(user.email, reset.expiresAt, reset.purpose, local.settings.current().passwordPolicy)
    }

    @PublicOperation("the unguessable one-time link is the credential")
    override fun complete(token: String, newPassword: String): LoginOutcome {
        val (reset, user) = usable(token)
        if (!local.localLoginAvailable()) throw InvalidStateException("local login is disabled on this installation")
        local.validatePassword(newPassword, user.email)
        val now = local.clock.now()
        if (!local.resetTokens.markUsed(reset.hash, now)) throw NotFoundException("set-password link", "provided")
        local.resetTokens.invalidateAllOf(user.id, now)
        val credential = (local.credentials.find(user.id) ?: LocalCredential.empty(user.id)).withPassword(local.hasher.hash(newPassword), now)
        local.credentials.save(credential)
        val active = local.users.save(user.passwordSet())
        local.audit.record(
            AuditEntry(
                IdentityAudit.PASSWORD_RESET_COMPLETE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf("purpose" to reset.purpose.name, "email" to user.email),
                actor = local.actorOf(user),
            ),
        )
        if (active.status != user.status) accessVersion.increment()
        return local.afterPassword(active, credential)
    }

    private fun usable(token: String): Pair<PasswordResetToken, User> {
        val notFound = NotFoundException("set-password link", "provided")
        if (!ResetTokenCodec.looksValid(token)) throw notFound
        val reset = local.resetTokens.findByHash(ResetTokenCodec.hash(token))?.takeIf { it.isUsable(local.clock.now()) } ?: throw notFound
        val user = local.users.findById(reset.userId)?.takeIf { it.status != UserStatus.DEACTIVATED && it.authMode.allowsLocal } ?: throw notFound
        return reset to user
    }
}

class MyMfaService(private val local: LocalAccountSupport, private val gate: AuthorizationGate) : ManageMyMfa {

    @PublicOperation("users enrol their own authenticator")
    override fun startTotp(): TotpEnrolmentView {
        val (_, user) = selfOf(gate, local.users)
        if (!user.authMode.allowsLocal) throw InvalidStateException("TOTP protects local sign-in; this account signs in through the identity provider")
        if (local.mfa.findTotp(user.id)?.isActive == true) throw InvalidStateException("TOTP is already enabled; disable it before enrolling a new device")
        val secret = TotpVerifier.newSecret()
        local.mfa.saveTotp(MfaTotp(user.id, local.cipher.encrypt(secret), local.clock.now()))
        val base32 = Base32.encode(secret)
        return TotpEnrolmentView(base32, TotpVerifier.otpauthUri(local.options.local.totpIssuer, user.email, base32))
    }

    @PublicOperation("users confirm their own authenticator with a current code")
    override fun confirmTotp(code: String): List<String> {
        val (_, user) = selfOf(gate, local.users)
        val pending = local.mfa.findTotp(user.id) ?: throw InvalidStateException("start the TOTP enrolment first")
        if (pending.isActive) throw InvalidStateException("TOTP is already enabled")
        val now = local.clock.now()
        val step = TotpVerifier.verify(local.cipher.decrypt(pending.encryptedSecret), code, now, null)
            ?: throw ValidationException("the code does not match; check the time on your device and try again", "code")
        local.mfa.saveTotp(pending.copy(confirmedAt = now, lastUsedStep = step))
        val codes = local.newRecoveryCodes(user.id)
        local.audit.record(AuditEntry(IdentityAudit.MFA_ENABLE, IdentityAudit.ENTITY_USER, user.id.toString(), details = mapOf("method" to "TOTP")))
        return codes
    }

    @PublicOperation("users disable their own authenticator with their current password")
    override fun disableTotp(currentPassword: String) {
        val (_, user) = selfOf(gate, local.users)
        if (local.settings.current().mfaRequiredForLocal && user.authMode.allowsLocal) {
            throw InvalidStateException("the platform requires MFA for local accounts; ask an administrator to reset it if you lost your device")
        }
        local.verifyCurrentPassword(user.id, currentPassword)
        if (local.mfa.findTotp(user.id) == null) return
        local.mfa.deleteAll(user.id)
        local.audit.record(AuditEntry(IdentityAudit.MFA_DISABLE, IdentityAudit.ENTITY_USER, user.id.toString(), details = mapOf("method" to "TOTP")))
    }

    @PublicOperation("users replace their own recovery codes with their current password")
    override fun regenerateRecoveryCodes(currentPassword: String): List<String> {
        val (_, user) = selfOf(gate, local.users)
        local.verifyCurrentPassword(user.id, currentPassword)
        if (local.mfa.findTotp(user.id)?.isActive != true) throw InvalidStateException("enable TOTP first")
        val codes = local.newRecoveryCodes(user.id)
        local.audit.record(AuditEntry(IdentityAudit.RECOVERY_CODES_REGENERATE, IdentityAudit.ENTITY_USER, user.id.toString(), details = mapOf("count" to codes.size)))
        return codes
    }
}

/** Administrators: sign-in method, lockout and lost authenticators. All need `admin.users.manage`. */
class LocalAccountAdminService(
    private val local: LocalAccountSupport,
    private val assembler: UserDetailsAssembler,
    private val admins: PlatformAdmins,
    private val sessions: SessionTerminator,
    private val accessVersion: AccessVersion,
    private val gate: AuthorizationGate,
) : AdminLocalAccounts {

    override fun setAuthMode(userId: UserId, mode: AuthMode): UserDetails {
        val me = gate.require(IdentityPermissions.USERS_MANAGE)
        val user = load(userId)
        if (user.authMode == mode) return assembler.details(user)
        val hasPassword = local.credentials.find(userId)?.hasPassword == true
        val holders = admins.holders()
        if (userId in holders && canSignIn(user, user.authMode, hasPassword) && !canSignIn(user, mode, hasPassword) &&
            local.users.findByIds(holders - userId).none { canSignIn(it, it.authMode, local.credentials.find(it.id)?.hasPassword == true) }
        ) {
            throw InvalidStateException("the last platform administrator who can sign in would lose their sign-in method")
        }
        val saved = local.users.save(user.copy(authMode = mode))
        val removedLocal = user.authMode.allowsLocal && !mode.allowsLocal
        if (removedLocal) {
            // OIDC users never have a password
            local.credentials.delete(userId)
            local.mfa.deleteAll(userId)
            local.resetTokens.invalidateAllOf(userId, local.clock.now())
        }
        val removedMethod = removedLocal || (user.authMode.allowsOidc && !mode.allowsOidc)
        val terminated = if (removedMethod) sessions.terminateAll(userId) else 0
        local.audit.record(
            AuditEntry(
                IdentityAudit.AUTH_MODE_CHANGE, IdentityAudit.ENTITY_USER, userId.toString(),
                details = AuditableChanges.changed("authMode" to (user.authMode.name to mode.name)) + mapOf("terminatedSessions" to terminated),
            ),
        )
        val link = if (mode.allowsLocal && !hasPassword) local.issueLink(saved, me.actor) else null
        accessVersion.increment()
        return assembler.details(saved, link?.token)
    }

    override fun unlock(userId: UserId): UserDetails {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val user = load(userId)
        val credential = local.credentials.find(userId) ?: return assembler.details(user)
        val unlocked = credential.unlocked()
        if (unlocked != credential) {
            local.credentials.save(unlocked)
            local.audit.record(
                AuditEntry(
                    IdentityAudit.ACCOUNT_UNLOCK, IdentityAudit.ENTITY_USER, userId.toString(),
                    details = mapOf("lockedUntil" to credential.lockedUntil?.toString(), "failedAttempts" to credential.failedAttempts),
                ),
            )
        }
        return assembler.details(user)
    }

    override fun resetMfa(userId: UserId) {
        gate.require(IdentityPermissions.USERS_MANAGE)
        val user = load(userId)
        val had = local.mfa.findTotp(userId)
        local.mfa.deleteAll(userId)
        local.audit.record(
            AuditEntry(IdentityAudit.MFA_RESET, IdentityAudit.ENTITY_USER, userId.toString(), details = mapOf("email" to user.email, "hadAuthenticator" to (had?.isActive == true))),
        )
    }

    private fun canSignIn(user: User, mode: AuthMode, hasPassword: Boolean): Boolean =
        user.status == UserStatus.ACTIVE && ((mode.allowsOidc && local.options.local.oidcConfigured) || (mode.allowsLocal && hasPassword && local.localLoginAvailable()))

    private fun load(id: UserId): User = local.users.findById(id) ?: throw NotFoundException("user", id)
}

class MySecurityService(private val local: LocalAccountSupport, private val auditLog: AuditLogReader, private val gate: AuthorizationGate) : GetMySecurity {

    @PublicOperation("every signed-in user may read their own sign-in state")
    override fun mySecurity(): MySecurityView {
        val (_, user) = selfOf(gate, local.users)
        val credential = local.credentials.find(user.id)
        val totp = local.mfa.findTotp(user.id)
        val settings = local.settings.current()
        val logins = listOf(IdentityAudit.LOGIN_SUCCESS, IdentityAudit.LOGIN_FAILURE).flatMap { operation ->
            auditLog.search(AuditQuery(operation = operation, entityType = IdentityAudit.ENTITY_USER, entityId = user.id.toString(), size = RECENT)).content
        }.sortedByDescending { it.eventTime }.take(RECENT).map {
            LoginRecord(it.eventTime, it.details["method"]?.toString() ?: LoginMethod.OIDC.name, it.result, it.sourceIp)
        }
        return MySecurityView(
            authMode = user.authMode,
            hasPassword = credential?.hasPassword == true,
            passwordChangedAt = credential?.passwordChangedAt,
            mfaEnabled = totp?.isActive == true,
            mfaRequired = settings.mfaRequiredForLocal && user.authMode.allowsLocal,
            recoveryCodesRemaining = if (totp?.isActive == true) local.mfa.recoveryCodes(user.id).count { it.usedAt == null } else 0,
            lastLoginAt = user.lastLoginAt,
            lastLoginMethod = user.lastLoginMethod,
            recentLogins = logins,
            policy = settings.passwordPolicy,
        )
    }

    private companion object {
        const val RECENT = 10
    }
}

/**
 * `backoffice.auth.local.bootstrap-admin-email` + `-password`: the first platform administrator of an
 * installation without an identity provider. Only when no user exists; the password must be changed at the
 * first login.
 */
class BootstrapLocalAdminService(
    private val local: LocalAccountSupport,
    private val grants: GrantRepository,
    private val accessVersion: AccessVersion,
) : BootstrapLocalAdmin {

    @SystemOperation("startup: first administrator of an installation without an identity provider")
    override fun bootstrap(): User? {
        val email = local.options.local.bootstrapAdminEmail?.takeIf { it.isNotBlank() } ?: return null
        val password = local.options.local.bootstrapAdminPassword?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("backoffice.auth.local.bootstrap-admin-password must be set together with bootstrap-admin-email")
        val normalised = User.validEmail(email, "bootstrap-admin-email")
        if (!local.users.isEmpty()) return adoptExisting(normalised, password)
        local.validatePassword(password, normalised, "bootstrap-admin-password")
        val now = local.clock.now()
        val user = local.users.save(
            User.invite(normalised, "Administrator", emptySet(), now, Actor.SYSTEM, AuthMode.LOCAL).copy(status = UserStatus.ACTIVE),
        )
        local.credentials.save(LocalCredential.empty(user.id).withPassword(local.hasher.hash(password), now, mustChange = true))
        val set = grants.find(GrantLevel.USER, user.id.value)
        grants.save(set.copy(roles = set.roles + BuiltInRoles.PLATFORM_ADMIN))
        local.audit.record(
            AuditEntry(IdentityAudit.USER_PROVISION, IdentityAudit.ENTITY_USER, user.id.toString(), details = mapOf("email" to normalised, "authMode" to "LOCAL", "reason" to "bootstrap-local-admin"), actor = Actor.SYSTEM),
        )
        local.audit.record(
            AuditEntry(
                IdentityAudit.ROLE_ASSIGN, IdentityAudit.ENTITY_GRANT, user.id.toString(),
                details = mapOf("level" to "USER", "role" to BuiltInRoles.PLATFORM_ADMIN, "reason" to "bootstrap-local-admin"), actor = Actor.SYSTEM,
            ),
        )
        accessVersion.increment()
        return user
    }

    /**
     * An installation that drops its identity provider keeps its data: an active platform administrator with the bootstrap
     * e-mail and no password yet (an OIDC-only account) gets the bootstrap password, to be changed at the first sign-in.
     * A password once set is never touched, nor is anybody who is not a platform administrator.
     */
    private fun adoptExisting(email: String, password: String): User? {
        val user = local.users.findByEmail(email) ?: return null
        if (user.status != UserStatus.ACTIVE || local.credentials.find(user.id) != null) return null
        if (BuiltInRoles.PLATFORM_ADMIN !in grants.find(GrantLevel.USER, user.id.value).roles) return null
        local.validatePassword(password, email, "bootstrap-admin-password")
        val now = local.clock.now()
        val mode = if (user.authMode == AuthMode.OIDC) AuthMode.BOTH else user.authMode
        val saved = local.users.save(user.copy(authMode = mode))
        local.credentials.save(LocalCredential.empty(user.id).withPassword(local.hasher.hash(password), now, mustChange = true))
        local.audit.record(
            AuditEntry(
                IdentityAudit.AUTH_MODE_CHANGE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = mapOf("authMode" to (user.authMode.name to mode.name), "reason" to "bootstrap-local-admin"), actor = Actor.SYSTEM,
            ),
        )
        accessVersion.increment()
        return saved
    }
}
