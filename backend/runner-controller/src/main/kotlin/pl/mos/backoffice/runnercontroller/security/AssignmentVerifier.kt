package pl.mos.backoffice.runnercontroller.security

import pl.mos.backoffice.runnercontroller.protocol.AgentJobAssignment
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

sealed interface Verification {
    data object Valid : Verification
    data class Rejected(val reason: String) : Verification
}

/**
 * Verifies that an assignment was issued by the control plane and is still current (doc 10, "Assignment
 * verification"):
 *
 * - `specDigest` must equal SHA-256 over [CanonicalJson] of `{"policy", "spec"}` as received;
 * - `signature` is base64 (standard or URL-safe) Ed25519 over the UTF-8 bytes of
 *   `jobId + "|" + specDigest + "|" + leaseExpiresAt`, using the strings exactly as received;
 * - `keyId` selects the JWK (`kty: OKP`, `crv: Ed25519`, `x`) from `RunnerConfig.signingKeys`; without a
 *   `keyId` every key is tried;
 * - `leaseExpiresAt` must lie in the future (with `clockSkew` tolerance).
 */
class AssignmentVerifier(
    keys: List<Map<String, Any?>>,
    private val requireSignature: Boolean = true,
    private val clockSkew: Duration = Duration.ofSeconds(5),
    private val now: () -> Instant = Instant::now,
) {
    private val keysById: Map<String?, PublicKey> = keys.mapNotNull { jwk -> parseJwk(jwk)?.let { (jwk["kid"] as? String) to it } }.toMap()

    val keyCount: Int get() = keysById.size

    fun verify(assignment: AgentJobAssignment): Verification {
        val expires = runCatching { OffsetDateTime.parse(assignment.leaseExpiresAt).toInstant() }.getOrNull()
            ?: return Verification.Rejected("leaseExpiresAt is not an RFC 3339 timestamp")
        if (expires.plus(clockSkew).isBefore(now())) return Verification.Rejected("assignment expired at ${assignment.leaseExpiresAt}")

        val computed = CanonicalJson.specDigest(assignment.spec, assignment.policy)
        val claimed = assignment.specDigest
        if (claimed == null) {
            if (requireSignature) return Verification.Rejected("assignment has no specDigest")
        } else if (!claimed.removePrefix("sha256:").equals(computed, ignoreCase = true)) {
            return Verification.Rejected("specDigest mismatch (computed $computed)")
        }

        val signature = assignment.signature
        if (signature.isNullOrBlank()) {
            return if (requireSignature) Verification.Rejected("assignment is not signed") else Verification.Valid
        }
        if (claimed == null) return Verification.Rejected("signature without specDigest")
        val sigBytes = decodeBase64(signature) ?: return Verification.Rejected("signature is not base64")
        val message = signingInput(assignment.jobId, claimed, assignment.leaseExpiresAt)
        val candidates = if (assignment.keyId != null) listOfNotNull(keysById[assignment.keyId]) else keysById.values.toList()
        if (candidates.isEmpty()) return Verification.Rejected("unknown signing key ${assignment.keyId ?: "(none configured)"}")
        val ok = candidates.any { key ->
            runCatching {
                Signature.getInstance("Ed25519").apply { initVerify(key); update(message) }.verify(sigBytes)
            }.getOrDefault(false)
        }
        return if (ok) Verification.Valid else Verification.Rejected("signature does not verify")
    }

    companion object {
        private val ED25519_X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun signingInput(jobId: String, specDigest: String, leaseExpiresAt: String): ByteArray =
            "$jobId|$specDigest|$leaseExpiresAt".toByteArray(Charsets.UTF_8)

        /** Ed25519 public key from a JWK (`{"kty":"OKP","crv":"Ed25519","x":"<base64url>"}`); null for other keys. */
        fun parseJwk(jwk: Map<String, Any?>): PublicKey? {
            if (jwk["kty"] != "OKP" || jwk["crv"] != "Ed25519") return null
            val x = (jwk["x"] as? String)?.let { decodeBase64(it) } ?: return null
            if (x.size != 32) return null
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + x))
        }

        /** Raw 32-byte public key of an Ed25519 [PublicKey] as JWK `x` (used by tests and the fake server). */
        fun jwkX(key: PublicKey): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded.takeLast(32).toByteArray())

        private fun decodeBase64(s: String): ByteArray? {
            val t = s.trim()
            return runCatching { Base64.getDecoder().decode(t) }.getOrNull()
                ?: runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()
        }
    }
}
