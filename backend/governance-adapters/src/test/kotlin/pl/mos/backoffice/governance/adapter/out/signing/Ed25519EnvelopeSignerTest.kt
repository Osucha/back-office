package pl.mos.backoffice.governance.adapter.out.signing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.domain.service.Digests
import pl.mos.backoffice.governance.domain.service.Dsse
import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.DsseSignature
import java.util.Base64

class Ed25519EnvelopeSignerTest {

    @Test
    fun `RFC 8032 test vector 1 - signature of the empty message`() {
        val signer = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY)))
        val signature = signer.sign(ByteArray(0))
        assertThat(signature.keyId).isEqualTo("rfc8032-1")
        assertThat(Digests.hex(Base64.getDecoder().decode(signature.signature))).isEqualTo(RFC_SIGNATURE)
        assertThat(signer.verify(ByteArray(0), signature)).isTrue()
    }

    @Test
    fun `JWK publishes the raw public key as OKP Ed25519 with the key id`() {
        val signer = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY)))
        val jwk = signer.publicJwks().single()
        assertThat(jwk["kty"]).isEqualTo("OKP")
        assertThat(jwk["crv"]).isEqualTo("Ed25519")
        assertThat(jwk["kid"]).isEqualTo("rfc8032-1")
        assertThat(jwk["status"]).isEqualTo("active")
        assertThat(Digests.hex(Base64.getUrlDecoder().decode(jwk["x"].toString()))).isEqualTo(RFC_PUBLIC)
        assertThat(jwk["fingerprint"]).isEqualTo("sha256:" + Digests.sha256Hex(hexBytes(RFC_PUBLIC)))
    }

    @Test
    fun `DSSE envelope signs the PAE and round-trips`() {
        val signer = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY)))
        val payload = """{"_type":"https://in-toto.io/Statement/v1"}""".toByteArray()
        val envelope = signer.signEnvelope(Dsse.IN_TOTO_PAYLOAD_TYPE, payload)
        assertThat(Base64.getDecoder().decode(envelope.payloadBase64)).isEqualTo(payload)
        assertThat(signer.verify(envelope)).isTrue()
        // the signature is over PAE(type, payload), not over the payload alone
        val sig = envelope.signatures.single()
        assertThat(signer.verify(Dsse.pae(envelope.payloadType, payload), sig)).isTrue()
        assertThat(signer.verify(payload, sig)).isFalse()
    }

    @Test
    fun `tampering with payload, type or signature is detected`() {
        val signer = Ed25519EnvelopeSigner.fromProperties(SigningProperties())
        val envelope = signer.signEnvelope(Dsse.IN_TOTO_PAYLOAD_TYPE, "payload".toByteArray())
        assertThat(signer.verify(envelope.copy(payloadBase64 = Digests.base64("payloaD".toByteArray())))).isFalse()
        assertThat(signer.verify(envelope.copy(payloadType = "application/json"))).isFalse()
        val sig = Base64.getDecoder().decode(envelope.signatures.single().signature).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThat(signer.verify(envelope.copy(signatures = listOf(DsseSignature(envelope.signatures.single().keyId, Digests.base64(sig)))))).isFalse()
        assertThat(signer.verify(envelope.copy(signatures = listOf(envelope.signatures.single().copy(keyId = "other"))))).isFalse()
        assertThat(signer.verify(DsseEnvelope(envelope.payloadType, "not base64!", envelope.signatures))).isFalse()
    }

    @Test
    fun `retired keys still verify and are published, the active key signs`() {
        val old = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY)))
        val oldEnvelope = old.signEnvelope("t", "x".toByteArray())
        val fresh = Ed25519EnvelopeSigner.generate()
        val rotated = Ed25519EnvelopeSigner.fromProperties(
            SigningProperties(
                listOf(
                    RFC_KEY.copy(privateKeyPkcs8Base64 = null, active = false),
                    SigningProperties.Key(
                        id = null,
                        privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(fresh.privateKey!!.encoded),
                        publicKeyX509Base64 = Base64.getEncoder().encodeToString(fresh.publicKey.encoded),
                    ),
                ),
            ),
        )
        assertThat(rotated.verify(oldEnvelope)).isTrue()
        assertThat(rotated.activeKeyId).isEqualTo(Ed25519EnvelopeSigner.fingerprint(fresh.publicKey))
        assertThat(rotated.signEnvelope("t", "y".toByteArray()).signatures.single().keyId).isEqualTo(rotated.activeKeyId)
        assertThat(rotated.publicJwks().map { it["status"] }).containsExactly("retired", "active")
    }

    @Test
    fun `mismatched key pair and missing signing key are rejected`() {
        val other = Ed25519EnvelopeSigner.generate()
        assertThatThrownBy {
            Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY.copy(publicKeyX509Base64 = Base64.getEncoder().encodeToString(other.publicKey.encoded)))))
        }.hasMessageContaining("do not match")
        assertThatThrownBy { Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(RFC_KEY.copy(active = false)))) }
            .hasMessageContaining("no active signing key")
        assertThatThrownBy { Ed25519EnvelopeSigner.fromProperties(SigningProperties(allowEphemeral = false)) }
            .hasMessageContaining("allow-ephemeral")
    }

    @Test
    fun `entries without key material are unset slots, not keys`() {
        val blank = SigningProperties.Key(id = "", privateKeyPkcs8Base64 = "", publicKeyX509Base64 = " ", active = true)
        val ephemeral = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(blank)))
        assertThat(ephemeral.publicJwks()).hasSize(1)
        assertThatThrownBy { Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(blank), allowEphemeral = false)) }
            .hasMessageContaining("allow-ephemeral")
        val withKey = Ed25519EnvelopeSigner.fromProperties(SigningProperties(listOf(blank, RFC_KEY)))
        assertThat(withKey.publicJwks().map { it["kid"] }).containsExactly(RFC_KEY.id)
    }

    companion object {
        const val RFC_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        const val RFC_PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val RFC_SIGNATURE =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** PKCS#8 and SPKI DER prefixes of Ed25519 (RFC 8410) around the RFC 8032 key. */
        val RFC_KEY = SigningProperties.Key(
            id = "rfc8032-1",
            privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(hexBytes("302e020100300506032b657004220420$RFC_SEED")),
            publicKeyX509Base64 = Base64.getEncoder().encodeToString(hexBytes("302a300506032b6570032100$RFC_PUBLIC")),
            active = true,
        )
    }
}
