package pl.mos.backoffice.governance.adapter.out.signing

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.governance.domain.service.Digests
import pl.mos.backoffice.governance.domain.service.Dsse
import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.DsseSignature
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * `backoffice.signing` (ADR 0024/0025). `keys` lists the platform's Ed25519 keys: exactly one should be `active`
 * (it signs; it needs `privateKeyPkcs8Base64`), the others are retired and only verify (public key only is
 * enough). `id` is the published `kid` / DSSE `keyid`; when blank it defaults to the key fingerprint
 * `sha256:<hex of SHA-256 of the raw 32-byte public key>`. Without keys the signer generates an ephemeral key
 * (logged as a warning) when `allow-ephemeral` is true: signatures then do not survive a restart and differ
 * between replicas, so production must configure a key (the Helm chart generates one).
 */
@ConfigurationProperties("backoffice.signing")
data class SigningProperties(
    val keys: List<Key> = emptyList(),
    val allowEphemeral: Boolean = true,
) {
    data class Key(
        val id: String? = null,
        val privateKeyPkcs8Base64: String? = null,
        val publicKeyX509Base64: String? = null,
        val active: Boolean = true,
    )
}

/**
 * [EnvelopeSigner] with JDK Ed25519 (`Signature.getInstance("Ed25519")`). DSSE envelopes are signed over
 * `PAE(payloadType, payload)`; detached signatures (runner assignments) over the raw bytes. Verification tries
 * the key named by `keyid`, current and retired keys alike; the JWK set publishes all of them (`OKP`/`Ed25519`).
 */
class Ed25519EnvelopeSigner(keys: List<SigningKey>) : EnvelopeSigner {

    data class SigningKey(val id: String, val publicKey: PublicKey, val privateKey: PrivateKey?, val active: Boolean) {
        val rawPublicKey: ByteArray get() = rawEd25519(publicKey)
    }

    private val keys: List<SigningKey> = keys.toList()
    private val byId: Map<String, SigningKey> = keys.associateBy { it.id }
    private val signingKey: SigningKey

    init {
        require(keys.isNotEmpty()) { "at least one signing key is required" }
        require(byId.size == keys.size) { "signing key ids must be unique" }
        signingKey = keys.firstOrNull { it.active && it.privateKey != null }
            ?: throw IllegalArgumentException("no active signing key with a private key (backoffice.signing.keys[*].active + privateKeyPkcs8Base64)")
        keys.filter { it.privateKey != null }.forEach { key ->
            val probe = "back-office signing key check".toByteArray()
            require(verifyWith(key.publicKey, probe, signRaw(key.privateKey!!, probe))) { "signing key ${key.id}: private and public key do not match" }
        }
    }

    val activeKeyId: String get() = signingKey.id

    override fun signEnvelope(payloadType: String, payload: ByteArray): DsseEnvelope {
        val signature = signRaw(signingKey.privateKey!!, Dsse.pae(payloadType, payload))
        return DsseEnvelope(payloadType, Digests.base64(payload), listOf(DsseSignature(signingKey.id, Digests.base64(signature))))
    }

    override fun sign(bytes: ByteArray): DsseSignature = DsseSignature(signingKey.id, Digests.base64(signRaw(signingKey.privateKey!!, bytes)))

    override fun verify(envelope: DsseEnvelope): Boolean {
        val payload = runCatching { Digests.decodeBase64(envelope.payloadBase64) }.getOrNull() ?: return false
        val pae = Dsse.pae(envelope.payloadType, payload)
        return envelope.signatures.any { verify(pae, it) }
    }

    override fun verify(bytes: ByteArray, signature: DsseSignature): Boolean {
        val key = byId[signature.keyId] ?: return false
        val sig = runCatching { Digests.decodeBase64(signature.signature) }.getOrNull() ?: return false
        return verifyWith(key.publicKey, bytes, sig)
    }

    override fun publicJwks(): List<Map<String, Any?>> = keys.map {
        linkedMapOf(
            "kty" to "OKP",
            "crv" to "Ed25519",
            "x" to Base64.getUrlEncoder().withoutPadding().encodeToString(it.rawPublicKey),
            "kid" to it.id,
            "use" to "sig",
            "alg" to "EdDSA",
            "status" to if (it.id == signingKey.id) "active" else "retired",
            "fingerprint" to fingerprint(it.publicKey),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(Ed25519EnvelopeSigner::class.java)
        private const val ALGORITHM = "Ed25519"
        /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410): the raw key is the last 32 bytes. */
        private const val SPKI_LENGTH = 44
        private const val RAW_LENGTH = 32

        fun fromProperties(properties: SigningProperties): Ed25519EnvelopeSigner {
            // an entry without any key material is an unset slot (e.g. BACKOFFICE_SIGNING_KEYS_0_ACTIVE alone), not a key
            val configured = properties.keys.withIndex().filterNot { (_, key) ->
                key.publicKeyX509Base64.isNullOrBlank() && key.privateKeyPkcs8Base64.isNullOrBlank()
            }
            if (configured.isEmpty()) {
                check(properties.allowEphemeral) { "backoffice.signing.keys has no key material and backoffice.signing.allow-ephemeral=false" }
                val generated = generate()
                log.warn(
                    "backoffice.signing.keys is not configured: generated an EPHEMERAL Ed25519 signing key kid={}. Attestations and runner " +
                        "assignments signed with it cannot be verified after a restart or on another replica; configure a key for production.",
                    generated.id,
                )
                return Ed25519EnvelopeSigner(listOf(generated))
            }
            val factory = KeyFactory.getInstance(ALGORITHM)
            val keys = configured.map { (index, key) ->
                val publicText = key.publicKeyX509Base64?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("backoffice.signing.keys[$index].publicKeyX509Base64 is required")
                val publicKey = factory.generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicText.trim())))
                val privateKey = key.privateKeyPkcs8Base64?.takeIf { it.isNotBlank() }
                    ?.let { factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(it.trim()))) }
                SigningKey(key.id?.takeIf { it.isNotBlank() } ?: fingerprint(publicKey), publicKey, privateKey, key.active)
            }
            return Ed25519EnvelopeSigner(keys)
        }

        fun generate(): SigningKey {
            val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            return SigningKey(fingerprint(pair.public), pair.public, pair.private, active = true)
        }

        /** `sha256:<hex>` of the raw 32-byte public key. */
        fun fingerprint(publicKey: PublicKey): String = "sha256:" + Digests.sha256Hex(rawEd25519(publicKey))

        fun rawEd25519(publicKey: PublicKey): ByteArray {
            val encoded = publicKey.encoded
            require(encoded.size == SPKI_LENGTH) { "not an Ed25519 public key (${encoded.size} bytes)" }
            return encoded.copyOfRange(SPKI_LENGTH - RAW_LENGTH, SPKI_LENGTH)
        }

        private fun signRaw(key: PrivateKey, bytes: ByteArray): ByteArray =
            Signature.getInstance(ALGORITHM).run {
                initSign(key)
                update(bytes)
                sign()
            }

        private fun verifyWith(key: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean = runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
