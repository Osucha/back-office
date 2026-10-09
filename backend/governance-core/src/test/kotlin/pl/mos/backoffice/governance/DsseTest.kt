package pl.mos.backoffice.governance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.domain.service.Digests
import pl.mos.backoffice.governance.domain.service.Dsse

class DsseTest {

    @Test
    fun `PAE follows the DSSE v1 layout with byte lengths`() {
        val pae = Dsse.pae("http://example.com/HelloWorld", "hello world".toByteArray())
        // the example of the DSSE protocol specification
        assertThat(String(pae, Charsets.UTF_8)).isEqualTo("DSSEv1 29 http://example.com/HelloWorld 11 hello world")
    }

    @Test
    fun `PAE counts bytes not characters and keeps the payload verbatim`() {
        val payload = "zażółć".toByteArray(Charsets.UTF_8)
        val pae = Dsse.pae("application/vnd.in-toto+json", payload)
        val prefix = "DSSEv1 28 application/vnd.in-toto+json ${payload.size} ".toByteArray()
        assertThat(pae).startsWith(*prefix)
        assertThat(pae.copyOfRange(prefix.size, pae.size)).isEqualTo(payload)
        assertThat(payload.size).isEqualTo(10)
    }

    @Test
    fun `empty payload`() {
        assertThat(String(Dsse.pae("t", ByteArray(0)))).isEqualTo("DSSEv1 1 t 0 ")
    }

    @Test
    fun `sha256 hex and base64 decoding of both alphabets`() {
        assertThat(Digests.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(Digests.decodeBase64("-_8")).isEqualTo(byteArrayOf(-5, -1))
        assertThat(Digests.decodeBase64("+/8=")).isEqualTo(byteArrayOf(-5, -1))
    }
}
