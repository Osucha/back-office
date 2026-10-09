package pl.mos.backoffice.runner

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.platform.BlobRef
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.DsseSignature
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import pl.mos.backoffice.shared.platform.PresignedUrl
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.Pricing
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal Spring Boot application for agent-runner integration tests. */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.runner"])
class RunnerTestApplication

/** A clock the tests move forward (lease expiry, queue timeout). */
class MutableClock(@Volatile var instant: Instant = Instant.parse("2026-09-28T10:00:00Z")) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant = instant.plus(duration) }
}

/** Catalogue fake with pricing keyed by `provider/modelId`. */
class FakeModelCatalog : ModelCatalog {
    val pricing = ConcurrentHashMap<String, Pricing>()
    override fun find(id: ModelProfileId): ModelProfileSnapshot? = null
    override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
    override fun findPricing(ref: ModelRef): Pricing? = pricing[ref.label]
}

/** Test double of the scale-out work queue: records what would be enqueued. */
class RecordingWorkQueue : WorkQueue {
    val requests = CopyOnWriteArrayList<WorkRequest>()
    override fun enqueue(request: WorkRequest) { requests += request }
}

/** Deterministic "signature" (base64 of the payload) standing in for the governance module's Ed25519 signer. */
class FakeEnvelopeSigner : EnvelopeSigner {
    override fun signEnvelope(payloadType: String, payload: ByteArray) =
        DsseEnvelope(payloadType, Base64.getEncoder().encodeToString(payload), listOf(sign(payload)))
    override fun sign(bytes: ByteArray) = DsseSignature("test-key", Base64.getEncoder().encodeToString(bytes))
    override fun verify(envelope: DsseEnvelope) = true
    override fun verify(bytes: ByteArray, signature: DsseSignature) = sign(bytes).signature == signature.signature
    override fun publicJwks(): List<Map<String, Any?>> = listOf(mapOf("kty" to "OKP", "crv" to "Ed25519", "kid" to "test-key", "x" to "AAAA"))
}

/** In-memory BlobStore with fake presigned URLs. */
class InMemoryBlobStore : BlobStore {
    val objects = ConcurrentHashMap<String, ByteArray>()
    override fun put(key: String, contentType: String, content: ByteArray): BlobRef { objects[key] = content; return BlobRef(key, content.size.toLong(), contentType, null) }
    override fun put(key: String, contentType: String, content: InputStream, sizeBytes: Long) = put(key, contentType, content.readAllBytes())
    override fun open(key: String): InputStream? = objects[key]?.inputStream()
    override fun readText(key: String, maxBytes: Int): String? = objects[key]?.let { String(it) }
    override fun presignPut(key: String, contentType: String, sizeBytes: Long, ttl: Duration) =
        PresignedUrl("https://blobs.test/$key?signature=x", "PUT", mapOf("Content-Type" to contentType), Instant.parse("2026-09-28T11:00:00Z"))
    override fun presignGet(key: String, ttl: Duration) = PresignedUrl("https://blobs.test/$key", "GET", emptyMap(), Instant.parse("2026-09-28T11:00:00Z"))
    override fun delete(key: String) { objects.remove(key) }
    override fun deletePrefix(prefix: String): Int = objects.keys.filter { it.startsWith(prefix) }.onEach { objects.remove(it) }.size
}

/** The generic RFC 9457 mapping that `app` provides (ProblemDetailsHandler + AuthorizationExceptionHandler). */
@RestControllerAdvice
class TestProblemHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(InvalidStateException::class)
    fun conflict(e: InvalidStateException) = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(ValidationException::class)
    fun validation(e: ValidationException) = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(InsufficientPermissionException::class)
    fun forbidden(e: InsufficientPermissionException) = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message).apply { setProperty("permission", e.permission) }

    @ExceptionHandler(IntegrationException::class)
    fun integration(e: IntegrationException) = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message)
}

/** Shared kernel beans normally provided by other modules, plus a throw-away PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
class RunnerTestBeans {

    val events = CopyOnWriteArrayList<DomainEvent>()

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")

    @Bean
    fun clock(): MutableClock = MutableClock()

    @Bean
    @Primary
    fun eventPublisher(): EventPublisher = EventPublisher { events += it }

    @Bean
    fun modelCatalog(): FakeModelCatalog = FakeModelCatalog()

    @Bean
    fun authorizationGate(): FakeAuthorizationGate = FakeAuthorizationGate.denyAll()

    @Bean
    fun auditPort(): RecordingAuditPort = RecordingAuditPort()

    @Bean
    fun workQueue(): RecordingWorkQueue = RecordingWorkQueue()

    @Bean
    fun envelopeSigner(): FakeEnvelopeSigner = FakeEnvelopeSigner()

    @Bean
    fun blobStore(): InMemoryBlobStore = InMemoryBlobStore()

    @Bean
    fun testProblemHandler() = TestProblemHandler()
}
