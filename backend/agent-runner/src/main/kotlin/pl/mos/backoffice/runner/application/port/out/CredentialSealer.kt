package pl.mos.backoffice.runner.application.port.out

/** One token sealed to a runner's key: [enc] is the HPKE encapsulated key, [ciphertext] the sealed token (base64). */
data class SealedCredential(val enc: String, val ciphertext: String) {
    override fun toString() = "SealedCredential(***)"
}

/**
 * ADR 0051: seals project repository tokens to a runner-controller's X25519 public key (HPKE, RFC 9180 base mode,
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM). Only the controller holding the private key can open them.
 */
interface CredentialSealer {
    /** [publicKey] = base64 of the 32-byte raw X25519 key; [associatedData] binds the ciphertext to job, token and expiry. */
    fun seal(publicKey: String, plaintext: String, associatedData: String): SealedCredential

    /** Whether [publicKey] is a valid base64 X25519 public key. */
    fun valid(publicKey: String): Boolean

    companion object {
        /** HPKE `info` of every sealed credential (protocol version; changes only with an incompatible format). */
        const val INFO = "back-office credentials v1"

        /** Associated data of one token: the controller rebuilds it from the answer and refuses anything else. */
        fun associatedData(jobId: String, ref: String, expiresAt: String) = "$jobId|$ref|$expiresAt"
    }
}
