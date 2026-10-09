package pl.mos.backoffice.projects.adapter.out.persistence

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.platform.BlobStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * `backoffice.storage.artifacts.*`: artefact content above [inlineMaxBytes] goes to the BlobStore
 * (ADR 0023); [cacheMaxBytes] bounds the in-process cache of blob-backed content (keys are immutable, see
 * [ArtifactStore]).
 */
@ConfigurationProperties(prefix = "backoffice.storage.artifacts")
data class ArtifactStorageProperties(
    val inlineMaxBytes: Int = 64 * 1024,
    val cacheMaxBytes: Long = 64L * 1024 * 1024,
    /** Largest artefact read back from the BlobStore. */
    val maxReadBytes: Int = 64 * 1024 * 1024,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArtifactStorageProperties::class)
class ArtifactStorageConfiguration

/**
 * Artefact rows of a run, with large content offloaded to the [BlobStore]:
 *
 * - content up to `inline-max-bytes` stays in `artifact.content`;
 * - larger content is written to `runs/<runId>/artifacts/<artifactId>` (first version) or
 *   `…/<artifactId>.<sha256 prefix>` (edited versions), the row keeps `blob_key`, `size_bytes`, `sha256`
 *   and an empty `content`. A key is never overwritten, so a rolled-back transaction cannot corrupt the
 *   content of a committed row and content can be cached by key; superseded keys are queued in
 *   `run_blob_gc` in the same transaction and deleted by the retention job;
 * - rows whose blob was removed by retention read as [EXPIRED_NOTICE].
 *
 * Without a BlobStore bean (module tests) everything stays inline. The domain model and API are unchanged.
 */
@Component
class ArtifactStore(
    private val repo: ArtifactJpaRepository,
    private val mapper: PersistenceMapper,
    private val jdbc: JdbcClient,
    blobStore: ObjectProvider<BlobStore>,
    private val properties: ArtifactStorageProperties,
) {
    private val blobs: BlobStore? = blobStore.ifAvailable

    private val cache: Cache<String, String> = Caffeine.newBuilder()
        .maximumWeight(properties.cacheMaxBytes)
        .weigher<String, String> { _, value -> value.length * 2 }
        .build()

    val blobStoreEnabled: Boolean get() = blobs != null

    fun load(runId: UUID): List<Artifact> = repo.findByRunIdOrderByCreatedAtAsc(runId).map { mapper.toDomain(it, content(it)) }

    /** Inserts new artefacts and updates changed ones; unchanged rows (compared by content or sha256) are not touched. */
    fun sync(run: WorkflowRun) {
        val existing = repo.findByRunIdOrderByCreatedAtAsc(run.id.value).associateBy { it.id }
        run.artifacts.forEach { artifact ->
            val current = existing[artifact.id.value]
            if (current == null) {
                val entity = mapper.toEntity(artifact, run.id)
                store(entity, artifact.content, primaryKey(run.id.value, artifact.id.value))
                repo.save(entity)
            } else if (!unchanged(current, artifact.content)) {
                val previousKey = current.blobKey
                store(current, artifact.content, null)
                current.contentExpiredAt = null
                repo.save(current)
                if (previousKey != null && previousKey != current.blobKey) enqueueGc(previousKey)
            }
        }
    }

    /** Deletes the rows and queues the runs' blob prefixes for deletion after commit (retention job). */
    fun deleteByRunIds(runIds: Collection<UUID>) {
        if (runIds.isEmpty()) return
        if (blobs != null) runIds.forEach { enqueueGc(runPrefix(it)) }
        repo.deleteByRunIdIn(runIds)
    }

    /**
     * Moves one legacy row (content above the inline limit, written before V103) to the BlobStore.
     * Returns false when the row is gone or already migrated.
     */
    fun migrateInline(artifactId: UUID): Boolean {
        val entity = repo.findById(artifactId).orElse(null) ?: return false
        if (blobs == null || entity.blobKey != null || entity.contentExpiredAt != null) return false
        if (entity.content.toByteArray(Charsets.UTF_8).size <= properties.inlineMaxBytes) {
            entity.sizeBytes = entity.content.toByteArray(Charsets.UTF_8).size.toLong()
            repo.save(entity)
            return false
        }
        store(entity, entity.content, primaryKey(entity.runId, entity.id))
        repo.save(entity)
        return true
    }

    fun content(entity: ArtifactEntity): String {
        if (entity.contentExpiredAt != null && entity.blobKey == null) return EXPIRED_NOTICE
        val key = entity.blobKey ?: return entity.content
        val store = blobs ?: throw IllegalStateException("artifact ${entity.id} is stored in the BlobStore but no BlobStore is configured")
        cache.getIfPresent(key)?.let { return it }
        val text = store.readText(key, properties.maxReadBytes)
        if (text == null) {
            log.warn("artifact blob missing artifactId={} key={}", entity.id, key)
            return MISSING_NOTICE
        }
        cache.put(key, text)
        return text
    }

    fun evictCached(key: String) = cache.invalidate(key)

    private fun unchanged(current: ArtifactEntity, content: String): Boolean = when {
        current.contentExpiredAt != null && current.blobKey == null -> content == EXPIRED_NOTICE || content == current.content
        current.blobKey != null -> sha256(content.toByteArray(Charsets.UTF_8)) == current.sha256
        else -> current.content == content
    }

    /** Sets content / blob columns of [entity] for [content]; [preferredKey] null = versioned key. */
    private fun store(entity: ArtifactEntity, content: String, preferredKey: String?) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        entity.sizeBytes = bytes.size.toLong()
        val store = blobs
        if (store == null || bytes.size <= properties.inlineMaxBytes) {
            entity.content = content
            entity.blobKey = null
            entity.sha256 = null
            return
        }
        val sha = sha256(bytes)
        val key = preferredKey ?: "${primaryKey(entity.runId, entity.id)}.${sha.take(VERSION_HASH_CHARS)}"
        val ref = store.put(key, entity.mediaType.ifBlank { "text/plain" } + "; charset=utf-8", bytes)
        cache.put(key, content)
        entity.content = ""
        entity.blobKey = ref.key
        entity.sha256 = ref.sha256 ?: sha
    }

    private fun enqueueGc(prefix: String) {
        jdbc.sql("INSERT INTO run_blob_gc (key_prefix, enqueued_at) VALUES (:prefix, :at) ON CONFLICT (key_prefix) DO NOTHING")
            .param("prefix", prefix).param("at", java.sql.Timestamp.from(Instant.now())).update()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ArtifactStore::class.java)
        private const val VERSION_HASH_CHARS = 12
        const val EXPIRED_NOTICE = "(content removed by the data retention policy)"
        const val MISSING_NOTICE = "(content unavailable: object missing in storage)"

        fun runPrefix(runId: UUID) = "runs/$runId/"
        fun primaryKey(runId: UUID, artifactId: UUID) = "runs/$runId/artifacts/$artifactId"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
