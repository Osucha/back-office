package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryLocalAccounts
import pl.mos.backoffice.identity.application.port.`in`.LoginOutcome
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.MfaTotp
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.TotpVerifier
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration

class LocalLoginServiceTest {

    private val local = InMemoryLocalAccounts()
    private val audit = RecordingAuditPort()
    private val service = LocalLoginService(local.support(audit))

    private fun failureReasons() = audit.entries.filter { it.operation == "LOGIN_FAILURE" }.map { it.details["reason"] }

    @Test
    fun `a correct password signs the user in, records LOGIN_SUCCESS and the login method`() {
        val user = local.localUser("ada@example.com")
        val outcome = service.login(" Ada@Example.com ", "correct-horse-battery")
        assertThat(outcome).isInstanceOf(LoginOutcome.SignedIn::class.java)
        outcome as LoginOutcome.SignedIn
        assertThat(outcome.restriction).isNull()
        assertThat(local.db.userMap[user.id]!!.lastLoginMethod).isEqualTo(LoginMethod.LOCAL)
        assertThat(audit.operations()).containsExactly("LOGIN_SUCCESS")
        assertThat(audit.entries.single().details).containsEntry("method", "LOCAL").containsEntry("mfa", false)
    }

    @Test
    fun `unknown user and wrong password give the same outcome and both run exactly one hash`() {
        local.localUser("ada@example.com")
        assertThat(service.login("nobody@example.com", "whatever-password")).isEqualTo(LoginOutcome.Failed)
        assertThat(local.hashChecks).hasSize(1)
        assertThat(local.hashChecks.single().second).isEqualTo(local.hasher.dummyHash)
        assertThat(service.login("ada@example.com", "wrong-password-123")).isEqualTo(LoginOutcome.Failed)
        assertThat(local.hashChecks).hasSize(2)
        assertThat(failureReasons()).containsExactly("unknown-user", "bad-password")
        assertThat(audit.entries.map { it.details["method"] }).containsOnly("LOCAL")
    }

    @Test
    fun `OIDC-only, deactivated and disabled local login fail uniformly with their audit reasons`() {
        local.localUser("oidc@example.com", mode = AuthMode.OIDC)
        local.localUser("gone@example.com", status = UserStatus.DEACTIVATED)
        assertThat(service.login("oidc@example.com", "correct-horse-battery")).isEqualTo(LoginOutcome.Failed)
        assertThat(service.login("gone@example.com", "correct-horse-battery")).isEqualTo(LoginOutcome.Failed)
        local.db.storedSettings = PlatformSettings(localLoginEnabled = false)
        local.localUser("ada@example.com")
        assertThat(service.login("ada@example.com", "correct-horse-battery")).isEqualTo(LoginOutcome.Failed)
        assertThat(failureReasons()).containsExactly("method-not-allowed", "inactive", "method-not-allowed")
        assertThat(local.hashChecks).hasSize(3)
    }

    @Test
    fun `five wrong passwords lock the account, the correct password then fails too, and the next lockout doubles`() {
        val user = local.localUser("ada@example.com")
        repeat(5) { service.login("ada@example.com", "wrong-password-123") }
        assertThat(audit.operations()).contains("ACCOUNT_LOCKED")
        val lockedUntil = local.credentialMap[user.id]!!.lockedUntil
        assertThat(lockedUntil).isEqualTo(local.time.plus(Duration.ofMinutes(15)))
        assertThat(service.login("ada@example.com", "correct-horse-battery")).isEqualTo(LoginOutcome.Failed)
        assertThat(failureReasons().last()).isEqualTo("locked")

        local.advance(Duration.ofMinutes(16))
        repeat(5) { service.login("ada@example.com", "wrong-password-123") }
        assertThat(local.credentialMap[user.id]!!.lockedUntil).isEqualTo(local.time.plus(Duration.ofMinutes(30)))

        local.advance(Duration.ofMinutes(31))
        assertThat(service.login("ada@example.com", "correct-horse-battery")).isInstanceOf(LoginOutcome.SignedIn::class.java)
        assertThat(local.credentialMap[user.id]!!.lockoutCount).isZero()
    }

