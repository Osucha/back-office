package pl.mos.backoffice.identity.application.port.out

import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.MfaTotp
import pl.mos.backoffice.identity.domain.model.PasswordResetToken
import pl.mos.backoffice.identity.domain.model.RecoveryCode
import pl.mos.backoffice.shared.security.UserId
import java.time.Instant

// Ports of local accounts (ADR 0021).

interface LocalCredentialRepository {
    fun find(userId: UserId): LocalCredential?
    fun findAll(userIds: Collection<UserId>): Map<UserId, LocalCredential> = userIds.mapNotNull { id -> find(id)?.let { id to it } }.toMap()
    fun save(credential: LocalCredential): LocalCredential
    fun delete(userId: UserId)
}

interface MfaRepository {
    fun findTotp(userId: UserId): MfaTotp?
    fun saveTotp(totp: MfaTotp): MfaTotp
    /** Removes the authenticator and every recovery code of the user. */
    fun deleteAll(userId: UserId)
    fun recoveryCodes(userId: UserId): List<RecoveryCode>
    fun replaceRecoveryCodes(userId: UserId, codes: List<RecoveryCode>)
    /** Marks the unused code with [hash] as used; `false` when there is none (wrong or already used). */
    fun useRecoveryCode(userId: UserId, hash: String, at: Instant): Boolean
}

interface PasswordResetTokenRepository {
    fun findByHash(hash: String): PasswordResetToken?
    fun save(token: PasswordResetToken): PasswordResetToken
    /** Marks every unused token of the user as used (a new link replaces the old ones). */
    fun invalidateAllOf(userId: UserId, at: Instant): Int
    /** Marks the token used; `false` when it was used concurrently. */
    fun markUsed(hash: String, at: Instant): Boolean
}

/** Argon2id behind a delegating encoder in the adapter (parameters can be upgraded on the next login). */
interface PasswordHasher {
    fun hash(raw: String): String
    fun matches(raw: String, hash: String): Boolean
    /** True when [hash] uses outdated parameters and should be re-hashed after a successful login. */
    fun needsRehash(hash: String): Boolean

    /** A valid hash of a random value: unknown users are verified against it so every path costs one hash. */
    val dummyHash: String
}

/** AES-GCM for TOTP secrets, keyed by `backoffice.auth.local.secret-key`. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): String
    fun decrypt(ciphertext: String): ByteArray
}

/** Keyed hash (HMAC-SHA256 with a key derived from the secret key) for low-entropy recovery codes. */
fun interface CodeHasher {
    fun hash(value: String): String
}

/**
 * The short-lived signed token between the password step and the MFA step (5 minutes, bound to the user id
 * and a nonce, consumed on first use).
 */
interface MfaChallenges {
    fun issue(userId: UserId): String
    /** The user id when the token is authentic, unexpired and not used before; consumes it. */
    fun consume(token: String): UserId?
}

/** The bundled list of common passwords (case-insensitive). */
fun interface CommonPasswords {
    fun contains(password: String): Boolean
}
