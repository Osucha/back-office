package pl.mos.backoffice.projects.adapter.out.secrets

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

/** ADR 0030: AES-256-GCM envelope encryption of connection secrets. */
class AesGcmSecretCipherTest {

    private fun key(seed: Char) = Base64.getEncoder().encodeToString(ByteArray(32) { seed.code.toByte() })

    private val k1 = key('a')
    private val k2 = key('b')
    private val token = "ATATT3xFfGF0-classic-api-token"

    @Test
    fun `seal and open round-trip without the plaintext in the sealed value`() {
        val cipher = AesGcmSecretCipher(SecretsProperties(key = k1, keyId = "k1"))
        val sealed = cipher.seal(token, "connection:1")

        assertThat(sealed.keyId).isEqualTo("k1")
        assertThat(sealed.ciphertext + sealed.wrappedKey).doesNotContain(token).doesNotContain(Base64.getEncoder().encodeToString(token.toByteArray()))
        assertThat(sealed.toString()).doesNotContain(sealed.ciphertext)
        assertThat(cipher.open(sealed, "connection:1")).isEqualTo(token)
        // a fresh data key and nonce every time
        assertThat(cipher.seal(token, "connection:1")).isNotEqualTo(sealed)
    }

    @Test
    fun `the wrong key, another connection or tampering do not open`() {
        val sealed = AesGcmSecretCipher(SecretsProperties(key = k1, keyId = "k1")).seal(token, "connection:1")

        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = k2, keyId = "k1")).open(sealed, "connection:1") }
            .isInstanceOf(SecretUnavailableException::class.java).hasMessageNotContaining(token)
        val cipher = AesGcmSecretCipher(SecretsProperties(key = k1, keyId = "k1"))
        assertThatThrownBy { cipher.open(sealed, "connection:2") }.isInstanceOf(SecretUnavailableException::class.java)
        val bytes = Base64.getDecoder().decode(sealed.ciphertext).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThatThrownBy { cipher.open(sealed.copy(ciphertext = Base64.getEncoder().encodeToString(bytes)), "connection:1") }
            .isInstanceOf(SecretUnavailableException::class.java)
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = k2, keyId = "k2")).open(sealed, "connection:1") }
            .isInstanceOf(SecretUnavailableException::class.java).hasMessageContaining("key id k1")
    }

    @Test
    fun `key rotation re-wraps the data key with the new key and keeps the ciphertext`() {
        val sealed = AesGcmSecretCipher(SecretsProperties(key = k1, keyId = "k1")).seal(token, "connection:1")
        val rotated = AesGcmSecretCipher(SecretsProperties(key = k2, keyId = "k2", previousKeys = "k1:$k1"))

        assertThat(rotated.knows("k1")).isTrue()
        assertThat(rotated.open(sealed, "connection:1")).isEqualTo(token)
        val rewrapped = rotated.rewrap(sealed, "connection:1")!!
        assertThat(rewrapped.keyId).isEqualTo("k2")
        assertThat(rewrapped.ciphertext).isEqualTo(sealed.ciphertext)
        assertThat(rotated.rewrap(rewrapped, "connection:1")).isNull()

        // after the rotation the old key can be removed
        val onlyNew = AesGcmSecretCipher(SecretsProperties(key = k2, keyId = "k2"))
        assertThat(onlyNew.open(rewrapped, "connection:1")).isEqualTo(token)
        assertThatThrownBy { onlyNew.open(sealed, "connection:1") }.isInstanceOf(SecretUnavailableException::class.java)
    }

    @Test
    fun `invalid configuration fails clearly and a missing key means not configured`() {
        assertThat(AesGcmSecretCipher(SecretsProperties()).configured).isFalse()
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties()).seal(token, "x") }.hasMessageContaining("BACKOFFICE_SECRETS_KEY")
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = Base64.getEncoder().encodeToString(ByteArray(16)))) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("32 bytes").hasMessageContaining("openssl rand -base64 32")
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = "not base64 !")) }.hasMessageContaining("not valid base64")
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = k1, keyId = "k1", previousKeys = "k1:$k2")) }.hasMessageContaining("repeats the active key id")
        assertThatThrownBy { AesGcmSecretCipher(SecretsProperties(key = k1, previousKeys = "garbage")) }.hasMessageContaining("id:base64")
        assertThat(SecretsProperties(key = k1).toString()).doesNotContain(k1)
    }
}
