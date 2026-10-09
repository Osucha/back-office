package pl.mos.backoffice.identity.domain.service

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 4648 base32 without padding (the format authenticator apps expect in `otpauth://` URIs). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS = 5
    private const val MASK = 0x1f

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + BITS - 1) / BITS)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= BITS) {
                out.append(ALPHABET[(buffer shr (bits - BITS)) and MASK])
                bits -= BITS
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS - bits)) and MASK])
        return out.toString()
    }

    /** Case-insensitive; ignores spaces, dashes and `=` padding. Throws `IllegalArgumentException` on other characters. */
    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filterNot { it == ' ' || it == '-' || it == '=' }
        val out = java.io.ByteArrayOutputStream(clean.length * BITS / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "not a base32 character: $c" }
            buffer = (buffer shl BITS) or value
            bits += BITS
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/**
 * RFC 6238 TOTP with HMAC-SHA1, 30-second steps and 6 digits. [verify] accepts the current step and one step
 * either side (clock drift) and rejects any step not greater than the last accepted one (replay).
 */
object TotpVerifier {
    const val PERIOD_SECONDS = 30L
    const val DIGITS = 6
    const val WINDOW = 1
    const val SECRET_BYTES = 20
    private const val MODULO = 1_000_000
    private val CODE = Regex("^[0-9]{6}$")
    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun stepOf(at: Instant): Long = Math.floorDiv(at.epochSecond, PERIOD_SECONDS)

    /** The code of [step] (RFC 4226 dynamic truncation). */
    fun code(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % MODULO).toString().padStart(DIGITS, '0')
    }

    /** The accepted step, or `null` when the code is wrong, malformed or a replay of [lastUsedStep] (or older). */
    fun verify(secret: ByteArray, code: String, at: Instant, lastUsedStep: Long?): Long? {
        val candidate = code.trim()
        if (!CODE.matches(candidate)) return null
        val current = stepOf(at)
        var matched: Long? = null
        // compare every step of the window (no early exit) so timing does not reveal which one matched
        for (step in current - WINDOW..current + WINDOW) {
            if (MessageDigest.isEqual(code(secret, step).toByteArray(), candidate.toByteArray()) && matched == null) matched = step
        }
        if (matched == null) return null
        if (lastUsedStep != null && matched <= lastUsedStep) return null
        return matched
    }

    /** `otpauth://totp/<issuer>:<account>?secret=...&issuer=...` for authenticator apps and the QR code. */
    fun otpauthUri(issuer: String, account: String, secretBase32: String): String {
        val label = encode("$issuer:$account")
        return "otpauth://totp/$label?secret=$secretBase32&issuer=${encode(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

/** Ten recovery codes `xxxx-xxxx-xx` from an alphabet without look-alikes (no 0/o, 1/l/i). */
object RecoveryCodeGenerator {
    const val COUNT = 10
    const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
    private const val LENGTH = 10
    private val FORMAT = Regex("^[$ALPHABET]{4}-[$ALPHABET]{4}-[$ALPHABET]{2}$")
    private val random = SecureRandom()

    fun generate(count: Int = COUNT): List<String> = List(count) {
        val raw = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
        "${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8)}"
    }

    /** Lower-cased, trimmed, dashes re-inserted if the user left them out; `null` when it cannot be a code. */
    fun normalise(input: String): String? {
        val compact = input.trim().lowercase().filterNot { it == '-' || it == ' ' }
        if (compact.length != LENGTH) return null
        val formatted = "${compact.substring(0, 4)}-${compact.substring(4, 8)}-${compact.substring(8)}"
        return formatted.takeIf { FORMAT.matches(it) }
    }
}

/** One-time set-password links: 32 random bytes, URL-safe base64 (43 characters); only the SHA-256 is stored. */
object ResetTokenCodec {
    private val random = SecureRandom()
    private val FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    fun hash(token: String): String = TokenCodec.hash(token)

    fun looksValid(token: String): Boolean = FORMAT.matches(token)
}
