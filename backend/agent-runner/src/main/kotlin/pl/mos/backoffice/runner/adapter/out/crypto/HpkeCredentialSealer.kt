package pl.mos.backoffice.runner.adapter.out.crypto

import org.bouncycastle.crypto.hpke.HPKE
import pl.mos.backoffice.runner.application.port.out.CredentialSealer
import pl.mos.backoffice.runner.application.port.out.SealedCredential
import java.util.Base64

/** [CredentialSealer] with Bouncy Castle's RFC 9180 implementation (the runner-controller opens with the same suite). */
class HpkeCredentialSealer : CredentialSealer {

    private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    override fun seal(publicKey: String, plaintext: String, associatedData: String): SealedCredential {
        val hpke = hpke()
        val recipient = hpke.deserializePublicKey(decode(publicKey))
        val result = hpke.seal(recipient, INFO, associatedData.toByteArray(Charsets.UTF_8), plaintext.toByteArray(Charsets.UTF_8), null, null, null)
        return SealedCredential(enc = encode(result[1]), ciphertext = encode(result[0]))
    }

    override fun valid(publicKey: String): Boolean = runCatching { decode(publicKey).size == KEY_BYTES && hpke().deserializePublicKey(decode(publicKey)) != null }.getOrDefault(false)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value.trim())
    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private companion object {
        val INFO = CredentialSealer.INFO.toByteArray(Charsets.UTF_8)
        const val KEY_BYTES = 32
    }
}
