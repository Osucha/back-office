package pl.mos.backoffice.blob

import org.slf4j.LoggerFactory
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.platform.BlobRef
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.PresignedUrl
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration

/**
 * [BlobStore] on any S3-compatible service through the AWS SDK for Java v2 (sync client). Streams above
 * [multipartThreshold] or of unknown size (`sizeBytes < 0`) are uploaded in [partSize] parts, so memory
 * stays bounded by one part. Every write requests the configured server-side encryption. The SHA-256 of
 * the content is computed while uploading and returned in [BlobRef]; for byte-array writes it is also
 * stored as object metadata `x-amz-meta-sha256`.
 */
class S3BlobStore(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    val bucket: String,
    private val sse: StorageProperties.Sse = StorageProperties.Sse.NONE,
    private val sseKmsKeyId: String? = null,
    private val multipartThreshold: Long = 16L * 1024 * 1024,
    private val partSize: Long = 8L * 1024 * 1024,
) : BlobStore {

    init {
        require(partSize >= MIN_PART_SIZE) { "backoffice.storage.s3.part-size must be at least 5 MiB" }
    }

    override fun put(key: String, contentType: String, content: ByteArray): BlobRef = s3Call("put", key) {
        BlobKeys.validate(key)
        val sha = Hashing.sha256Hex(content)
        s3.putObject(putRequest(key, contentType, content.size.toLong(), sha), RequestBody.fromBytes(content))
        BlobRef(key, content.size.toLong(), contentType, sha)
    }

    override fun put(key: String, contentType: String, content: InputStream, sizeBytes: Long): BlobRef = s3Call("put", key) {
        BlobKeys.validate(key)
        val digest = MessageDigest.getInstance("SHA-256")
        val input = DigestInputStream(content, digest)
        val written = input.use {
            if (sizeBytes in 0..multipartThreshold) {
                s3.putObject(putRequest(key, contentType, sizeBytes, null), RequestBody.fromInputStream(it, sizeBytes))
                sizeBytes
            } else {
                multipart(key, contentType, it)
            }
        }
        BlobRef(key, written, contentType, Hashing.hex(digest.digest()))
    }

    private fun multipart(key: String, contentType: String, input: InputStream): Long {
        val create = CreateMultipartUploadRequest.builder().bucket(bucket).key(key).contentType(contentType)
        when (sse) {
            StorageProperties.Sse.NONE -> Unit
            StorageProperties.Sse.S3 -> create.serverSideEncryption(ServerSideEncryption.AES256)
            StorageProperties.Sse.KMS -> create.serverSideEncryption(ServerSideEncryption.AWS_KMS).also { b -> sseKmsKeyId?.let { b.ssekmsKeyId(it) } }
        }
        val uploadId = s3.createMultipartUpload(create.build()).uploadId()
        try {
            val parts = mutableListOf<CompletedPart>()
            val buffer = ByteArray(partSize.toInt())
            var total = 0L
            var partNumber = 1
            while (true) {
                val n = input.readNBytes(buffer, 0, buffer.size)
                if (n == 0 && partNumber > 1) break
                val number = partNumber
                val response = s3.uploadPart(
                    { it.bucket(bucket).key(key).uploadId(uploadId).partNumber(number).contentLength(n.toLong()) },
                    RequestBody.fromByteBuffer(ByteBuffer.wrap(buffer, 0, n)),
                )
                parts += CompletedPart.builder().partNumber(number).eTag(response.eTag()).build()
                total += n
                partNumber++
                if (n < buffer.size) break
            }
            s3.completeMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId).multipartUpload { m -> m.parts(parts) } }
            return total
        } catch (e: Exception) {
            runCatching { s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId) } }
                .onFailure { log.warn("abort of multipart upload failed key={} uploadId={} error={}", key, uploadId, it.toString()) }
            throw e
        }
    }

    override fun open(key: String): InputStream? = s3Call("get", key) {
        BlobKeys.validate(key)
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override fun readText(key: String, maxBytes: Int): String? = open(key)?.let { readBounded(it, maxBytes, key) }

    override fun presignPut(key: String, contentType: String, sizeBytes: Long, ttl: Duration): PresignedUrl {
        BlobKeys.validate(key)
        val request = putRequest(key, contentType, sizeBytes.takeIf { it >= 0 }, null)
        val presigned = presigner.presignPutObject { it.signatureDuration(ttl).putObjectRequest(request) }
        return PresignedUrl(presigned.url().toString(), "PUT", flatten(presigned.signedHeaders()), presigned.expiration())
    }

    override fun presignGet(key: String, ttl: Duration): PresignedUrl {
        BlobKeys.validate(key)
        val presigned = presigner.presignGetObject { it.signatureDuration(ttl).getObjectRequest { r -> r.bucket(bucket).key(key) } }
        return PresignedUrl(presigned.url().toString(), "GET", flatten(presigned.signedHeaders()), presigned.expiration())
    }

    override fun delete(key: String) = s3Call("delete", key) {
        BlobKeys.validate(key)
        s3.deleteObject { it.bucket(bucket).key(key) }
        Unit
    }

    /**
     * Lists the prefix page by page and removes each page with one DeleteObjects call; stores without
     * multi-object delete (GCS XML interop) fall back to single deletes.
     */
    override fun deletePrefix(prefix: String): Int = s3Call("deletePrefix", prefix) {
        BlobKeys.validatePrefix(prefix)
        var deleted = 0
        s3.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }.forEach { page ->
            val keys = page.contents().map { it.key() }
            if (keys.isEmpty()) return@forEach
            deleted += try {
                val response = s3.deleteObjects { it.bucket(bucket).delete(Delete.builder().objects(keys.map { k -> ObjectIdentifier.builder().key(k).build() }).quiet(true).build()) }
                if (response.hasErrors() && response.errors().isNotEmpty()) {
                    throw IntegrationException("blob-storage", "delete of ${response.errors().size} objects under $prefix failed: ${response.errors().first().message()}")
                }
                keys.size
            } catch (e: S3Exception) {
                if (e.statusCode() != HTTP_NOT_IMPLEMENTED && e.statusCode() != HTTP_BAD_REQUEST) throw e
                keys.forEach { k -> s3.deleteObject { it.bucket(bucket).key(k) } }
                keys.size
            }
        }
        deleted
    }

    /** Health probe: the bucket exists and the credentials may access it. */
    fun probe(timeout: Duration = Duration.ofSeconds(3)) {
        s3.headBucket { it.bucket(bucket).overrideConfiguration { c -> c.apiCallTimeout(timeout) } }
    }

    fun ensureBucket() {
        try {
            s3.headBucket { it.bucket(bucket) }
        } catch (e: NoSuchBucketException) {
            log.info("creating bucket {}", bucket)
            s3.createBucket { it.bucket(bucket) }
        } catch (e: S3Exception) {
            if (e.statusCode() != HTTP_NOT_FOUND) throw e
            log.info("creating bucket {}", bucket)
            s3.createBucket { it.bucket(bucket) }
        }
    }

    private fun putRequest(key: String, contentType: String, contentLength: Long?, sha256: String?): PutObjectRequest {
        val builder = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
        contentLength?.let { builder.contentLength(it) }
        sha256?.let { builder.metadata(mapOf("sha256" to it)) }
        when (sse) {
            StorageProperties.Sse.NONE -> Unit
            StorageProperties.Sse.S3 -> builder.serverSideEncryption(ServerSideEncryption.AES256)
            StorageProperties.Sse.KMS -> {
                builder.serverSideEncryption(ServerSideEncryption.AWS_KMS)
                sseKmsKeyId?.let { builder.ssekmsKeyId(it) }
            }
        }
        return builder.build()
    }

    /** Headers the client must send with a presigned request (`host` is implied by the URL). */
    private fun flatten(headers: Map<String, List<String>>): Map<String, String> =
        headers.filterKeys { !it.equals("host", ignoreCase = true) }.mapValues { it.value.joinToString(",") }

    private inline fun <T> s3Call(operation: String, key: String, block: () -> T): T = try {
        block()
    } catch (e: SdkException) {
        val retryable = e.retryable() || (e is S3Exception && (e.statusCode() >= HTTP_SERVER_ERROR || e.statusCode() == HTTP_TOO_MANY_REQUESTS))
        throw IntegrationException("blob-storage", "$operation $key failed: ${e.message}", e, retryable)
    }

    companion object {
        private val log = LoggerFactory.getLogger(S3BlobStore::class.java)
        const val MIN_PART_SIZE = 5L * 1024 * 1024
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_NOT_IMPLEMENTED = 501
    }
}
