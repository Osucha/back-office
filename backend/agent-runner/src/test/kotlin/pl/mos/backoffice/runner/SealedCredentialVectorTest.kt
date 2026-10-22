package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.crypto.hpke.HPKE
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.adapter.out.crypto.HpkeCredentialSealer
import pl.mos.backoffice.runner.application.port.out.CredentialSealer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * ADR 0051: Back Office seals with the suite of docs/api/fixtures/sealed-credential-vector.json, which every
 * runner-controller implementation must open (the controller's CredentialKeysTest opens the same vector).
 */
class SealedCredentialVectorTest {

    private val vector = JsonMapper.builder().build().readTree(Files.readString(locate("docs/api/fixtures/sealed-credential-vector.json")))
    private val hpke = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
    private val decoder = Base64.getDecoder()

    private fun open(enc: String, ciphertext: String, aad: String): String {
        val pair = hpke.deserializePrivateKey(decoder.decode(vector.get("privateKey").asString()), decoder.decode(vector.get("publicKey").asString()))
        return String(hpke.open(decoder.decode(enc), pair, vector.get("info").asString().toByteArray(), aad.toByteArray(), decoder.decode(ciphertext), null, null, null))
    }

    @Test
    fun `what the sealer produces opens with the vector's key pair, and the vector uses the protocol's info`() {
        assertThat(vector.get("info").asString()).isEqualTo(CredentialSealer.INFO)
        assertThat(open(vector.get("enc").asString(), vector.get("ciphertext").asString(), vector.get("associatedData").asString()))
            .isEqualTo(vector.get("plaintext").asString())

        val sealer = HpkeCredentialSealer()
        assertThat(sealer.valid(vector.get("publicKey").asString())).isTrue()
        assertThat(sealer.valid("c2hvcnQ=")).isFalse()
        val aad = CredentialSealer.associatedData("job-1", "ref-1", "2026-10-22T12:00:00Z")
        val sealed = sealer.seal(vector.get("publicKey").asString(), "tkn-sealed", aad)
        assertThat(sealed.toString()).doesNotContain(sealed.ciphertext)
        assertThat(open(sealed.enc, sealed.ciphertext, aad)).isEqualTo("tkn-sealed")
    }

    private fun locate(relative: String): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve(relative) }.first { Files.isRegularFile(it) }
}
