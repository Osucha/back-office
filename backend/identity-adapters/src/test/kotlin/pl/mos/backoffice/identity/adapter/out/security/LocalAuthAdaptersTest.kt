package pl.mos.backoffice.identity.adapter.out.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.adapter.`in`.web.RestrictedSession
import pl.mos.backoffice.identity.adapter.`in`.web.SlidingWindowThrottle
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant
import java.util.Base64

class LocalAuthAdaptersTest {

    private val keys = LocalKeyMaterial(ByteArray(32) { it.toByte() })
    private var now = Instant.parse("2026-09-27T10:00:00Z")
    private val clock = Clock { now }

    @Test
    fun `the secret key is required while local login is enabled and must be 32 bytes`() {
        assertThatThrownBy { LocalKeyMaterial.from("", required = true) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("backoffice.auth.local.secret-key")
        assertThatThrownBy { LocalKeyMaterial.from(Base64.getEncoder().encodeToString(ByteArray(16)), required = true) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(LocalKeyMaterial.from("", required = false)).isNotNull()
        assertThat(LocalKeyMaterial.from(Base64.getEncoder().encodeToString(ByteArray(32)), required = true)).isNotNull()
    }

    @Test
    fun `argon2id hashes are prefixed, verify, and legacy formats ask for a rehash`() {
        val hasher = SpringPasswordHasher(SpringPasswordHasher.encoder())
        val hash = hasher.hash("correct-horse-battery")
        assertThat(hash).startsWith("{argon2}\$argon2id\$")
        assertThat(hasher.matches("correct-horse-battery", hash)).isTrue()
        assertThat(hasher.matches("wrong", hash)).isFalse()
        assertThat(hasher.needsRehash(hash)).isFalse()
        assertThat(hasher.matches("x", hasher.dummyHash)).isFalse()
        assertThat(hasher.matches("x", "garbage")).isFalse()
    }

    @Test
    fun `TOTP secrets round-trip through AES-GCM with a random IV and tampering is detected`() {
        val cipher = AesGcmSecretCipher(keys)
        val secret = "12345678901234567890".toByteArray()
        val a = cipher.encrypt(secret)
        assertThat(a).startsWith("v1:").isNotEqualTo(cipher.encrypt(secret))
        assertThat(cipher.decrypt(a)).isEqualTo(secret)
        val tampered = "v1:" + Base64.getEncoder().encodeToString(Base64.getDecoder().decode(a.removePrefix("v1:")).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertThatThrownBy { cipher.decrypt(tampered) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { AesGcmSecretCipher(LocalKeyMaterial(ByteArray(32))).decrypt(a) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `mfa tokens are bound to the user, single use, expire and cannot be forged`() {
        val challenges = SignedMfaChallenges(keys, clock, Duration.ofMinutes(5))
        val user = UserId.new()
        val token = challenges.issue(user)
        assertThat(challenges.consume(token)).isEqualTo(user)
        assertThat(challenges.consume(token)).isNull()

        val forged = token.substringBefore('.') + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        assertThat(challenges.consume(forged)).isNull()
        assertThat(SignedMfaChallenges(LocalKeyMaterial(ByteArray(32)), clock).consume(challenges.issue(user))).isNull()

        val expiring = challenges.issue(user)
        now = now.plus(Duration.ofMinutes(5))
        assertThat(challenges.consume(expiring)).isNull()
        assertThat(challenges.consume("not.a-token")).isNull()
    }

    @Test
    fun `recovery-code hashes are keyed`() {
        assertThat(HmacCodeHasher(keys).hash("abcd-efgh-jk")).hasSize(64).isEqualTo(HmacCodeHasher(keys).hash("abcd-efgh-jk"))
            .isNotEqualTo(HmacCodeHasher(LocalKeyMaterial(ByteArray(32))).hash("abcd-efgh-jk"))
    }

    @Test
    fun `the bundled list holds the 10k most common passwords`() {
        val common = ResourceCommonPasswords()
        assertThat(common.size).isBetween(9_900, 10_100)
        assertThat(common.contains("PASSWORD")).isTrue()
        assertThat(common.contains("unbelievable")).isTrue()
        assertThat(common.contains("correct horse battery staple")).isFalse()
    }

    @Test
    fun `the sliding window allows max attempts per window and reports the wait`() {
        val throttle = SlidingWindowThrottle(3, Duration.ofMinutes(5), clock)
        repeat(3) { assertThat(throttle.tryAcquire("a")).isNull() }
        now = now.plusSeconds(60)
        assertThat(throttle.tryAcquire("a")).isEqualTo(Duration.ofMinutes(4))
        assertThat(throttle.tryAcquire("b")).isNull()
        now = now.plus(Duration.ofMinutes(4))
        assertThat(throttle.tryAcquire("a")).isNull()
    }

    @Test
    fun `restricted sessions reach only what resolves the restriction`() {
        assertThat(RestrictedSession.allows(SessionRestriction.PASSWORD_CHANGE, "/api/v1/auth/password/change")).isTrue()
        assertThat(RestrictedSession.allows(SessionRestriction.PASSWORD_CHANGE, "/api/v1/me")).isTrue()
        assertThat(RestrictedSession.allows(SessionRestriction.PASSWORD_CHANGE, "/api/v1/me/mfa/totp")).isFalse()
        assertThat(RestrictedSession.allows(SessionRestriction.MFA_ENROLMENT, "/api/v1/me/mfa/totp")).isTrue()
        assertThat(RestrictedSession.allows(SessionRestriction.MFA_ENROLMENT, "/api/v1/projects")).isFalse()
        assertThat(RestrictedSession.allows(SessionRestriction.MFA_ENROLMENT, "/projects")).isTrue()
    }
}
