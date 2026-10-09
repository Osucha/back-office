package pl.mos.backoffice.identity.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.LockoutPolicy
import pl.mos.backoffice.identity.domain.model.LockoutSettings
import pl.mos.backoffice.identity.domain.model.PasswordPolicy
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant

class TotpVerifierTest {

    /** RFC 6238 Appendix B, SHA-1 seed; the 8-digit reference values truncated to our 6 digits. */
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @ParameterizedTest
    @CsvSource(
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130",
    )
    fun `matches the RFC 6238 test vectors`(epochSeconds: Long, eightDigits: String) {
        val at = Instant.ofEpochSecond(epochSeconds)
        val expected = eightDigits.takeLast(6)
        assertThat(TotpVerifier.code(rfcSecret, TotpVerifier.stepOf(at))).isEqualTo(expected)
        assertThat(TotpVerifier.verify(rfcSecret, expected, at, null)).isEqualTo(TotpVerifier.stepOf(at))
    }

    @Test
    fun `accepts one step of drift either side and nothing further`() {
        val at = Instant.ofEpochSecond(1_111_111_111)
        val step = TotpVerifier.stepOf(at)
        assertThat(TotpVerifier.verify(rfcSecret, TotpVerifier.code(rfcSecret, step - 1), at, null)).isEqualTo(step - 1)
        assertThat(TotpVerifier.verify(rfcSecret, TotpVerifier.code(rfcSecret, step + 1), at, null)).isEqualTo(step + 1)
        assertThat(TotpVerifier.verify(rfcSecret, TotpVerifier.code(rfcSecret, step - 2), at, null)).isNull()
        assertThat(TotpVerifier.verify(rfcSecret, TotpVerifier.code(rfcSecret, step + 2), at, null)).isNull()
    }

    @Test
    fun `rejects a replay of the last used step or an older one`() {
        val at = Instant.ofEpochSecond(1_234_567_890)
        val step = TotpVerifier.stepOf(at)
        val code = TotpVerifier.code(rfcSecret, step)
        assertThat(TotpVerifier.verify(rfcSecret, code, at, step)).isNull()
        assertThat(TotpVerifier.verify(rfcSecret, TotpVerifier.code(rfcSecret, step - 1), at, step)).isNull()
        assertThat(TotpVerifier.verify(rfcSecret, code, at, step - 1)).isEqualTo(step)
    }

    @Test
    fun `rejects malformed codes`() {
        val at = Instant.ofEpochSecond(59)
        listOf("", "12345", "1234567", "abcdef", "28708 2").forEach { assertThat(TotpVerifier.verify(rfcSecret, it, at, null)).isNull() }
    }

    @Test
    fun `builds an otpauth URI for authenticator apps`() {
        val uri = TotpVerifier.otpauthUri("Back Office", "ada@example.com", "JBSWY3DPEHPK3PXP")
        assertThat(uri).isEqualTo("otpauth://totp/Back%20Office%3Aada%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Back%20Office&algorithm=SHA1&digits=6&period=30")
    }
}

class Base32Test {
    @ParameterizedTest
    @CsvSource("f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI")
    fun `encodes and decodes the RFC 4648 vectors without padding`(plain: String, encoded: String) {
        assertThat(Base32.encode(plain.toByteArray())).isEqualTo(encoded)
        assertThat(String(Base32.decode(encoded.lowercase()))).isEqualTo(plain)
    }

