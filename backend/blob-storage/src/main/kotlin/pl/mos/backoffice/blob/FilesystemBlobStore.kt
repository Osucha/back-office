package pl.mos.backoffice.blob

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.platform.BlobRef
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.PresignedUrl
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Properties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * [BlobStore] on a local directory for development, tests and single-VM installs: one file per key under
 * [root], a `<file>.bo-meta` sidecar with content type and SHA-256, atomic writes (temp file + move).
 * Presigned URLs point at `/blobs/{key}` on this API ([signer]) and are verified by
 * `FilesystemBlobController` (HMAC over method, key and expiry). Not for several replicas.
 */
class FilesystemBlobStore(
    root: Path,
    val signer: BlobUrlSigner,
    private val clock: Clock = Clock.SYSTEM,
) : BlobStore {

    val root: Path = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.root)
    }

    override fun put(key: String, contentType: String, content: ByteArray): BlobRef = put(key, contentType, content.inputStream(), content.size.toLong())

    override fun put(key: String, contentType: String, content: InputStream, sizeBytes: Long): BlobRef = io("put", key) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, UPLOAD_PREFIX, ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val written = DigestInputStream(content, digest).use { input -> Files.newOutputStream(temp).use { input.transferTo(it) } }
            if (sizeBytes >= 0 && written != sizeBytes) throw IntegrationException("blob-storage", "put $key: expected $sizeBytes bytes, got $written")
            val sha = Hashing.hex(digest.digest())
            writeMeta(target, contentType, sha)
            move(temp, target)
            BlobRef(key, written, contentType, sha)
        } finally {
            temp.deleteIfExists()
        }
    }

    override fun open(key: String): InputStream? = io("get", key) {
        try {
            Files.newInputStream(resolve(key))
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override fun readText(key: String, maxBytes: Int): String? = open(key)?.let { readBounded(it, maxBytes, key) }

    /** Content type and SHA-256 recorded at write time; null when the key does not exist. */
    fun stat(key: String): BlobRef? = io("stat", key) {
        val file = resolve(key)
        if (!file.isRegularFile()) return@io null
        val meta = Properties()
        metaFile(file).takeIf { it.exists() }?.let { m -> Files.newInputStream(m).use { meta.load(it) } }
        BlobRef(key, Files.size(file), meta.getProperty("contentType", "application/octet-stream"), meta.getProperty("sha256"))
    }

    override fun presignPut(key: String, contentType: String, sizeBytes: Long, ttl: Duration): PresignedUrl {
        BlobKeys.validate(key)
        val expires = clock.now().plus(ttl)
        return PresignedUrl(signer.url("PUT", key, expires), "PUT", mapOf("Content-Type" to contentType), expires)
    }

    override fun presignGet(key: String, ttl: Duration): PresignedUrl {
        BlobKeys.validate(key)
        val expires = clock.now().plus(ttl)
        return PresignedUrl(signer.url("GET", key, expires), "GET", emptyMap(), expires)
    }

    override fun delete(key: String) = io("delete", key) {
        val file = resolve(key)
        file.deleteIfExists()
        metaFile(file).deleteIfExists()
        pruneEmptyParents(file.parent)
    }

    override fun deletePrefix(prefix: String): Int = io("deletePrefix", prefix) {
        BlobKeys.validatePrefix(prefix)
        // the deepest existing directory that can contain matching keys
        val dirPart = prefix.substringBeforeLast('/', "")
        val start = if (dirPart.isEmpty()) root else root.resolve(dirPart).normalize()
        if (!start.startsWith(root) || !start.isDirectory()) return@io 0
        val matches = Files.walk(start).use { stream ->
            stream.filter { it.isRegularFile() && !it.fileName.toString().endsWith(META_SUFFIX) && !it.fileName.toString().startsWith(UPLOAD_PREFIX) }
                .filter { root.relativize(it).joinToString("/").startsWith(prefix) }
                .toList()
        }
        matches.forEach { file ->
            file.deleteIfExists()
            metaFile(file).deleteIfExists()
        }
        matches.map { it.parent }.distinct().forEach(::pruneEmptyParents)
        matches.size
    }

    private fun resolve(key: String): Path {
        BlobKeys.validate(key)
        if (key.endsWith(META_SUFFIX)) throw pl.mos.backoffice.shared.ValidationException("blob keys must not end with $META_SUFFIX", "key")
        val path = root.resolve(key).normalize()
        if (!path.startsWith(root) || path == root) throw pl.mos.backoffice.shared.ValidationException("invalid blob key '$key'", "key")
        return path
    }

    private fun metaFile(file: Path): Path = file.resolveSibling(file.fileName.toString() + META_SUFFIX)

    private fun writeMeta(target: Path, contentType: String, sha: String) {
        val meta = Properties()
        meta.setProperty("contentType", contentType)
        meta.setProperty("sha256", sha)
        val temp = Files.createTempFile(target.parent, UPLOAD_PREFIX, ".meta")
        try {
            Files.newOutputStream(temp).use { meta.store(it, null) }
            move(temp, metaFile(target))
        } finally {
            temp.deleteIfExists()
        }
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pruneEmptyParents(dir: Path?) {
        var current = dir
        while (current != null && current != root && current.startsWith(root)) {
            val empty = runCatching { Files.list(current).use { it.findFirst().isEmpty } }.getOrDefault(false)
            if (!empty || !runCatching { Files.deleteIfExists(current) }.getOrDefault(false)) return
            current = current.parent
        }
    }

    private inline fun <T> io(operation: String, key: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw IntegrationException("blob-storage", "$operation $key failed: $e", e, retryable = true)
    }

    companion object {
        const val META_SUFFIX = ".bo-meta"

        /** Prefix of the temporary files an upload writes before the atomic move. */
        private const val UPLOAD_PREFIX = ".upload-"
    }
}

/**
 * Signs and verifies filesystem-store links `<baseUrl>/blobs/<key>?method=GET&expires=<epochSeconds>&signature=<hex>`
 * with HMAC-SHA256 over `method\nkey\nexpires`.
 */
class BlobUrlSigner(baseUrl: String, key: ByteArray, private val clock: Clock = Clock.SYSTEM) {
    private val base = baseUrl.trimEnd('/')
    private val secret = SecretKeySpec(key, "HmacSHA256")

    fun url(method: String, blobKey: String, expires: Instant): String {
        val epoch = expires.epochSecond
        val path = blobKey.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        return "$base$PATH_PREFIX$path?method=$method&expires=$epoch&signature=${sign(method, blobKey, epoch)}"
    }

    /** True when [signature] matches and [expires] (epoch seconds) is in the future. */
    fun verify(method: String, blobKey: String, expires: Long, signature: String): Boolean {
        if (Instant.ofEpochSecond(expires).isBefore(clock.now())) return false
        return MessageDigest.isEqual(sign(method, blobKey, expires).toByteArray(), signature.lowercase().toByteArray())
    }

    private fun sign(method: String, blobKey: String, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secret)
        return Hashing.hex(mac.doFinal("${method.uppercase()}\n$blobKey\n$expires".toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val PATH_PREFIX = "/blobs/"
    }
}
