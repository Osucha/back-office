package pl.mos.backoffice.projects.adapter.out.retention

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.projects.adapter.out.persistence.ArtifactStore
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.platform.BlobStore
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `backoffice.retention.*` for data owned by the projects context (docs/architecture/09-scale-and-operations.md
 * "Storage and data lifecycle"). Durations are ISO-8601 (`P180D`) or Spring style (`180d`).
 */
@ConfigurationProperties(prefix = "backoffice.retention")
data class RetentionProperties(
    val enabled: Boolean = true,
    /** Pause between two runs of the job (each run works in bounded batches). */
    val interval: Duration = Duration.ofHours(1),
    /** Blob content of artefacts of runs finished longer ago than this is deleted; the rows stay. */
    val artifacts: Duration = Duration.ofDays(180),
    /** LangGraph checkpoints of runs finished longer ago than this are deleted. */
    val checkpoints: Duration = Duration.ofDays(30),
    /** Runner job log events older than this are deleted (table `runner_job_event`, agent-runner V50). */
    val runnerJobEvents: Duration = Duration.ofDays(30),
    /** Rows (or runs) per batch; each batch is its own transaction. */
    val batchSize: Int = 500,
    /** Upper bound of batches per step and run, so one run never monopolises the database. */
    val maxBatches: Int = 20,
    /** Legacy inline artefacts above the inline limit moved to the BlobStore per run (0 = off). */
    val migrateInlineArtifacts: Int = 200,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties::class)
class RetentionConfiguration

/**
 * Retention and storage housekeeping of the projects context, one scheduled entry point guarded by a
 * cluster-wide lock named [LOCK_NAME] (PostgreSQL advisory lock today; with ShedLock put
 * `@SchedulerLock(name = DataRetentionJobs.LOCK_NAME)` on [run]). Steps, each in bounded batches and
 * independent of the others' failures:
 *
 * 1. `run_blob_gc`: delete queued blob keys/prefixes (deleted runs, superseded artefact versions);
 * 2. artefact blobs of runs finished before `now - artifacts`: delete `runs/<runId>/…`, mark rows expired;
 * 3. legacy inline artefacts above the inline limit: move to the BlobStore (one-off migration, V103);
 * 4. LangGraph checkpoints (`lg4j*` tables) of runs finished before `now - checkpoints`, and of threads
 *    whose run no longer exists;
 * 5. `runner_job_event` rows older than `now - runner-job-events`.
 */
