package pl.mos.backoffice.projects.adapter.out.secrets

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.projects.application.port.out.SecretSealer
import pl.mos.backoffice.projects.domain.model.SealedSecret
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `backoffice.secrets.*` (ADR 0030, docs/guides/configuration.md): the key-encryption key that seals connection
 * secrets. [key] is base64 of 32 bytes (`BACKOFFICE_SECRETS_KEY`), [keyId] names it (`BACKOFFICE_SECRETS_KEY_ID`),
 * [previousKeys] keeps retired keys for decryption until every secret is re-wrapped
 * (`BACKOFFICE_SECRETS_PREVIOUS_KEYS`, `id:base64,id:base64`).
 */
@ConfigurationProperties(prefix = "backoffice.secrets")
data class SecretsProperties(
    val key: String = "",
    val keyId: String = DEFAULT_KEY_ID,
    val previousKeys: String = "",
    /**
     * Recovery switch (`BACKOFFICE_SECRETS_ALLOW_UNREADABLE`): start even though stored secrets cannot be opened (key
     * lost or missing). Affected connections fail their test with SECRET_UNAVAILABLE until "Replace token"; remove the
     * switch afterwards.
     */
    val allowUnreadable: Boolean = false,
) {
    override fun toString(): String = "SecretsProperties(keyId=$keyId, key=${if (key.isBlank()) "unset" else "***"}, previousKeys=${previousKeys.split(',').count { it.isNotBlank() }})"

    companion object {
        const val DEFAULT_KEY_ID = "k1"
    }
}

/** A sealed secret cannot be opened: unknown key id, wrong key or tampered data. The message never contains secret material. */
class SecretUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Envelope encryption with AES-256-GCM (ADR 0030). For every secret a random 256-bit data key encrypts the plaintext
 * (96-bit random nonce, 128-bit tag); the data key is encrypted ("wrapped") with the key-encryption key named by its
 * id. The caller's associated data (the connection id) is authenticated by both layers, so a sealed value copied to
 * another connection does not open. Rotating the key-encryption key only re-wraps the small data keys
 * ([rewrap]); ciphertexts stay as they are.
 *
 * [open] is deliberately not part of [SecretSealer]: only the adapters that call the external system decrypt.
 */
class AesGcmSecretCipher(properties: SecretsProperties, private val random: SecureRandom = SecureRandom()) : SecretSealer {

    private val keys: Map<String, SecretKeySpec>
    override val activeKeyId: String?

