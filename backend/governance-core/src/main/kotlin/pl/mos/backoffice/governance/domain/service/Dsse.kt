package pl.mos.backoffice.governance.domain.service

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * DSSE v1 helpers (https://github.com/secure-systems-lab/dsse/blob/master/protocol.md). The signature is
 * computed over the Pre-Authentication Encoding
 * `PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body`, where `LEN` is the ASCII decimal
 * byte length without leading zeros and SP is one 0x20 byte.
 */
object Dsse {
    const val IN_TOTO_PAYLOAD_TYPE = "application/vnd.in-toto+json"

    fun pae(payloadType: String, payload: ByteArray): ByteArray {
        val type = payloadType.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(type.size + payload.size + 32)
        out.write("DSSEv1 ${type.size} ".toByteArray(Charsets.US_ASCII))
        out.write(type)
        out.write(" ${payload.size} ".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }
}

object Digests {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Standard or URL-safe base64, with or without padding (DSSE implementations differ). */
    fun decodeBase64(text: String): ByteArray {
        val trimmed = text.trim()
        return if (trimmed.contains('-') || trimmed.contains('_')) Base64.getUrlDecoder().decode(trimmed) else Base64.getDecoder().decode(trimmed)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