@Component("projectsDataRetentionJobs")
class DataRetentionJobs(
    private val jdbc: JdbcClient,
    private val dataSource: DataSource,
    private val artifacts: ArtifactStore,
    blobStore: ObjectProvider<BlobStore>,
    private val properties: RetentionProperties,
    clock: ObjectProvider<Clock>,
    transactionManager: PlatformTransactionManager,
) {
    private val blobs: BlobStore? = blobStore.ifAvailable
    private val clock: Clock = clock.getIfAvailable { Clock.SYSTEM }
    private val tx = TransactionTemplate(transactionManager)

    data class Report(
        var blobPrefixesDeleted: Int = 0,
        var artifactRunsExpired: Int = 0,
        var artifactsMigrated: Int = 0,
        var checkpointThreadsDeleted: Int = 0,
        var runnerJobEventsDeleted: Int = 0,
        val failures: MutableList<String> = mutableListOf(),
    )

    @Scheduled(initialDelayString = "\${backoffice.retention.initial-delay:PT5M}", fixedDelayString = "\${backoffice.retention.interval:PT1H}")
    fun scheduled() {
        if (!properties.enabled) return
        val report = run() ?: return
        if (report != Report()) log.info("data retention (projects) {}", report)
    }

    /** One pass of every step; null when another replica holds the lock. */
    fun run(): Report? = AdvisoryLock.tryRun(dataSource, LOCK_NAME) {
        val report = Report()
        step(report, "blob-gc") { report.blobPrefixesDeleted = processBlobGc() }
        step(report, "artifact-blobs") { report.artifactRunsExpired = expireArtifactBlobs() }
        step(report, "artifact-migration") { report.artifactsMigrated = migrateInlineArtifacts() }
        step(report, "checkpoints") { report.checkpointThreadsDeleted = purgeCheckpoints() }
        step(report, "runner-job-events") { report.runnerJobEventsDeleted = purgeRunnerJobEvents() }
        report
    }

    private fun step(report: Report, name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            report.failures += "$name: ${e.message}"
            log.warn("data retention step failed step={} error={}", name, e.toString())
        }
    }

    fun processBlobGc(): Int {
        val store = blobs ?: return 0
        var done = 0
        repeat(properties.maxBatches) {
            val prefixes = jdbc.sql("SELECT key_prefix FROM run_blob_gc ORDER BY enqueued_at LIMIT :n")
                .param("n", properties.batchSize).query(String::class.java).list().filterNotNull()
            prefixes.forEach { prefix ->
                if (prefix.endsWith("/")) store.deletePrefix(prefix) else store.delete(prefix)
                artifacts.evictCached(prefix)
                jdbc.sql("DELETE FROM run_blob_gc WHERE key_prefix = :p").param("p", prefix).update()
                done++
            }
            if (prefixes.size < properties.batchSize) return done
        }
        return done
    }

    fun expireArtifactBlobs(): Int {
        val store = blobs ?: return 0
        val cutoff = ts(clock.now().minus(properties.artifacts))
        var runsDone = 0
        repeat(properties.maxBatches) {
            val runIds = jdbc.sql(
                """
                SELECT DISTINCT a.run_id FROM artifact a JOIN workflow_run r ON r.id = a.run_id
                WHERE a.blob_key IS NOT NULL AND r.status IN ($TERMINAL) AND r.finished_at < :cutoff
                LIMIT :n
                """.trimIndent(),
            ).param("cutoff", cutoff).param("n", properties.batchSize).query(UUID::class.java).list().filterNotNull()
            runIds.forEach { runId ->
                store.deletePrefix(ArtifactStore.runPrefix(runId))
                tx.executeWithoutResult {
                    jdbc.sql("UPDATE artifact SET blob_key = NULL, content = '', content_expired_at = :now WHERE run_id = :run AND blob_key IS NOT NULL")
                        .param("now", ts(clock.now())).param("run", runId).update()
                }
                runsDone++
            }
            if (runIds.size < properties.batchSize) return runsDone
        }
        return runsDone
    }

    fun migrateInlineArtifacts(): Int {
        if (!artifacts.blobStoreEnabled || properties.migrateInlineArtifacts <= 0) return 0
        // literal threshold so the partial index idx_artifact_inline_large (size_bytes > 65536) applies
        val ids = jdbc.sql(
            "SELECT id FROM artifact WHERE blob_key IS NULL AND content_expired_at IS NULL AND size_bytes > 65536 ORDER BY created_at LIMIT :n",
        ).param("n", properties.migrateInlineArtifacts).query(UUID::class.java).list().filterNotNull()
        var moved = 0
        ids.forEach { id -> if (tx.execute { artifacts.migrateInline(id) } == true) moved++ }
        return moved
    }

    fun purgeCheckpoints(): Int {
        if (!tableExists("lg4jcheckpoint") || !tableExists("lg4jthread") || !tableExists("lg4jthreadtag")) return 0
        val cutoff = ts(clock.now().minus(properties.checkpoints))
        val orphanCutoff = ts(clock.now().minus(ORPHAN_GRACE))
        var deleted = 0
        repeat(properties.maxBatches) {
            val threadIds = jdbc.sql(
                """
                SELECT t.thread_id FROM (
                    SELECT thread_id, thread_name, created_at FROM lg4jthread
                    UNION ALL SELECT thread_id, thread_name, created_at FROM lg4jthreadtag
                ) t
                LEFT JOIN workflow_run r ON r.id = CASE WHEN t.thread_name ~ '$UUID_PATTERN' THEN CAST(t.thread_name AS uuid) END
                WHERE (r.id IS NOT NULL AND r.status IN ($TERMINAL) AND r.finished_at < :cutoff)
                   OR (r.id IS NULL AND COALESCE(t.created_at, 'epoch') < :orphanCutoff)
                LIMIT :n
                """.trimIndent(),
            ).param("cutoff", cutoff).param("orphanCutoff", orphanCutoff).param("n", properties.batchSize).query(Long::class.javaObjectType).list().filterNotNull().distinct()
            if (threadIds.isEmpty()) return deleted
            tx.executeWithoutResult {
                jdbc.sql("DELETE FROM lg4jcheckpoint WHERE thread_id IN (:ids)").param("ids", threadIds).update()
                jdbc.sql("DELETE FROM lg4jthreadtag WHERE thread_id IN (:ids)").param("ids", threadIds).update()
                jdbc.sql("DELETE FROM lg4jthread WHERE thread_id IN (:ids)").param("ids", threadIds).update()
            }
            deleted += threadIds.size
            if (threadIds.size < properties.batchSize) return deleted
        }
        return deleted
    }

    /** Reads `runner_job_event` of the agent-runner module (V50) by name only; skipped when the table is absent. */
    fun purgeRunnerJobEvents(): Int {
        if (!tableExists("runner_job_event")) return 0
        val cutoff = ts(clock.now().minus(properties.runnerJobEvents))
        var deleted = 0
        repeat(properties.maxBatches) {
            // ids grow with time: walking the primary key from the lowest id finds the expired rows first
            val n = tx.execute {
                jdbc.sql("DELETE FROM runner_job_event WHERE id IN (SELECT id FROM runner_job_event WHERE at < :cutoff ORDER BY id LIMIT :n)")
                    .param("cutoff", cutoff).param("n", properties.batchSize).update()
            } ?: 0
            deleted += n
            if (n < properties.batchSize) return deleted
        }
        return deleted
    }

    private fun tableExists(name: String): Boolean =
        jdbc.sql("SELECT to_regclass(:name) IS NOT NULL").param("name", name).query(Boolean::class.javaObjectType).single()

    private fun ts(instant: Instant) = Timestamp.from(instant)

    companion object {
        /** Lock name for ShedLock (`@SchedulerLock(name = LOCK_NAME)`) and the advisory lock. */
        const val LOCK_NAME = "projects-data-retention"
        private const val TERMINAL = "'SUCCEEDED', 'FAILED', 'CANCELLED'"
        private const val UUID_PATTERN = "^[0-9a-fA-F-]{36}$"
        private val ORPHAN_GRACE: Duration = Duration.ofDays(1)
        private val log = LoggerFactory.getLogger(DataRetentionJobs::class.java)
    }
}
