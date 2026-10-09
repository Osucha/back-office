package pl.mos.backoffice.projects.adapter.out.support

import pl.mos.backoffice.projects.application.port.out.TokenHasher
import java.security.MessageDigest
import java.security.SecureRandom

/** [TokenHasher] for CI tokens: SHA-256 hex hashing and url-safe random generation, pure JDK. */
class Sha256TokenHasher : TokenHasher {
    private val random = SecureRandom()

    override fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun generate(prefix: String, randomLength: Int): String {
        val chars = CharArray(randomLength) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return prefix + String(chars)
    }

    companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
