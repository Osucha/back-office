package pl.mos.backoffice.blob

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.mos.backoffice.blob.adapter.`in`.web.FilesystemBlobController
import pl.mos.backoffice.shared.Clock
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Wires the [pl.mos.backoffice.shared.platform.BlobStore] selected by `backoffice.storage.type`
 * (`filesystem` by default, `s3` in clusters) plus a `blobStore` health indicator.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties::class)
class BlobStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "backoffice.storage", name = ["type"], havingValue = "s3")
    class S3StoreConfiguration {

        @Bean(destroyMethod = "close")
        fun blobS3Client(properties: StorageProperties): S3Client = S3Clients.client(properties.s3)

        @Bean(destroyMethod = "close")
        fun blobS3Presigner(properties: StorageProperties): S3Presigner = S3Clients.presigner(properties.s3)

        @Bean
        fun s3BlobStore(client: S3Client, presigner: S3Presigner, properties: StorageProperties): S3BlobStore {
            val s3 = properties.s3
            require(s3.bucket.isNotBlank()) { "backoffice.storage.s3.bucket is required when backoffice.storage.type=s3" }
            val store = S3BlobStore(client, presigner, s3.bucket, s3.sse, s3.sseKmsKeyId.ifBlank { null }, s3.multipartThreshold, s3.partSize)
            if (s3.createBucket) store.ensureBucket()
            return store
        }

        @Bean("blobStoreHealthIndicator")
        fun s3BlobStoreHealth(store: S3BlobStore): AbstractHealthIndicator = object : AbstractHealthIndicator("blob store health check failed") {
            override fun doHealthCheck(builder: Health.Builder) {
                store.probe()
                builder.up().withDetail("type", "s3").withDetail("bucket", store.bucket)
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "backoffice.storage", name = ["type"], havingValue = "filesystem", matchIfMissing = true)
    class FilesystemStoreConfiguration {

        @Bean
        fun filesystemBlobStore(properties: StorageProperties, clock: ObjectProvider<Clock>, @Value("\${backoffice.public-url:http://localhost:8080}") publicUrl: String): FilesystemBlobStore {
            val fs = properties.filesystem
            val key = fs.signingKey.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                ?: ByteArray(SIGNING_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val effectiveClock = clock.getIfAvailable { Clock.SYSTEM }
            val store = FilesystemBlobStore(Path.of(fs.root), BlobUrlSigner(fs.baseUrl.ifBlank { publicUrl }, key, effectiveClock), effectiveClock)
            log.info("blob store: filesystem root={} (single replica only; use backoffice.storage.type=s3 in clusters)", store.root)
            return store
        }

        @Bean fun filesystemBlobController(store: FilesystemBlobStore) = FilesystemBlobController(store)

        @Bean("blobStoreHealthIndicator")
        fun filesystemBlobStoreHealth(store: FilesystemBlobStore): AbstractHealthIndicator = object : AbstractHealthIndicator("blob store health check failed") {
            override fun doHealthCheck(builder: Health.Builder) {
                val root = store.root
                if (java.nio.file.Files.isDirectory(root) && java.nio.file.Files.isWritable(root)) {
                    builder.up().withDetail("type", "filesystem")
                } else {
                    builder.down().withDetail("type", "filesystem").withDetail("reason", "root is not a writable directory")
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BlobStorageConfiguration::class.java)
        private const val SIGNING_KEY_BYTES = 32
    }
}

/** Builds S3 clients for AWS and S3-compatible stores (custom endpoint, path style, relaxed checksums). */
object S3Clients {

    fun client(p: StorageProperties.S3): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(p.region))
            .credentialsProvider(credentials(p))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle).build())
            // Only compute/validate the new default CRC checksums where S3 requires them: several
            // S3-compatible stores (older MinIO/Ceph, GCS interop, R2) reject or ignore them.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .connectionTimeout(p.connectionTimeout)
                    .socketTimeout(p.socketTimeout)
                    .maxConnections(p.maxConnections),
            )
        if (p.endpoint.isNotBlank()) builder.endpointOverride(URI.create(p.endpoint))
        return builder.build()
    }

    fun presigner(p: StorageProperties.S3): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(p.region))
            .credentialsProvider(credentials(p))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle).build())
        val endpoint = p.presignEndpoint.ifBlank { p.endpoint }
        if (endpoint.isNotBlank()) builder.endpointOverride(URI.create(endpoint))
        return builder.build()
    }

    private fun credentials(p: StorageProperties.S3): AwsCredentialsProvider =
        if (p.accessKey.isNotBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey, p.secretKey))
        } else {
            DefaultCredentialsProvider.builder().build()
        }
}
