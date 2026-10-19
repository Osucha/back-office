package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryLocalAccounts
import pl.mos.backoffice.identity.application.port.`in`.ChangePassword
import pl.mos.backoffice.identity.application.port.`in`.LoginOutcome
import pl.mos.backoffice.identity.application.port.`in`.ManageUsers
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.InvalidCredentialsException
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.ResetPurpose
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.ResetTokenCodec
import pl.mos.backoffice.identity.domain.service.TotpVerifier
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.time.Instant

class LocalAccountServicesTest {

    private val local = InMemoryLocalAccounts()
    private val db = local.db
    private val audit = RecordingAuditPort()
    private val support = local.support(audit)

    private fun gateFor(user: User, vararg permissions: String) = FakeAuthorizationGate(
        CurrentUser(PrincipalKind.USER, user.id, user.email, user.displayName, permissions.toSet(), emptyMap()),
    )

    private fun resets(gate: FakeAuthorizationGate) = PasswordResetService(support, db.sessions, db.accessVersion, gate)
    private fun admin(gate: FakeAuthorizationGate) =
        LocalAccountAdminService(support, UserDetailsAssembler(db.groups, db.grants, db.memberships, support), db.admins, db.sessions, db.accessVersion, gate)

    // ---------------------------------------------------------------- reset links

