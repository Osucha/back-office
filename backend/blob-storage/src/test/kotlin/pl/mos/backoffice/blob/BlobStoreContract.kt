package pl.mos.backoffice.blob

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.BlobStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/** Behaviour every [BlobStore] must have; run against the filesystem store and MinIO. */
abstract class BlobStoreContract {

    abstract val store: BlobStore

    /** Base prefix unique per test so parallel tests and reruns do not interfere. */
    protected val base = "test/${UUID.randomUUID()}"

    protected val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `put and read bytes with size and sha256`() {
        val bytes = "hello blob".toByteArray()
        val ref = store.put("$base/a.txt", "text/plain", bytes)

        assertThat(ref.key).isEqualTo("$base/a.txt")
        assertThat(ref.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(ref.sha256).isEqualTo(sha256(bytes))
        assertThat(store.readText("$base/a.txt")).isEqualTo("hello blob")
        assertThat(store.open("$base/a.txt")!!.use { it.readAllBytes() }).isEqualTo(bytes)
    }

    @Test
    fun `missing keys read as null and delete is idempotent`() {
        assertThat(store.open("$base/missing")).isNull()
        assertThat(store.readText("$base/missing")).isNull()
        store.delete("$base/missing")
    }

    @Test
    fun `overwrite replaces content`() {
        store.put("$base/o", "text/plain", "one".toByteArray())
        store.put("$base/o", "text/plain", "two".toByteArray())
        assertThat(store.readText("$base/o")).isEqualTo("two")
    }

    @Test
    fun `stream upload of known size`() {
        val bytes = Random(1).nextBytes(300_000)
        val ref = store.put("$base/stream.bin", "application/octet-stream", ByteArrayInputStream(bytes), bytes.size.toLong())
        assertThat(ref.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(ref.sha256).isEqualTo(sha256(bytes))
        assertThat(store.open("$base/stream.bin")!!.use { it.readAllBytes() }).isEqualTo(bytes)
    }

    @Test
    fun `stream upload of unknown size larger than one part`() {
        val size = 11 * 1024 * 1024 + 123
        val bytes = Random(2).nextBytes(size)
        val ref = store.put("$base/big.bin", "application/octet-stream", ByteArrayInputStream(bytes), -1)
        assertThat(ref.sizeBytes).isEqualTo(size.toLong())
        assertThat(ref.sha256).isEqualTo(sha256(bytes))
        assertThat(sha256(store.open("$base/big.bin")!!.use(InputStream::readAllBytes))).isEqualTo(sha256(bytes))
    }

    @Test
    fun `readText refuses objects above maxBytes`() {
        store.put("$base/long.txt", "text/plain", "0123456789".toByteArray())
        assertThat(store.readText("$base/long.txt", maxBytes = 10)).isEqualTo("0123456789")
        assertThatThrownBy { store.readText("$base/long.txt", maxBytes = 5) }.isInstanceOf(IntegrationException::class.java)
    }

    @Test
    fun `deletePrefix removes only keys under the prefix`() {
        store.put("$base/runs/r1/artifacts/a", "text/plain", "a".toByteArray())
        store.put("$base/runs/r1/artifacts/b", "text/plain", "b".toByteArray())
        store.put("$base/runs/r1/logs/x", "text/plain", "x".toByteArray())
        store.put("$base/runs/r10/artifacts/c", "text/plain", "c".toByteArray())

        assertThat(store.deletePrefix("$base/runs/r1/")).isEqualTo(3)

        assertThat(store.open("$base/runs/r1/artifacts/a")).isNull()
        assertThat(store.readText("$base/runs/r10/artifacts/c")).isEqualTo("c")
        assertThat(store.deletePrefix("$base/runs/r1/")).isEqualTo(0)
    }

    @Test
    fun `unsafe keys are rejected`() {
        listOf("../etc/passwd", "/abs", "a//b", "a/../b", "", "a/b/", "sp ace").forEach { key ->
            assertThatThrownBy { store.put(key, "text/plain", byteArrayOf(1)) }.isInstanceOf(ValidationException::class.java)
        }
        assertThatThrownBy { store.deletePrefix("") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `presigned put and get round trip over http`() {
        val key = "$base/presigned/report.json"
        val upload = store.presignPut(key, "application/json", 13, Duration.ofMinutes(5))
        assertThat(upload.method).isEqualTo("PUT")
        val put = HttpRequest.newBuilder(URI.create(upload.url)).PUT(HttpRequest.BodyPublishers.ofString("{\"ok\": true }"))
        upload.headers.filterKeys { !it.equals("content-length", ignoreCase = true) }.forEach { (k, v) -> put.header(k, v) }
        assertThat(http.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200)

        val download = store.presignGet(key, Duration.ofMinutes(5))
        val response = http.send(HttpRequest.newBuilder(URI.create(download.url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("{\"ok\": true }")
        assertThat(download.expiresAt).isAfter(java.time.Instant.now())
    }

    @Test
    fun `tampered presigned url is refused`() {
        val key = "$base/presigned/secret.txt"
        store.put(key, "text/plain", "secret".toByteArray())
        val url = store.presignGet(key, Duration.ofMinutes(5)).url.replace("secret.txt", "other.txt")
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(403)
    }

    protected fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
