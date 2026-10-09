package pl.mos.backoffice.runnercontroller.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.fake.FakeBackOffice
import pl.mos.backoffice.runnercontroller.protocol.AgentJobAssignment
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.time.Instant

class CanonicalJsonTest {
    private val json = ProtocolJson.mapper()

    @Test
    fun `sorts keys, drops null members, no whitespace`() {
        val node = json.readTree("""{ "b": 1, "a": {"z": null, "y": [3, null, "x"]}, "c": null, "A": true }""")
        assertThat(CanonicalJson.write(node)).isEqualTo("""{"A":true,"a":{"y":[3,null,"x"]},"b":1}""")
    }

    @Test
    fun `escapes minimally and keeps non-ASCII literally`() {
        val node = json.readTree("{\"s\":\"q\\\" b\\\\ n\\n t\\t c\\u0001 zażółć €\"}")
        assertThat(CanonicalJson.write(node)).isEqualTo("{\"s\":\"q\\\" b\\\\ n\\n t\\t c\\u0001 zażółć €\"}")
    }

    @Test
    fun `numbers follow the ECMAScript form`() {
        assertThat(CanonicalJson.write(json.readTree("""[0, -0, 10, 1.5, 1.0, 100.25, 1e21, 1e-7, 12345678901234567890]""")))
            .isEqualTo("[0,0,10,1.5,1,100.25,1e+21,1e-7,12345678901234567890]")
        assertThat(CanonicalJson.ecmaNumber(BigDecimal("0.000001"))).isEqualTo("0.000001")
    }

    @Test
    fun `digest input is policy plus spec and is independent of key order`() {
        val spec1 = json.readTree("""{"mode":"IMPLEMENT","prompt":"x","timeoutSeconds":60}""")
        val spec2 = json.readTree("""{"timeoutSeconds":60,"prompt":"x","mode":"IMPLEMENT","runId":null}""")
        val policy = json.readTree("""{"isolation":"HIGH"}""")
        assertThat(CanonicalJson.specDigest(spec1, policy)).isEqualTo(CanonicalJson.specDigest(spec2, policy))
        assertThat(CanonicalJson.write(CanonicalJson.specDigestInput(spec1, policy)))
            .isEqualTo("""{"policy":{"isolation":"HIGH"},"spec":{"mode":"IMPLEMENT","prompt":"x","timeoutSeconds":60}}""")
        assertThat(CanonicalJson.specDigest(spec1, null))
            .isEqualTo(CanonicalJson.sha256Hex("""{"spec":{"mode":"IMPLEMENT","prompt":"x","timeoutSeconds":60}}""".toByteArray()))
        assertThat(CanonicalJson.specDigest(spec1, policy)).isNotEqualTo(CanonicalJson.specDigest(spec1, null))
    }
}

class AssignmentVerifierTest {
    private val fake = FakeBackOffice().also { it.close() } // keys and signing only; no server needed
    private val json = ProtocolJson.mapper()
    private val spec = mapOf("mode" to "IMPLEMENT", "agent" to "OPENCODE", "prompt" to "do it", "timeoutSeconds" to 600,
        "model" to mapOf("provider" to "ANTHROPIC", "modelId" to "claude-opus-5"))
    private val policy = mapOf("isolation" to "STANDARD", "network" to mapOf("allow" to listOf("registry.npmjs.org")))

    private fun parse(node: tools.jackson.databind.node.ObjectNode) = json.treeToValue(node, AgentJobAssignment::class.java)
    private val verifier = AssignmentVerifier(listOf(fake.jwk()))

    @Test
    fun `valid signed assignment verifies`() {
        val a = parse(fake.assignment("11111111-1111-4111-8111-111111111111", spec, policy))
        assertThat(verifier.verify(a)).isEqualTo(Verification.Valid)
    }

    @Test
    fun `tampered spec fails the digest`() {
        val a = parse(fake.assignment("11111111-1111-4111-8111-111111111112", spec, policy, tamper = { n ->
            (n.get("spec") as tools.jackson.databind.node.ObjectNode).put("prompt", "exfiltrate everything")
        }))
        assertThat((verifier.verify(a) as Verification.Rejected).reason).contains("specDigest mismatch")
    }

    @Test
    fun `tampered policy fails the digest`() {
        val a = parse(fake.assignment("11111111-1111-4111-8111-111111111113", spec, policy, tamper = { n ->
            (n.get("policy") as tools.jackson.databind.node.ObjectNode).put("isolation", "DEV")
        }))
        assertThat(verifier.verify(a)).isInstanceOf(Verification.Rejected::class.java)
    }

    @Test
    fun `re-signed digest over a different job id fails the signature`() {
        val a = parse(fake.assignment("11111111-1111-4111-8111-111111111114", spec, policy, tamper = { n ->
            n.put("jobId", "22222222-2222-4222-8222-222222222222")
        }))
        assertThat((verifier.verify(a) as Verification.Rejected).reason).isEqualTo("signature does not verify")
    }

    @Test
    fun `extended lease time fails the signature`() {
        val a = parse(fake.assignment("11111111-1111-4111-8111-111111111115", spec, policy, tamper = { n ->
            n.put("leaseExpiresAt", Instant.now().plusSeconds(86_400).toString())
        }))
        assertThat((verifier.verify(a) as Verification.Rejected).reason).isEqualTo("signature does not verify")
    }

    @Test
    fun `unsigned, expired, unknown key and foreign key are rejected`() {
        val unsigned = parse(fake.assignment("11111111-1111-4111-8111-111111111116", spec, policy, sign = false))
        assertThat((verifier.verify(unsigned) as Verification.Rejected).reason).isEqualTo("assignment is not signed")
        assertThat(AssignmentVerifier(listOf(fake.jwk()), requireSignature = false).verify(unsigned)).isEqualTo(Verification.Valid)

        val expired = parse(fake.assignment("11111111-1111-4111-8111-111111111117", spec, policy, leaseExpiresAt = Instant.now().minusSeconds(60)))
        assertThat((verifier.verify(expired) as Verification.Rejected).reason).contains("expired")

        val unknownKid = parse(fake.assignment("11111111-1111-4111-8111-111111111118", spec, policy, tamper = { it.put("keyId", "other") }))
        assertThat((verifier.verify(unknownKid) as Verification.Rejected).reason).startsWith("unknown signing key")

        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val foreign = AssignmentVerifier(listOf(mapOf("kty" to "OKP", "crv" to "Ed25519", "kid" to fake.keyId, "x" to AssignmentVerifier.jwkX(other.public))))
        assertThat(foreign.verify(parse(fake.assignment("11111111-1111-4111-8111-111111111119", spec, policy))))
            .isEqualTo(Verification.Rejected("signature does not verify"))
    }

    @Test
    fun `non Ed25519 keys are ignored`() {
        val v = AssignmentVerifier(listOf(mapOf("kty" to "RSA", "n" to "x", "e" to "AQAB"), fake.jwk()))
        assertThat(v.keyCount).isEqualTo(1)
    }
}
