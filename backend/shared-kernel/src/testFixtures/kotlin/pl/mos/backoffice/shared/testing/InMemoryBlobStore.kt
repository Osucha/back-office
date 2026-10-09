package pl.mos.backoffice.shared.testing

import pl.mos.backoffice.shared.platform.BlobRef
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.PresignedUrl
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** [BlobStore] in a map for unit and module tests; presigned URLs are fake `mem://` links. */
class InMemoryBlobStore : BlobStore {
    data class Entry(val bytes: ByteArray, val contentType: String)

    val objects = ConcurrentHashMap<String, Entry>()

    override fun put(key: String, contentType: String, content: ByteArray): BlobRef {
        objects[key] = Entry(content.copyOf(), contentType)
        return BlobRef(key, content.size.toLong(), contentType, sha256(content))
    }

    override fun put(key: String, contentType: String, content: InputStream, sizeBytes: Long): BlobRef = put(key, contentType, content.use { it.readAllBytes() })

    override fun open(key: String): InputStream? = objects[key]?.bytes?.inputStream()

    override fun readText(key: String, maxBytes: Int): String? = objects[key]?.bytes?.let {
        check(it.size <= maxBytes) { "object $key is larger than $maxBytes bytes" }
        String(it, Charsets.UTF_8)
    }

    override fun presignPut(key: String, contentType: String, sizeBytes: Long, ttl: Duration) =
        PresignedUrl("mem://$key?put", "PUT", mapOf("Content-Type" to contentType), Instant.now().plus(ttl))

    override fun presignGet(key: String, ttl: Duration) = PresignedUrl("mem://$key", "GET", emptyMap(), Instant.now().plus(ttl))

    override fun delete(key: String) {
        objects.remove(key)
    }

    override fun deletePrefix(prefix: String): Int {
        val keys = objects.keys.filter { it.startsWith(prefix) }
        keys.forEach { objects.remove(it) }
        return keys.size
    }

    fun keys(prefix: String = ""): List<String> = objects.keys.filter { it.startsWith(prefix) }.sorted()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
