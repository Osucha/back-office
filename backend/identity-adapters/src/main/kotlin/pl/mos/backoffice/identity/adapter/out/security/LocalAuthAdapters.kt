package pl.mos.backoffice.identity.adapter.out.security

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import pl.mos.backoffice.identity.application.port.out.CodeHasher
import pl.mos.backoffice.identity.application.port.out.CommonPasswords
import pl.mos.backoffice.identity.application.port.out.MfaChallenges
import pl.mos.backoffice.identity.application.port.out.PasswordHasher
import pl.mos.backoffice.identity.application.port.out.SecretCipher
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.UserId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The master key `backoffice.auth.local.secret-key` (base64, 32 bytes). Purpose keys are derived with
 * HMAC-SHA256(master, label), so the TOTP cipher, the recovery-code hash and the MFA token signature never
 * share a key.
 */
class LocalKeyMaterial(private val master: ByteArray) {
    init {
        require(master.size == KEY_BYTES) { "the local-auth secret key must be $KEY_BYTES bytes" }
    }

    fun derive(label: String): ByteArray = hmac(master, label.toByteArray(Charsets.UTF_8))

    companion object {
        const val KEY_BYTES = 32
        const val PROPERTY = "backoffice.auth.local.secret-key"

        /**
         * Decodes [base64]. Blank: fails when [required] (local login enabled), otherwise a random key for this
         * process (nothing encrypted with it survives a restart, which is fine while local login is off).
         */
        fun from(base64: String, required: Boolean): LocalKeyMaterial {
            if (base64.isBlank()) {
                check(!required) {
                    "$PROPERTY (env BACKOFFICE_AUTH_LOCAL_SECRET_KEY) must be set while local login is enabled " +
                        "(backoffice.auth.local.enabled=true): a base64-encoded 32-byte key, e.g. `openssl rand -base64 32`. " +
                        "The Helm chart generates one; set backoffice.auth.local.enabled=false to run with OIDC only."
                }
                return LocalKeyMaterial(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))
            }
            val bytes = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull()
            check(bytes != null && bytes.size == KEY_BYTES) { "$PROPERTY must be base64 of exactly $KEY_BYTES bytes (e.g. `openssl rand -base64 32`)" }
            return LocalKeyMaterial(bytes)
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

/**
 * Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`) inside a [DelegatingPasswordEncoder]
 * with id `argon2`: stored hashes look like `{argon2}$argon2id$v=19$m=16384,t=2,p=1$...`, and
 * `upgradeEncoding` tells when parameters changed so the next login re-hashes.
 */
class SpringPasswordHasher(private val encoder: PasswordEncoder) : PasswordHasher {

    override fun hash(raw: String): String = encoder.encode(raw)!!

    override fun matches(raw: String, hash: String): Boolean = runCatching { encoder.matches(raw, hash) }.getOrDefault(false)

    override fun needsRehash(hash: String): Boolean = runCatching { encoder.upgradeEncoding(hash) }.getOrDefault(false)

    override val dummyHash: String = encoder.encode("dummy-" + UUID.randomUUID())!!

    companion object {
        const val ARGON2 = "argon2"

        fun encoder(): PasswordEncoder = DelegatingPasswordEncoder(ARGON2, mapOf<String, PasswordEncoder>(ARGON2 to Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()))
    }
}

/** AES-256-GCM, random 96-bit IV; format `v1:` + base64(iv || ciphertext || tag). */
class AesGcmSecretCipher(keys: LocalKeyMaterial) : SecretCipher {
    private val key = SecretKeySpec(keys.derive("totp-secret"), "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext))
    }

    override fun decrypt(ciphertext: String): ByteArray {
        require(ciphertext.startsWith(PREFIX)) { "unknown secret format" }
        val bytes = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    private companion object {
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** HMAC-SHA256 (hex) keyed from the master key: a stolen database alone does not allow brute-forcing recovery codes. */
class HmacCodeHasher(keys: LocalKeyMaterial) : CodeHasher {
    private val key = keys.derive("recovery-code")
    override fun hash(value: String): String = LocalKeyMaterial.hmac(key, value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * `base64url(userId(16) || expiresAtEpochSecond(8) || nonce(16)) + "." + base64url(HMAC)`. Consumed nonces are
 * remembered by [used] until the token would have expired anyway: in memory for one process, in PostgreSQL
 * (`mfa_token_use`) when several replicas serve logins, so a token is single use cluster-wide (ADR 0023).
 */
class SignedMfaChallenges(
    keys: LocalKeyMaterial,
    private val clock: Clock,
    private val validity: Duration = Duration.ofMinutes(5),
    private val used: UsedTokenStore = InMemoryUsedTokenStore(),
) : MfaChallenges {
    private val key = keys.derive("mfa-token")
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun issue(userId: UserId): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val payload = ByteBuffer.allocate(PAYLOAD_BYTES)
            .putLong(userId.value.mostSignificantBits).putLong(userId.value.leastSignificantBits)
            .putLong(clock.now().plus(validity).epochSecond)
            .put(nonce)
            .array()
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(LocalKeyMaterial.hmac(key, payload))
    }

    override fun consume(token: String): UserId? {
        if (token.length > MAX_TOKEN) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        val payload = runCatching { decoder.decode(parts[0]) }.getOrNull() ?: return null
        val signature = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        if (payload.size != PAYLOAD_BYTES || !MessageDigest.isEqual(signature, LocalKeyMaterial.hmac(key, payload))) return null
        val buffer = ByteBuffer.wrap(payload)
        val userId = UserId(UUID(buffer.long, buffer.long))
        val expires = buffer.long
        val now = clock.now().epochSecond
        if (now >= expires) return null
        val nonce = parts[0]
        if (!used.markUsed(nonce, java.time.Instant.ofEpochSecond(expires), clock.now())) return null
        return userId
    }

    private companion object {
        const val NONCE_BYTES = 16
        const val PAYLOAD_BYTES = 16 + 8 + NONCE_BYTES
        const val MAX_TOKEN = 200
        const val PURGE_THRESHOLD = 1_000
    }
}

/**
 * The 10 000 most common passwords (SecLists `10k-most-common.txt`, MIT) bundled as
 * `identity/common-passwords.txt`; compared case-insensitively. Lines starting with `#` are comments.
 */
class ResourceCommonPasswords(resource: String = RESOURCE) : CommonPasswords {
    private val passwords: Set<String> = (javaClass.classLoader.getResourceAsStream(resource) ?: error("missing $resource"))
        .bufferedReader(Charsets.UTF_8)
        .useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.lowercase() }.toHashSet() }

    val size: Int get() = passwords.size

    override fun contains(password: String): Boolean = password.lowercase() in passwords

    companion object {
        const val RESOURCE = "identity/common-passwords.txt"
    }
}
