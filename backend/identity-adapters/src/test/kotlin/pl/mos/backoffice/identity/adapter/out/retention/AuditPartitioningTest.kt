package pl.mos.backoffice.identity.adapter.out.retention

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import java.sql.Timestamp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** V404: `audit_log` is range-partitioned by month, keyset search spans partitions, the job maintains them. */
@SpringBootTest
@Import(PostgresTestSupport::class)
class AuditPartitioningTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var reader: AuditLogReader
    @Autowired lateinit var jobs: DataRetentionJobs

    private val current: YearMonth = YearMonth.now(ZoneOffset.UTC)

    private fun insert(at: Instant, entityId: String, operation: String = "test.partition"): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO audit_log (event_time, id, actor_kind, actor_name, operation, entity_type, entity_id, result)
            VALUES (:t, :id, 'SYSTEM', 'test', :op, 'partition-test', :e, 'SUCCESS')
            """.trimIndent(),
        ).param("t", Timestamp.from(at)).param("id", id).param("op", operation).param("e", entityId).update()
        return id
    }

    private fun partitionOf(id: UUID): String =
        jdbc.sql("SELECT tableoid::regclass::text FROM audit_log WHERE id = :id").param("id", id).query(String::class.java).single()

    private fun monthStart(month: YearMonth): Instant = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    @Test
    fun `migration creates monthly partitions for current month plus minus three and a default partition`() {
        val partitions = jobs.partitions()
        (-3L..3L).map { DataRetentionJobs.partitionName(current.plusMonths(it)) }.forEach { assertThat(partitions).contains(it) }
        assertThat(partitions).contains("audit_log_default")
        val pk = jdbc.sql(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'audit_log'::regclass AND contype = 'p'",
        ).query(String::class.java).single()
        assertThat(pk).isEqualTo("PRIMARY KEY (event_time, id)")
    }

    @Test
    fun `rows land in their month and the keyset search pages across partitions in order`() {
        val entity = "keyset-${UUID.randomUUID()}"
        val times = (0..11).map { monthStart(current.minusMonths(2)).plus(it * 5L, ChronoUnit.DAYS).plusSeconds(it.toLong()) }
        val ids = times.map { insert(it, entity) }
        // two rows with the same timestamp: the id breaks the tie
        val tie = times[5]
        val tieId = insert(tie, entity)

        assertThat(partitionOf(ids.first())).isEqualTo(DataRetentionJobs.partitionName(current.minusMonths(2)))
        assertThat(partitionOf(ids.last())).isEqualTo(DataRetentionJobs.partitionName(YearMonth.from(times.last().atZone(ZoneOffset.UTC))))

        val seen = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val page = reader.search(AuditQuery(entityType = "partition-test", entityId = entity, size = 4, cursor = cursor))
            seen += page.content.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        assertThat(seen).hasSize(13).doesNotHaveDuplicates()
        assertThat(seen.toSet()).isEqualTo((ids + tieId).toSet())
        val loaded = seen.map { id -> jdbc.sql("SELECT event_time FROM audit_log WHERE id = :id").param("id", id).query(Timestamp::class.java).single().toInstant() }
        assertThat(loaded).isSortedAccordingTo(Comparator.reverseOrder())

        // a time range prunes partitions but returns the same rows
        val march = reader.search(AuditQuery(from = monthStart(current.minusMonths(1)), to = monthStart(current), entityId = entity, size = 50))
        assertThat(march.content).allMatch { !it.eventTime.isBefore(monthStart(current.minusMonths(1))) && it.eventTime.isBefore(monthStart(current)) }
        assertThat(march.content).isNotEmpty()
    }

    @Test
    fun `rows beyond the created range go to the default partition and move into their month when it is created`() {
        val far = current.plusMonths(8)
        val id = insert(monthStart(far).plus(3, ChronoUnit.DAYS), "future-${UUID.randomUUID()}")
        assertThat(partitionOf(id)).isEqualTo("audit_log_default")

        val moved = jobs.ensurePartition(far)

        assertThat(moved).isGreaterThanOrEqualTo(1)
        assertThat(partitionOf(id)).isEqualTo(DataRetentionJobs.partitionName(far))
        assertThat(jobs.ensurePartition(far)).isNull()
        // the new partition got the partitioned indexes, including the primary key
        val indexes = jdbc.sql("SELECT count(*) FROM pg_indexes WHERE tablename = :t").param("t", DataRetentionJobs.partitionName(far)).query(Long::class.java).single()
        assertThat(indexes).isGreaterThanOrEqualTo(6)
    }

    @Test
    fun `the job keeps months ahead created and drops expired partitions only when enabled`() {
        val past = current.minusMonths(10)
        val stray = insert(monthStart(past).plusSeconds(90), "stray-${UUID.randomUUID()}")
        assertThat(partitionOf(stray)).isEqualTo("audit_log_default")

        val report = jobs.run()!!

        assertThat(report.partitionsDropped).isEmpty()
        assertThat(report.partitionsCreated).contains(DataRetentionJobs.partitionName(past))
        assertThat(partitionOf(stray)).isEqualTo(DataRetentionJobs.partitionName(past))
        assertThat(jobs.partitions()).contains(DataRetentionJobs.partitionName(current.plusMonths(3)))

        val old = current.minusMonths(30)
        jobs.ensurePartition(old)
        insert(monthStart(old).plusSeconds(60), "old-${UUID.randomUUID()}")
        assertThat(jobs.dropExpired(current.minusMonths(24))).contains(DataRetentionJobs.partitionName(old))
        assertThat(jobs.partitions()).doesNotContain(DataRetentionJobs.partitionName(old))
    }
}