    @Test
    fun `outdated hashes are upgraded after a successful login`() {
        val user = local.localUser("ada@example.com")
        local.credentialMap[user.id] = local.credentialMap[user.id]!!.copy(passwordHash = "{v1}correct-horse-battery")
        service.login("ada@example.com", "correct-horse-battery")
        assertThat(local.credentialMap[user.id]!!.passwordHash).isEqualTo("{v2}correct-horse-battery")
    }

    @Test
    fun `a forced password change and a missing required MFA restrict the session`() {
        val user = local.localUser("ada@example.com")
        local.credentialMap[user.id] = local.credentialMap[user.id]!!.copy(mustChangePassword = true)
        assertThat((service.login("ada@example.com", "correct-horse-battery") as LoginOutcome.SignedIn).restriction).isEqualTo(SessionRestriction.PASSWORD_CHANGE)

        local.credentialMap[user.id] = local.credentialMap[user.id]!!.copy(mustChangePassword = false)
        local.db.storedSettings = PlatformSettings(mfaRequiredForLocal = true)
        assertThat((service.login("ada@example.com", "correct-horse-battery") as LoginOutcome.SignedIn).restriction).isEqualTo(SessionRestriction.MFA_ENROLMENT)
    }

    @Test
    fun `with TOTP the password step returns a single-use mfa token and the code step signs in, replays are rejected`() {
        val user = local.localUser("ada@example.com")
        val secret = TotpVerifier.newSecret()
        local.totpMap[user.id] = MfaTotp(user.id, local.cipher.encrypt(secret), local.time, confirmedAt = local.time)

        val first = service.login("ada@example.com", "correct-horse-battery")
        assertThat(first).isInstanceOf(LoginOutcome.MfaRequired::class.java)
        assertThat(audit.operations()).isEmpty()
        val token = (first as LoginOutcome.MfaRequired).mfaToken
        val code = TotpVerifier.code(secret, TotpVerifier.stepOf(local.time))

        val signedIn = service.complete(token, code, null)
        assertThat(signedIn).isInstanceOf(LoginOutcome.SignedIn::class.java)
        assertThat(audit.entries.last().details).containsEntry("mfa", true)
        // the token is consumed
        assertThat(service.complete(token, code, null)).isEqualTo(LoginOutcome.Failed)
        // the same code with a fresh token is a replay of the last used step
        val again = service.login("ada@example.com", "correct-horse-battery") as LoginOutcome.MfaRequired
        assertThat(service.complete(again.mfaToken, code, null)).isEqualTo(LoginOutcome.Failed)
        assertThat(failureReasons()).containsExactly("bad-mfa", "bad-mfa")
        assertThat(local.credentialMap[user.id]!!.failedAttempts).isEqualTo(1)
    }

    @Test
    fun `a recovery code works exactly once and is audited`() {
        val user = local.localUser("ada@example.com")
        local.totpMap[user.id] = MfaTotp(user.id, local.cipher.encrypt(TotpVerifier.newSecret()), local.time, confirmedAt = local.time)
        val support = local.support(audit)
        val codes = support.newRecoveryCodes(user.id)

        val step = service.login("ada@example.com", "correct-horse-battery") as LoginOutcome.MfaRequired
        assertThat(service.complete(step.mfaToken, null, codes[3].uppercase())).isInstanceOf(LoginOutcome.SignedIn::class.java)
        assertThat(audit.operations()).containsExactly("RECOVERY_CODE_USED", "LOGIN_SUCCESS")
        assertThat(audit.entries.first().details).containsEntry("remaining", 9)

        val second = service.login("ada@example.com", "correct-horse-battery") as LoginOutcome.MfaRequired
        assertThat(service.complete(second.mfaToken, null, codes[3])).isEqualTo(LoginOutcome.Failed)
    }

    @Test
    fun `an unknown mfa token fails without touching any account`() {
        assertThat(service.complete("forged", "123456", null)).isEqualTo(LoginOutcome.Failed)
        assertThat(failureReasons()).containsExactly("bad-mfa")
    }
}
