package pl.mos.backoffice.blob

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.MinIOContainer
import pl.mos.backoffice.shared.platform.BlobStore
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.io.ByteArrayInputStream
import kotlin.random.Random

/** [S3BlobStore] against a real MinIO (path-style, custom endpoint, multipart, presigning, SSE-S3). */
@Testcontainers
class S3BlobStoreMinioTest : BlobStoreContract() {

    private val properties = StorageProperties.S3(
        endpoint = minio.s3URL, region = "us-east-1", bucket = BUCKET, pathStyle = true,
        accessKey = minio.userName, secretKey = minio.password,
        multipartThreshold = S3BlobStore.MIN_PART_SIZE, partSize = S3BlobStore.MIN_PART_SIZE, createBucket = true,
    )

    override val store: BlobStore by lazy { newStore(properties) }

    private fun newStore(p: StorageProperties.S3): S3BlobStore =
        S3BlobStore(client, S3Clients.presigner(p), p.bucket, p.sse, p.sseKmsKeyId.ifBlank { null }, p.multipartThreshold, p.partSize).also { it.ensureBucket() }

    @Test
    fun `sse-s3 is requested on single and multipart writes`() {
        val encrypted = newStore(properties.copy(sse = StorageProperties.Sse.S3))
        encrypted.put("$base/enc/small", "text/plain", "secret".toByteArray())
        val big = Random(3).nextBytes(6 * 1024 * 1024)
        encrypted.put("$base/enc/big", "application/octet-stream", ByteArrayInputStream(big), -1)

        assertThat(client.headObject { it.bucket(BUCKET).key("$base/enc/small") }.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256)
        assertThat(client.headObject { it.bucket(BUCKET).key("$base/enc/big") }.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256)
        assertThat(encrypted.readText("$base/enc/small")).isEqualTo("secret")
    }

    @Test
    fun `byte writes record the sha256 as object metadata`() {
        val ref = store.put("$base/meta", "text/plain", "abc".toByteArray())
        assertThat(client.headObject { it.bucket(BUCKET).key("$base/meta") }.metadata()["sha256"]).isEqualTo(ref.sha256)
    }

    @Test
    fun `type s3 wires the store and an UP health indicator`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
            .withUserConfiguration(BlobStorageConfiguration::class.java)
            .withPropertyValues(
                "backoffice.storage.type=s3",
                "backoffice.storage.s3.endpoint=${minio.s3URL}",
                "backoffice.storage.s3.bucket=wired-bucket",
                "backoffice.storage.s3.path-style=true",
                "backoffice.storage.s3.create-bucket=true",
                "backoffice.storage.s3.access-key=${minio.userName}",
                "backoffice.storage.s3.secret-key=${minio.password}",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(BlobStore::class.java)).isInstanceOf(S3BlobStore::class.java)
                val health = context.getBean("blobStoreHealthIndicator", HealthIndicator::class.java).health(true)!!
                assertThat(health.status).isEqualTo(Status.UP)
                assertThat(health.details["bucket"]).isEqualTo("wired-bucket")
            }
    }

    @Test
    fun `health is DOWN when the bucket is missing`() {
        val missing = newStoreWithoutBucket("does-not-exist")
        val result = runCatching { missing.probe() }
        assertThat(result.isFailure).isTrue()
    }

    private fun newStoreWithoutBucket(bucket: String) = S3BlobStore(client, S3Clients.presigner(properties), bucket)

    companion object {
        private const val BUCKET = "backoffice-test"

        /** minio/minio is no longer published on Docker Hub; the Chainguard build is a drop-in (override with -Dminio.image). */
        private val MINIO_IMAGE: String = System.getProperty("minio.image") ?: "cgr.dev/chainguard/minio:latest"

        @Container
        @JvmStatic
        val minio: MinIOContainer = MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"))
            // enables SSE-S3 with a static key (MinIO KMS "secret key" mode)
            .withEnv("MINIO_KMS_SECRET_KEY", "test-key:bXktbWluaW8tdGVzdC1rZXktMzItYnl0ZXMtbG9uZyE=")

        private val client: S3Client by lazy {
            S3Clients.client(StorageProperties.S3(endpoint = minio.s3URL, bucket = BUCKET, pathStyle = true, accessKey = minio.userName, secretKey = minio.password))
        }

        @AfterAll
        @JvmStatic
        fun close() {
            client.close()
        }
    }
}
