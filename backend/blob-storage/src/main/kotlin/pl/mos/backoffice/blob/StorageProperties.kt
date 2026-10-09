package pl.mos.backoffice.blob

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `backoffice.storage.*`: where large content (artefacts > 64 kB, diffs, runner logs, uploads) lives
 * (ADR 0023, docs/guides/configuration.md "Object storage").
 */
@ConfigurationProperties(prefix = "backoffice.storage")
data class StorageProperties(
    /** `s3` for any S3-compatible service (AWS S3, MinIO, Ceph RGW, GCS interop, Cloudflare R2); `filesystem` for local/dev/tests. */
    val type: Type = Type.FILESYSTEM,
    val s3: S3 = S3(),
    val filesystem: Filesystem = Filesystem(),
) {
    enum class Type { S3, FILESYSTEM }

    /** Server-side encryption requested on every write. */
    enum class Sse { NONE, S3, KMS }

    data class S3(
        /** Custom endpoint (MinIO, Ceph, GCS `https://storage.googleapis.com`, R2); blank = AWS regional endpoint. */
        val endpoint: String = "",
        /** Endpoint written into presigned URLs when clients reach the store under another name (e.g. in-cluster MinIO); blank = [endpoint]. */
        val presignEndpoint: String = "",
        val region: String = "us-east-1",
        val bucket: String = "",
        /** Path-style addressing (`https://host/bucket/key`); needed by MinIO and most self-hosted stores. */
        val pathStyle: Boolean = false,
        /** Static credentials; blank = the AWS default chain (env, profile, IRSA / pod identity, instance role). */
        val accessKey: String = "",
        val secretKey: String = "",
        val sse: Sse = Sse.NONE,
        /** KMS key id or ARN for `sse=KMS`; blank = the bucket's / account's default KMS key. */
        val sseKmsKeyId: String = "",
        /** Streams larger than this (or of unknown size) are uploaded as multipart. */
        val multipartThreshold: Long = 16L * 1024 * 1024,
        /** Multipart part size (S3 minimum 5 MiB). */
        val partSize: Long = 8L * 1024 * 1024,
        /** Create the bucket at startup when it does not exist (dev and MinIO installs; keep false on managed S3). */
        val createBucket: Boolean = false,
        val connectionTimeout: Duration = Duration.ofSeconds(5),
        val socketTimeout: Duration = Duration.ofSeconds(60),
        val maxConnections: Int = 50,
    )

    data class Filesystem(
        /** Root directory; one file per key. Only for a single replica (local dev, tests, one-VM installs). */
        val root: String = System.getProperty("java.io.tmpdir") + "/back-office/blobs",
        /** Base URL of presigned links (served under `/blobs/` by this API); blank = `backoffice.public-url`. */
        val baseUrl: String = "",
        /** HMAC key (any string, ideally 32+ random bytes) signing presigned links; blank = random per start. */
        val signingKey: String = "",
    )
}
