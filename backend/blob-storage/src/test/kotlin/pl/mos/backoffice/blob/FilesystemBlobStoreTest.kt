package pl.mos.backoffice.blob

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.platform.BlobStore
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

@SpringBootApplication
class BlobTestApplication

/** Filesystem store behind a real HTTP server, so presigned links are exercised through FilesystemBlobController. */
@SpringBootTest(classes = [BlobTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class FilesystemBlobStoreTest : BlobStoreContract() {

    @Autowired lateinit var blobStore: BlobStore
    @Autowired lateinit var filesystem: FilesystemBlobStore
    @Autowired lateinit var health: Map<String, HealthIndicator>

    override val store: BlobStore get() = blobStore

    @Test
    fun `filesystem is the default store and reports healthy`() {
        assertThat(blobStore).isInstanceOf(FilesystemBlobStore::class.java)
        assertThat(health.getValue("blobStoreHealthIndicator").health(false)!!.status).isEqualTo(Status.UP)
    }

    @Test
    fun `expired and wrong-method links are refused`() {
        val key = "$base/expiring.txt"
        store.put(key, "text/plain", "x".toByteArray())
        val expired = filesystem.signer.url("GET", key, Instant.now().minusSeconds(5))
        assertThat(get(expired).statusCode()).isEqualTo(403)
        val putLinkUsedForGet = store.presignPut(key, "text/plain", 1, Duration.ofMinutes(1)).url
        assertThat(get(putLinkUsedForGet).statusCode()).isEqualTo(403)
        assertThat(get("http://localhost:$PORT/blobs/$key").statusCode()).isEqualTo(403)
    }

    @Test
    fun `served object carries its content type and sha256 etag`() {
        val key = "$base/typed.md"
        val ref = store.put(key, "text/markdown", "# Title".toByteArray())
        val response = get(store.presignGet(key, Duration.ofMinutes(1)).url)
        assertThat(response.headers().firstValue("Content-Type").get()).startsWith("text/markdown")
        assertThat(response.headers().firstValue("ETag").get()).isEqualTo("\"${ref.sha256}\"")
    }

    @Test
    fun `signer verifies only its own signatures`() {
        val signer = BlobUrlSigner("http://x", "k1".toByteArray(), Clock.SYSTEM)
        val other = BlobUrlSigner("http://x", "k2".toByteArray(), Clock.SYSTEM)
        val url = URI.create(signer.url("GET", "a/b", Instant.now().plusSeconds(60)))
        val params = url.rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertThat(signer.verify("GET", "a/b", params.getValue("expires").toLong(), params.getValue("signature"))).isTrue()
        assertThat(other.verify("GET", "a/b", params.getValue("expires").toLong(), params.getValue("signature"))).isFalse()
        assertThat(signer.verify("GET", "a/c", params.getValue("expires").toLong(), params.getValue("signature"))).isFalse()
    }

    private fun get(url: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    companion object {
        private val PORT: Int = ServerSocket(0).use { it.localPort }
        private val ROOT = Files.createTempDirectory("blobs")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { PORT }
            registry.add("backoffice.storage.filesystem.root") { ROOT.toString() }
            registry.add("backoffice.storage.filesystem.base-url") { "http://localhost:$PORT" }
            registry.add("backoffice.storage.filesystem.signing-key") { "test-signing-key" }
        }
    }
}