    init {
        val parsed = linkedMapOf<String, SecretKeySpec>()
        val activeId = properties.keyId.trim().ifEmpty { SecretsProperties.DEFAULT_KEY_ID }
        requireValidId(activeId)
        if (properties.key.isNotBlank()) parsed[activeId] = decodeKey(activeId, properties.key, "BACKOFFICE_SECRETS_KEY")
        properties.previousKeys.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val id = entry.substringBefore(':', missingDelimiterValue = "").trim()
            val value = entry.substringAfter(':', missingDelimiterValue = "").trim()
            if (id.isEmpty() || value.isEmpty()) {
                throw IllegalStateException("BACKOFFICE_SECRETS_PREVIOUS_KEYS must be a comma list of id:base64 entries")
            }
            requireValidId(id)
            if (id == activeId) throw IllegalStateException("BACKOFFICE_SECRETS_PREVIOUS_KEYS repeats the active key id $id; give the new key a new id")
            parsed[id] = decodeKey(id, value, "BACKOFFICE_SECRETS_PREVIOUS_KEYS")
        }
        keys = parsed
        activeKeyId = activeId.takeIf { it in parsed }
    }

    override val configured: Boolean get() = activeKeyId != null

    override fun knows(keyId: String): Boolean = keyId in keys

    /** Ids of every key that can open secrets (active first). */
    val keyIds: Set<String> get() = keys.keys

    override fun seal(plaintext: String, associatedData: String): SealedSecret {
        val keyId = activeKeyId ?: throw IllegalStateException("BACKOFFICE_SECRETS_KEY is not configured")
        val dataKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val ciphertext = encrypt(SecretKeySpec(dataKey, AES), plaintext.toByteArray(Charsets.UTF_8), dataAad(associatedData))
            val wrapped = encrypt(keys.getValue(keyId), dataKey, wrapAad(keyId, associatedData))
            return SealedSecret(keyId, b64(wrapped), b64(ciphertext))
        } finally {
            dataKey.fill(0)
        }
    }

    /** Decrypts [sealed]; throws [SecretUnavailableException] for an unknown key id, a wrong key or tampered data. */
    fun open(sealed: SealedSecret, associatedData: String): String {
        val dataKey = unwrap(sealed, associatedData)
        try {
            val plain = decrypt(SecretKeySpec(dataKey, AES), unb64(sealed.ciphertext), dataAad(associatedData))
            return String(plain, Charsets.UTF_8).also { plain.fill(0) }
        } catch (e: GeneralSecurityException) {
            throw SecretUnavailableException("the stored secret cannot be decrypted (tampered or sealed for another connection)", e)
        } finally {
            dataKey.fill(0)
        }
    }

    override fun rewrap(sealed: SealedSecret, associatedData: String): SealedSecret? {
        val target = activeKeyId ?: return null
        if (sealed.keyId == target) return null
        val dataKey = unwrap(sealed, associatedData)
        try {
            val wrapped = encrypt(keys.getValue(target), dataKey, wrapAad(target, associatedData))
            return SealedSecret(target, b64(wrapped), sealed.ciphertext)
        } finally {
            dataKey.fill(0)
        }
    }

    private fun unwrap(sealed: SealedSecret, associatedData: String): ByteArray {
        val kek = keys[sealed.keyId]
            ?: throw SecretUnavailableException(
                "the secret was sealed with key id ${sealed.keyId}, which is not configured (BACKOFFICE_SECRETS_KEY / BACKOFFICE_SECRETS_PREVIOUS_KEYS)",
            )
        return try {
            decrypt(kek, unb64(sealed.wrappedKey), wrapAad(sealed.keyId, associatedData))
        } catch (e: GeneralSecurityException) {
            throw SecretUnavailableException("the secret cannot be unwrapped with key ${sealed.keyId} (wrong key value, or sealed for another connection)", e)
        } catch (e: IllegalArgumentException) {
            throw SecretUnavailableException("the stored secret is not valid base64", e)
        }
    }

    private fun encrypt(key: SecretKeySpec, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(VERSION_BYTES + NONCE_BYTES + body.size).put(VERSION).put(nonce).put(body).array()
    }

    private fun decrypt(key: SecretKeySpec, data: ByteArray, aad: ByteArray): ByteArray {
        if (data.size < VERSION_BYTES + NONCE_BYTES + TAG_BITS / 8 || data[0] != VERSION) throw SecretUnavailableException("unsupported sealed secret format")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, VERSION_BYTES, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(data, VERSION_BYTES + NONCE_BYTES, data.size - VERSION_BYTES - NONCE_BYTES)
    }

    private fun dataAad(associatedData: String) = "data|$associatedData".toByteArray(Charsets.UTF_8)

    private fun wrapAad(keyId: String, associatedData: String) = "kek|$keyId|$associatedData".toByteArray(Charsets.UTF_8)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
        private const val VERSION_BYTES = 1
        private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")

        private fun requireValidId(id: String) {
            if (!KEY_ID.matches(id)) throw IllegalStateException("secrets key id $id must match ${KEY_ID.pattern}")
        }

        private fun decodeKey(id: String, base64: String, source: String): SecretKeySpec {
            val bytes = try {
                Base64.getDecoder().decode(base64.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$source (key id $id) is not valid base64; generate one with: openssl rand -base64 32", e)
            }
            if (bytes.size != KEY_BYTES) {
                throw IllegalStateException("$source (key id $id) must be base64 of exactly 32 bytes (AES-256), got ${bytes.size}; generate one with: openssl rand -base64 32")
            }
            return SecretKeySpec(bytes, AES)
        }
    }
}
