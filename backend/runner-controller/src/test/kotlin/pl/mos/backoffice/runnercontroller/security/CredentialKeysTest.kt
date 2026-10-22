package pl.mos.backoffice.runnercontroller.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0051: the controller opens what Back Office seals (shared vector docs/api/fixtures/sealed-credential-vector.json). */
class CredentialKeysTest {

    private val vector = JsonMapper.builder().build().readTree(Files.readString(locate("docs/api/fixtures/sealed-credential-vector.json")))

    @Test
    fun `the conformance vector opens with its key and only with its associated data`() {
        val keys = CredentialKeys.restore(vector.get("privateKey").asString(), vector.get("publicKey").asString())!!
        val enc = vector.get("enc").asString()
        val ciphertext = vector.get("ciphertext").asString()
        assertThat(keys.open(enc, ciphertext, vector.get("associatedData").asString())).isEqualTo(vector.get("plaintext").asString())
        assertThatThrownBy { keys.open(enc, ciphertext, "another|ref|time") }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { CredentialKeys.generate().open(enc, ciphertext, vector.get("associatedData").asString()) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `a generated pair survives the store, broken stored keys are not restored`() {
        val keys = CredentialKeys.generate()
        val restored = CredentialKeys.restore(keys.exportPrivateKey(), keys.publicKey)!!
        assertThat(restored.publicKey).isEqualTo(keys.publicKey)
        assertThat(restored.toString()).doesNotContain(keys.exportPrivateKey())
        assertThat(CredentialKeys.restore(null, keys.publicKey)).isNull()
        assertThat(CredentialKeys.restore("c2hvcnQ=", keys.publicKey)).isNull()
        assertThat(CredentialKeys.restore("%%%", keys.publicKey)).isNull()
    }

    private fun locate(relative: String): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve(relative) }.first { Files.isRegularFile(it) }
}
