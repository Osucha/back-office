package pl.mos.backoffice.runnercontroller.security

import org.bouncycastle.crypto.hpke.HPKE
import java.util.Base64

/**
 * ADR 0051: the controller's X25519 key pair for project repository tokens. Back Office seals every token of a job to
 * [publicKey] (HPKE, RFC 9180 base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM); only this pair
 * opens them, so proxies that inspect TLS on the way see ciphertext only. The private key never leaves the
 * controller's credential store (0600 file or Kubernetes Secret).
 */
class CredentialKeys private constructor(private val privateKey: ByteArray, publicKeyBytes: ByteArray) {

    /** base64 of the 32-byte raw public key, as registered with `PUT /runner/v2/key`. */
    val publicKey: String = Base64.getEncoder().encodeToString(publicKeyBytes)

    /** base64 of the 32-byte raw private key, for the credential store only. */
    fun exportPrivateKey(): String = Base64.getEncoder().encodeToString(privateKey)

    /** Opens one sealed token; [associatedData] = `jobId|ref|expiresAt` rebuilt by the caller. Throws when it does not open. */
    fun open(enc: String, ciphertext: String, associatedData: String): String {
        val hpke = hpke()
        val pair = hpke.deserializePrivateKey(privateKey, Base64.getDecoder().decode(publicKey))
        val plain = hpke.open(
            Base64.getDecoder().decode(enc), pair, INFO, associatedData.toByteArray(Charsets.UTF_8),
            Base64.getDecoder().decode(ciphertext), null, null, null,
        )
        return String(plain, Charsets.UTF_8).also { plain.fill(0) }
    }

    override fun toString() = "CredentialKeys(publicKey=$publicKey)"

    companion object {
        /** HPKE `info` of the protocol (Back Office: `CredentialSealer.INFO`). */
        val INFO = "back-office credentials v1".toByteArray(Charsets.UTF_8)

        private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

        fun generate(): CredentialKeys {
            val hpke = hpke()
            val pair = hpke.generatePrivateKey()
            return CredentialKeys(hpke.serializePrivateKey(pair.private), hpke.serializePublicKey(pair.public))
        }

        /** Restores a stored pair; null when [privateKey] or [publicKey] is missing or not 32 bytes. */
        fun restore(privateKey: String?, publicKey: String?): CredentialKeys? {
            if (privateKey.isNullOrBlank() || publicKey.isNullOrBlank()) return null
            val sk = runCatching { Base64.getDecoder().decode(privateKey.trim()) }.getOrNull() ?: return null
            val pk = runCatching { Base64.getDecoder().decode(publicKey.trim()) }.getOrNull() ?: return null
            if (sk.size != 32 || pk.size != 32) return null
            return CredentialKeys(sk, pk)
        }
    }
}
