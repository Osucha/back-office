package pl.mos.backoffice.identity.adapter.out.retention

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
import pl.mos.backoffice.shared.Clock
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.YearMonth
import java.time.ZoneOffset
import javax.sql.DataSource

/** `backoffice.retention.audit.*`: monthly partitions of `audit_log` (V404). */
@ConfigurationProperties(prefix = "backoffice.retention.audit")
data class AuditRetentionProperties(
    val enabled: Boolean = true,
    /** Future months kept created ahead of time (the default partition catches anything beyond). */
    val monthsAhead: Int = 3,
    /** Months of audit history kept online; older partitions are dropped only when [dropExpired] is set. */
    val retentionMonths: Int = 24,
    /** Drop partitions older than [retentionMonths]. Off by default: export them first (docs/runbooks). */
    val dropExpired: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditRetentionProperties::class)
class AuditRetentionConfiguration

/**
 * Data lifecycle of the identity context: keeps `audit_log` partitions (`audit_log_pYYYY_MM`, UTC months)
 * created [AuditRetentionProperties.monthsAhead] months ahead, moves rows that landed in
 * `audit_log_default` into their month when that month is created, and optionally drops expired months.
 * One scheduled entry point guarded by a cluster-wide lock [LOCK_NAME] (PostgreSQL advisory lock; with
 * ShedLock put `@SchedulerLock(name = DataRetentionJobs.LOCK_NAME)` on [run]).
 */
@Component("identityDataRetentionJobs")
class DataRetentionJobs(
    private val jdbc: JdbcClient,
    private val dataSource: DataSource,
    private val properties: AuditRetentionProperties,
    clock: ObjectProvider<Clock>,
    transactionManager: PlatformTransactionManager,
) {
    private val clock: Clock = clock.getIfAvailable { Clock.SYSTEM }
    private val tx = TransactionTemplate(transactionManager)

    data class Report(val partitionsCreated: List<String>, val rowsMovedFromDefault: Long, val partitionsDropped: List<String>)

    @Scheduled(initialDelayString = "\${backoffice.retention.audit.initial-delay:PT1M}", fixedDelayString = "\${backoffice.retention.audit.interval:PT6H}")
    fun scheduled() {
        if (!properties.enabled) return
        try {
            val report = run() ?: return
            if (report.partitionsCreated.isNotEmpty() || report.partitionsDropped.isNotEmpty()) log.info("audit partition maintenance {}", report)
        } catch (e: RuntimeException) {
            log.warn("audit partition maintenance failed error={}", e.toString())
        }
    }

    /** Null when another replica holds the lock or `audit_log` is not partitioned. */
    fun run(): Report? = tryLocked {
        if (!partitioned()) return@tryLocked null
        val current = YearMonth.now(java.time.Clock.fixed(clock.now(), ZoneOffset.UTC))
        var moved = 0L
        // months ahead, plus every month that has rows in the default partition (imports, clock skew, backfills)
        val strays = jdbc.sql("SELECT DISTINCT to_char(event_time AT TIME ZONE 'UTC', 'YYYY-MM') FROM audit_log_default")
            .query(String::class.java).list().filterNotNull().map(YearMonth::parse)
        val months = ((0..properties.monthsAhead).map { current.plusMonths(it.toLong()) } + strays).distinct().sorted()
        val created = months.mapNotNull { month ->
            ensurePartition(month)?.also { moved += it }?.let { partitionName(month) }
        }
        val dropped = if (properties.dropExpired) dropExpired(current.minusMonths(properties.retentionMonths.toLong())) else emptyList()
        Report(created, moved, dropped)
    }

    /**
     * Creates the partition of [month] unless it exists; rows of that month already in the default
     * partition are moved into it before it is attached. Returns the moved row count, null when it existed.
     */
    fun ensurePartition(month: YearMonth): Long? = tx.execute {
        val name = partitionName(month)
        if (exists(name)) return@execute null
        val from = bound(month)
        val to = bound(month.plusMonths(1))
        jdbc.sql("CREATE TABLE $name (LIKE audit_log INCLUDING DEFAULTS INCLUDING CONSTRAINTS)").update()
        val moved = jdbc.sql(
            """
            WITH moved AS (DELETE FROM audit_log_default WHERE event_time >= '$from' AND event_time < '$to' RETURNING *)
            INSERT INTO $name SELECT * FROM moved
            """.trimIndent(),
        ).update().toLong()
        jdbc.sql("ALTER TABLE audit_log ATTACH PARTITION $name FOR VALUES FROM ('$from') TO ('$to')").update()
        if (moved > 0) log.info("audit partition {} created, {} rows moved from audit_log_default", name, moved)
        moved
    }

    /** Monthly partitions whose month is before [keepFrom]; detached and dropped. */
    fun dropExpired(keepFrom: YearMonth): List<String> = partitions()
        .filter { name -> monthOf(name)?.isBefore(keepFrom) == true }
        .onEach { name ->
            tx.executeWithoutResult {
                jdbc.sql("ALTER TABLE audit_log DETACH PARTITION $name").update()
                jdbc.sql("DROP TABLE $name").update()
            }
            log.info("audit partition {} dropped (older than retention)", name)
        }

    fun partitions(): List<String> = jdbc.sql(
        "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'audit_log' ORDER BY c.relname",
    ).query(String::class.java).list().filterNotNull()

    private fun partitioned(): Boolean =
        jdbc.sql("SELECT count(*) FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid WHERE c.relname = 'audit_log'").query(Long::class.java).single() > 0

    private fun exists(name: String): Boolean = jdbc.sql("SELECT to_regclass(:n) IS NOT NULL").param("n", name).query(Boolean::class.javaObjectType).single()

    private fun <T> tryLocked(block: () -> T): T? = dataSource.connection.use { connection ->
        val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(LOCK_NAME.toByteArray(Charsets.UTF_8))).long
        val locked = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, key)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!locked) return@use null
        try {
            block()
        } finally {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                ps.setLong(1, key)
                ps.executeQuery().close()
            }
        }
    }

    companion object {
        /** Lock name for ShedLock (`@SchedulerLock(name = LOCK_NAME)`) and the advisory lock. */
        const val LOCK_NAME = "identity-data-retention"
        private val log = LoggerFactory.getLogger(DataRetentionJobs::class.java)
        private val NAME = Regex("^audit_log_p(\\d{4})_(\\d{2})$")

        fun partitionName(month: YearMonth): String = "audit_log_p%04d_%02d".format(month.year, month.monthValue)

        fun monthOf(partition: String): YearMonth? = NAME.matchEntire(partition)?.let { YearMonth.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        private fun bound(month: YearMonth): String = "%04d-%02d-01 00:00:00+00".format(month.year, month.monthValue)
    }
}