    @Test
    fun `round-trips a TOTP secret and rejects foreign characters`() {
        val secret = TotpVerifier.newSecret()
        assertThat(secret).hasSize(20)
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret)
        assertThatThrownBy { Base32.decode("ABC1") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

class RecoveryCodeGeneratorTest {
    @Test
    fun `generates ten distinct codes of the form xxxx-xxxx-xx without look-alike characters`() {
        val codes = RecoveryCodeGenerator.generate()
        assertThat(codes).hasSize(10).doesNotHaveDuplicates()
        codes.forEach {
            assertThat(it).matches("^[23456789abcdefghjkmnpqrstuvwxyz]{4}-[23456789abcdefghjkmnpqrstuvwxyz]{4}-[23456789abcdefghjkmnpqrstuvwxyz]{2}$")
            assertThat(it).doesNotContain("0", "1", "o", "l", "i")
        }
    }

    @Test
    fun `normalises user input`() {
        assertThat(RecoveryCodeGenerator.normalise(" ABCD-EFGH-JK ")).isEqualTo("abcd-efgh-jk")
        assertThat(RecoveryCodeGenerator.normalise("abcdefghjk")).isEqualTo("abcd-efgh-jk")
        assertThat(RecoveryCodeGenerator.normalise("abcd-efgh-j")).isNull()
        assertThat(RecoveryCodeGenerator.normalise("abcd-efgh-j0")).isNull()
    }
}

class PasswordPolicyTest {
    private val common = setOf("password1234", "letmein12345")
    private val policy = PasswordPolicy()
    private fun problems(password: String, email: String? = "jan.kowalski@example.com") = policy.violations(password, email) { it.lowercase() in common }

    @Test
    fun `accepts 12 to 128 characters without composition rules`() {
        assertThat(problems("a".repeat(11))).containsExactly("must have at least 12 characters")
        assertThat(problems("correct horse battery")).isEmpty()
        assertThat(problems("x".repeat(128))).isEmpty()
        assertThat(problems("x".repeat(129))).containsExactly("must have at most 128 characters")
    }

    @Test
    fun `rejects common passwords case-insensitively`() {
        assertThat(problems("Password1234")).containsExactly("is too common")
        assertThat(PasswordPolicy(rejectCommon = false).violations("Password1234", null) { true }).isEmpty()
    }

    @Test
    fun `rejects passwords containing the e-mail local part when it has at least 3 characters`() {
        assertThat(problems("my-JAN.KOWALSKI-secret")).containsExactly("must not contain your e-mail name")
        assertThat(problems("ab-and-a-long-secret", "ab@example.com")).isEmpty()
        assertThat(problems("abc-and-a-long-secret", "abc@example.com")).containsExactly("must not contain your e-mail name")
        assertThat(PasswordPolicy(rejectContainingEmail = false).violations("abc-and-a-long-secret", "abc@example.com") { false }).isEmpty()
    }

    @Test
    fun `validate throws a ValidationException on the given field and the policy validates its own bounds`() {
        assertThatThrownBy { policy.validate("short", null, { false }) }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ assertThat((it as ValidationException).errors.single().field).isEqualTo("newPassword") })
        assertThatThrownBy { PasswordPolicy(minLength = 4) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { PasswordPolicy(maxLength = 200) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { LockoutSettings(maxFailures = 0) }.isInstanceOf(ValidationException::class.java)
    }
}

class LockoutTest {
    private val now = Instant.parse("2026-09-27T10:00:00Z")
    private val policy = LockoutPolicy()

    @Test
    fun `five consecutive failures lock for 15 minutes and every further lockout doubles up to 24 hours`() {
        assertThat((0..8).map { policy.durationOf(it).toMinutes() }).containsExactly(15, 30, 60, 120, 240, 480, 960, 1440, 1440)

        var credential = LocalCredential(UserId.new(), passwordHash = "h")
        repeat(4) {
            val (next, locked) = credential.failed(now, policy)
            assertThat(locked).isFalse()
            credential = next
        }
        val (locked, justLocked) = credential.failed(now, policy)
        assertThat(justLocked).isTrue()
        assertThat(locked.lockedUntil).isEqualTo(now.plus(Duration.ofMinutes(15)))
        assertThat(locked.isLocked(now.plusSeconds(899))).isTrue()
        assertThat(locked.isLocked(now.plusSeconds(900))).isFalse()
        assertThat(locked.failedAttempts).isZero()

        var again = locked
        repeat(5) { again = again.failed(now, policy).first }
        assertThat(again.lockedUntil).isEqualTo(now.plus(Duration.ofMinutes(30)))
        assertThat(again.succeeded().lockoutCount).isZero()
    }
}
