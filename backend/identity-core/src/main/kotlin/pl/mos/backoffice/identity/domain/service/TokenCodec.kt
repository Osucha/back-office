package pl.mos.backoffice.identity.domain.service

import java.security.MessageDigest
import java.security.SecureRandom

data class GeneratedToken(val plaintext: String, val hash: String, val prefix: String)

/**
 * Personal access tokens: `bo_pat_` + 40 URL-safe random characters (~238 bits). Only the SHA-256 hex hash
 * is stored; the first [PREFIX_LENGTH] characters are kept for display.
 */
object TokenCodec {
    const val PAT_PREFIX = "bo_pat_"
    const val CI_PREFIX = "bo_ci_"
    const val RANDOM_LENGTH = 40
    const val PREFIX_LENGTH = 12

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val random = SecureRandom()

    fun generate(kind: String = PAT_PREFIX): GeneratedToken {
        val body = CharArray(RANDOM_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
        val plaintext = kind + body
        return GeneratedToken(plaintext, hash(plaintext), plaintext.take(PREFIX_LENGTH))
    }

    fun hash(plaintext: String): String =
        MessageDigest.getInstance("SHA-256").digest(plaintext.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Cheap syntactic check before a database lookup. */
    fun looksLikePersonalToken(value: String): Boolean =
        value.startsWith(PAT_PREFIX) && value.length == PAT_PREFIX.length + RANDOM_LENGTH &&
            value.substring(PAT_PREFIX.length).all { it in ALPHABET }
}