    @Test
    fun `issuing a reset link needs admin users manage, removes the password and ends the sessions`() {
        val user = local.localUser("ada@example.com")
        assertThatThrownBy { resets(FakeAuthorizationGate.permitting("admin.users.view")).issue(user.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val link = resets(gate).issue(user.id)
        assertThat(gate.checked).containsExactly("admin.users.manage")
        assertThat(link.purpose).isEqualTo(ResetPurpose.RESET)
        assertThat(link.expiresAt).isEqualTo(local.time.plus(Duration.ofHours(24)))
        assertThat(ResetTokenCodec.looksValid(link.token)).isTrue()
        assertThat(local.resetMap.keys).containsExactly(ResetTokenCodec.hash(link.token))
        assertThat(local.credentialMap[user.id]!!.hasPassword).isFalse()
        assertThat(db.terminatedSessions).containsExactly(user.id)
        assertThat(audit.operations()).containsExactly("PASSWORD_RESET_ISSUE", "SESSIONS_TERMINATE")
        assertThat(audit.entries.first().details.values.map { it.toString() }).noneMatch { it.contains(link.token) }
    }

    @Test
    fun `a link is single use, expires and the completion signs the user in`() {
        val user = local.localUser("ada@example.com")
        val service = resets(FakeAuthorizationGate.permitting("admin.users.manage"))
        val link = service.issue(user.id)

        assertThat(service.check(link.token).email).isEqualTo("ada@example.com")
        assertThatThrownBy { service.complete(link.token, "ada-is-my-password") }.isInstanceOf(ValidationException::class.java)
        val outcome = service.complete(link.token, "a-brand-new-secret")
        assertThat(outcome).isInstanceOf(LoginOutcome.SignedIn::class.java)
        assertThat(local.hasher.matches("a-brand-new-secret", local.credentialMap[user.id]!!.passwordHash!!)).isTrue()
        assertThat(audit.operations()).contains("PASSWORD_RESET_COMPLETE", "LOGIN_SUCCESS")
        assertThatThrownBy { service.check(link.token) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.complete(link.token, "another-new-secret") }.isInstanceOf(NotFoundException::class.java)

        val expiring = service.issue(user.id)
        local.advance(Duration.ofHours(24).plusSeconds(1))
        assertThatThrownBy { service.check(expiring.token) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.check("not-a-token") }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a new link replaces the older one and OIDC-only users cannot get one`() {
        val user = local.localUser("ada@example.com")
        val service = resets(FakeAuthorizationGate.permitting("admin.users.manage"))
        val first = service.issue(user.id)
        val second = service.issue(user.id)
        assertThatThrownBy { service.check(first.token) }.isInstanceOf(NotFoundException::class.java)
        assertThat(service.check(second.token).purpose).isEqualTo(ResetPurpose.RESET)

        val oidc = db.user("oidc@example.com")
        assertThatThrownBy { service.issue(oidc.id) }.isInstanceOf(InvalidStateException::class.java)
    }

    // ---------------------------------------------------------------- invite and auth mode

    @Test
    fun `inviting a local user returns a SET_INITIAL token, completing it activates the user`() {
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val users = UserAdminService(
            db.users, db.groups, db.roles, db.grants, db.memberships, db.tokens, db.sessions, db.projectKeys, db.admins,
            audit, db.accessVersion, local.clock, gate, support,
        )
        val details = users.invite(ManageUsers.InviteUser("fresh@example.com", authMode = AuthMode.LOCAL))
        assertThat(details.user.authMode).isEqualTo(AuthMode.LOCAL)
        assertThat(details.user.status).isEqualTo(UserStatus.INVITED)
        val token = details.setPasswordToken!!
        assertThat(audit.operations()).containsExactly("USER_INVITE", "PASSWORD_RESET_ISSUE")

        val service = resets(gate)
        assertThat(service.check(token).purpose).isEqualTo(ResetPurpose.SET_INITIAL)
        service.complete(token, "a-brand-new-secret")
        assertThat(db.userMap[details.user.id]!!.status).isEqualTo(UserStatus.ACTIVE)

        // without an explicit mode: LOCAL when no identity provider is configured
        assertThat(users.invite(ManageUsers.InviteUser("other@example.com")).user.authMode).isEqualTo(AuthMode.LOCAL)
    }

    @Test
    fun `switching to LOCAL without a password issues a link, switching to OIDC removes the credential`() {
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val user = db.user("oidc@example.com")
        val details = admin(gate).setAuthMode(user.id, AuthMode.BOTH)
        assertThat(gate.checked).containsExactly("admin.users.manage")
        assertThat(details.user.authMode).isEqualTo(AuthMode.BOTH)
        assertThat(details.setPasswordToken).isNotNull()
        assertThat(audit.operations()).containsExactly("AUTH_MODE_CHANGE", "PASSWORD_RESET_ISSUE")
        assertThat(audit.entries.first().details["authMode"]).isEqualTo(mapOf("old" to "OIDC", "new" to "BOTH"))

        val localUser = local.localUser("ada@example.com", mode = AuthMode.BOTH)
        val back = admin(gate).setAuthMode(localUser.id, AuthMode.OIDC)
        assertThat(back.local.hasPassword).isFalse()
        assertThat(local.credentialMap).doesNotContainKey(localUser.id)
        assertThat(back.setPasswordToken).isNull()
    }

    @Test
    fun `the last platform admin who can sign in cannot lose the sign-in method`() {
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val boss = local.localUser("boss@example.com")
        db.assign(GrantLevel.USER, boss.id.value, BuiltInRoles.PLATFORM_ADMIN)
        // no identity provider configured: OIDC would lock everybody out
        assertThatThrownBy { admin(gate).setAuthMode(boss.id, AuthMode.OIDC) }.isInstanceOf(InvalidStateException::class.java)

        val second = local.localUser("second@example.com")
        db.assign(GrantLevel.USER, second.id.value, BuiltInRoles.PLATFORM_ADMIN)
        assertThat(admin(gate).setAuthMode(boss.id, AuthMode.OIDC).user.authMode).isEqualTo(AuthMode.OIDC)
    }

    @Test
    fun `unlock clears the lockout and is audited, reset MFA removes the authenticator`() {
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val user = local.localUser("ada@example.com")
        local.credentialMap[user.id] = local.credentialMap[user.id]!!.copy(lockedUntil = local.time.plusSeconds(600), lockoutCount = 2, failedAttempts = 1)
        assertThat(support.view(user).lockedUntil).isNotNull()
        val details = admin(gate).unlock(user.id)
        assertThat(details.local.lockedUntil).isNull()
        assertThat(local.credentialMap[user.id]!!.lockoutCount).isZero()
        assertThat(audit.operations()).containsExactly("ACCOUNT_UNLOCK")

        MyMfaService(support, gateFor(user)).startTotp()
        admin(gate).resetMfa(user.id)
        assertThat(local.totpMap).isEmpty()
        assertThat(audit.operations().last()).isEqualTo("MFA_RESET")
        assertThatThrownBy { admin(FakeAuthorizationGate.permitting("admin.users.view")).resetMfa(user.id) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    // ---------------------------------------------------------------- change password

    @Test
    fun `changing the password needs the current one, applies the policy and ends the other sessions`() {
        val user = local.localUser("ada@example.com")
        val service = ChangePasswordService(support, db.sessions, gateFor(user))
        assertThatThrownBy { service.change(ChangePassword.Command("wrong-password-123", "a-brand-new-secret")) }.isInstanceOf(InvalidCredentialsException::class.java)
        assertThatThrownBy { service.change(ChangePassword.Command(null, "a-brand-new-secret")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.change(ChangePassword.Command("correct-horse-battery", "password1234")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.change(ChangePassword.Command("correct-horse-battery", "correct-horse-battery")) }.isInstanceOf(ValidationException::class.java)

        val result = service.change(ChangePassword.Command("correct-horse-battery", "a-brand-new-secret", keepSessionId = "mine"))
        assertThat(result.mfaEnrolmentRequired).isFalse()
        assertThat(db.terminatedSessions).containsExactly(user.id)
        assertThat(audit.operations()).containsExactly("PASSWORD_CHANGE")
    }

    @Test
    fun `the forced change after the first login needs no current password`() {
        val user = local.localUser("ada@example.com")
        local.credentialMap[user.id] = local.credentialMap[user.id]!!.copy(mustChangePassword = true)
        db.storedSettings = PlatformSettings(mfaRequiredForLocal = true)
        val service = ChangePasswordService(support, db.sessions, gateFor(user))
        val result = service.change(ChangePassword.Command(null, "a-brand-new-secret", forcedChange = true))
        assertThat(result.mfaEnrolmentRequired).isTrue()
        assertThat(local.credentialMap[user.id]!!.mustChangePassword).isFalse()
        // a second "forced" change is not forced any more
        assertThatThrownBy { service.change(ChangePassword.Command(null, "another-new-secret", forcedChange = true)) }.isInstanceOf(ValidationException::class.java)
    }

    // ---------------------------------------------------------------- MFA self-service

    @Test
    fun `TOTP enrolment, confirmation with a generated code, recovery codes and disabling`() {
        val user = local.localUser("ada@example.com")
        val service = MyMfaService(support, gateFor(user))
        val enrolment = service.startTotp()
        assertThat(enrolment.otpauthUri).startsWith("otpauth://totp/Back%20Office%3Aada%40example.com?secret=${enrolment.secretBase32}")
        assertThat(local.totpMap[user.id]!!.isActive).isFalse()
        assertThatThrownBy { service.confirmTotp("000000") }.isInstanceOf(ValidationException::class.java)

        val secret = pl.mos.backoffice.identity.domain.service.Base32.decode(enrolment.secretBase32)
        val codes = service.confirmTotp(TotpVerifier.code(secret, TotpVerifier.stepOf(local.time)))
        assertThat(codes).hasSize(10)
        assertThat(local.recoveryMap[user.id]!!.map { it.hash }).containsExactlyElementsOf(codes.map { "hmac:$it" })
        assertThat(audit.operations()).containsExactly("MFA_ENABLE")
        assertThatThrownBy { service.startTotp() }.isInstanceOf(InvalidStateException::class.java)

        assertThatThrownBy { service.regenerateRecoveryCodes("wrong-password-123") }.isInstanceOf(InvalidCredentialsException::class.java)
        assertThat(service.regenerateRecoveryCodes("correct-horse-battery")).hasSize(10).doesNotContainAnyElementsOf(codes)

        db.storedSettings = PlatformSettings(mfaRequiredForLocal = true)
        assertThatThrownBy { service.disableTotp("correct-horse-battery") }.isInstanceOf(InvalidStateException::class.java)
        db.storedSettings = PlatformSettings()
        service.disableTotp("correct-horse-battery")
        assertThat(local.totpMap).isEmpty()
        assertThat(audit.operations()).containsExactly("MFA_ENABLE", "RECOVERY_CODES_REGENERATE", "MFA_DISABLE")
    }

    @Test
    fun `OIDC-only users and tokens cannot manage local MFA`() {
        val user = db.user("oidc@example.com")
        assertThatThrownBy { MyMfaService(support, gateFor(user)).startTotp() }.isInstanceOf(InvalidStateException::class.java)
        val token = FakeAuthorizationGate(gateFor(user).user.copy(kind = PrincipalKind.PERSONAL_TOKEN))
        assertThatThrownBy { MyMfaService(support, token).startTotp() }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `my security reports the local state`() {
        val user = local.localUser("ada@example.com")
        val view = MySecurityService(support, local.auditReader, gateFor(user)).mySecurity()
        assertThat(view.authMode).isEqualTo(AuthMode.LOCAL)
        assertThat(view.hasPassword).isTrue()
        assertThat(view.mfaEnabled).isFalse()
        assertThat(view.policy.minLength).isEqualTo(12)
    }

    // ---------------------------------------------------------------- bootstrap

    @Test
    fun `the bootstrap admin is created once, only when no user exists, with a forced password change`() {
        val options = local.options(bootstrapEmail = "Owner@Example.com", bootstrapPassword = "ChangeMe-Local-123")
        val service = BootstrapLocalAdminService(local.support(audit, options), db.grants, db.accessVersion)
        val created = service.bootstrap()!!
        assertThat(created.email).isEqualTo("owner@example.com")
        assertThat(created.status).isEqualTo(UserStatus.ACTIVE)
        assertThat(created.authMode).isEqualTo(AuthMode.LOCAL)
        assertThat(local.credentialMap[created.id]!!.mustChangePassword).isTrue()
        assertThat(db.grants.find(GrantLevel.USER, created.id.value).roles).containsExactly(BuiltInRoles.PLATFORM_ADMIN)
        assertThat(audit.operations()).containsExactly("USER_PROVISION", "ROLE_ASSIGN")
        assertThat(service.bootstrap()).isNull()

        val login = LocalLoginService(local.support(audit, options)).login("owner@example.com", "ChangeMe-Local-123")
        assertThat((login as LoginOutcome.SignedIn).restriction).isEqualTo(SessionRestriction.PASSWORD_CHANGE)
    }

    @Test
    fun `the bootstrap gives an OIDC-only administrator a password once, never anybody else`() {
        val options = local.options(bootstrapEmail = "owner@example.com", bootstrapPassword = "ChangeMe-Local-123")
        val service = BootstrapLocalAdminService(local.support(audit, options), db.grants, db.accessVersion)
        val other = local.support(audit, options).users.save(User.invite("other@example.com", "Other", emptySet(), Instant.now(), Actor.SYSTEM).copy(status = UserStatus.ACTIVE))
        val owner = local.support(audit, options).users.save(User.invite("owner@example.com", "Owner", emptySet(), Instant.now(), Actor.SYSTEM).copy(status = UserStatus.ACTIVE))
        // not an administrator yet: nothing happens
        assertThat(service.bootstrap()).isNull()

        val set = db.grants.find(GrantLevel.USER, owner.id.value)
        db.grants.save(set.copy(roles = set.roles + BuiltInRoles.PLATFORM_ADMIN))
        val adopted = service.bootstrap()!!
        assertThat(adopted.authMode).isEqualTo(AuthMode.BOTH)
        assertThat(local.credentialMap[owner.id]!!.mustChangePassword).isTrue()
        assertThat(local.credentialMap[other.id]).isNull()
        // a password once set is never overwritten
        assertThat(service.bootstrap()).isNull()
    }

    @Test
    fun `settings refuse switching off local login when it is the only method`() {
        val gate = FakeAuthorizationGate.permitting("admin.settings.manage")
        val service = PlatformSettingsService(db.settingsRepository, db.settingsProvider(local.options()), audit, db.accessVersion, gate, local.options())
        assertThatThrownBy { service.update(PlatformSettings(localLoginEnabled = false)) }.isInstanceOf(InvalidStateException::class.java)
        val withOidc = PlatformSettingsService(db.settingsRepository, db.settingsProvider(local.options(oidc = true)), audit, db.accessVersion, gate, local.options(oidc = true))
        assertThat(withOidc.update(PlatformSettings(localLoginEnabled = false, mfaRequiredForLocal = true)).localLoginEnabled).isFalse()
        assertThat(audit.entries.single().details.keys).containsExactlyInAnyOrder("localLoginEnabled", "mfaRequiredForLocal")
        assertThat(withOidc.loginOptions().localLoginEnabled).isFalse()
    }
}
