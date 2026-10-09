package pl.mos.backoffice.blob

import pl.mos.backoffice.shared.ValidationException
import java.io.InputStream
import java.security.MessageDigest

/** Key rules shared by every store: slash-separated relative paths, no traversal, portable characters only. */
object BlobKeys {
    private val allowed = Regex("[A-Za-z0-9._\\-/=+]+")
    const val MAX_LENGTH = 1024

    fun validate(key: String): String {
        val ok = key.isNotEmpty() && key.length <= MAX_LENGTH && allowed.matches(key) &&
            !key.startsWith("/") && !key.endsWith("/") && !key.contains("//") &&
            key.split('/').none { it == "." || it == ".." }
        if (!ok) throw ValidationException("invalid blob key '$key'", "key")
        return key
    }

    /** Prefixes may end with '/'; an empty prefix (= everything) is refused to prevent accidental wipes. */
    fun validatePrefix(prefix: String): String {
        if (prefix.isBlank()) throw ValidationException("blob prefix must not be empty", "prefix")
        validate(prefix.trimEnd('/'))
        return prefix
    }
}

internal object Hashing {
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Reads at most [maxBytes] of [input] as UTF-8; fails when the stream is longer. */
internal fun readBounded(input: InputStream, maxBytes: Int, key: String): String = input.use {
    val bytes = it.readNBytes(maxBytes)
    if (it.read() != -1) throw pl.mos.backoffice.shared.IntegrationException("blob-storage", "object $key is larger than $maxBytes bytes")
    String(bytes, Charsets.UTF_8)
}
