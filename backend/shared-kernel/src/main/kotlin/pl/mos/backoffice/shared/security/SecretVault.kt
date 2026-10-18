package pl.mos.backoffice.shared.security

/** A secret sealed with envelope encryption: data key wrapped by key [keyId], both Base64. */
data class SealedValue(val keyId: String, val wrappedKey: String, val ciphertext: String) {
    override fun toString(): String = "SealedValue(keyId=$keyId)"
}

/**
 * Cross-context port (ADR 0045): seals and opens secrets with the installation's key-encryption key
 * (`BACKOFFICE_SECRETS_KEY`, AES-256-GCM envelope encryption). [associatedData] binds a ciphertext to its owner, for
 * example `llm-provider:<id>`; the same value is needed to open it. Implemented by the projects adapters.
 */
interface SecretVault {
    /** A key-encryption key is configured. */
    val configured: Boolean

    fun seal(plaintext: String, associatedData: String): SealedValue

    /** Throws when the key is unknown or the ciphertext does not belong to [associatedData]. */
    fun open(sealed: SealedValue, associatedData: String): String
}
