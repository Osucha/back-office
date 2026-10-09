package pl.mos.backoffice.platform.adapter.out.jdbc

import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.platform.application.port.`in`.ReplicaView
import pl.mos.backoffice.platform.application.port.`in`.WorkItemView
import pl.mos.backoffice.platform.application.port.out.OperationsReadModel
import pl.mos.backoffice.platform.queue.JdbcWorkQueue
import pl.mos.backoffice.shared.usage.PageOf
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** [OperationsReadModel] over `work_item` and `platform_instance`. */
class JdbcOperationsReadModel(private val jdbc: JdbcClient, private val queue: JdbcWorkQueue) : OperationsReadModel {

    override fun replicas(staleAfter: Duration): List<ReplicaView> =
        jdbc.sql("SELECT id, roles, version, heartbeat_at, heartbeat_at < now() - CAST(:ms AS bigint) * interval '1 millisecond' AS stale FROM platform_instance ORDER BY id")
            .param("ms", staleAfter.toMillis())
            .query { rs, _ ->
                ReplicaView(
                    instance = rs.getString("id"),
                    roles = rs.getString("roles").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    version = rs.getString("version"),
                    lastSeenAt = rs.instant("heartbeat_at")!!,
                    stale = rs.getBoolean("stale"),
                )
            }.list()

    override fun countsByStatus(): Map<String, Long> =
        jdbc.sql("SELECT status, count(*) AS n FROM work_item GROUP BY status").query { rs, _ -> rs.getString("status") to rs.getLong("n") }.list().toMap()

    override fun oldestReadyAgeSeconds(): Long =
        jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(run_after))), 0) FROM work_item WHERE status = 'READY' AND run_after <= now()")
            .query(Double::class.javaObjectType).single().toLong()

    override fun workItems(status: String?, kind: String?, page: Int, size: Int): PageOf<WorkItemView> {
        val where = buildString {
            append("WHERE 1 = 1")
            if (status != null) append(" AND status = :status")
            if (kind != null) append(" AND kind = :kind")
        }
        fun bind(spec: JdbcClient.StatementSpec): JdbcClient.StatementSpec {
            var s = spec
            if (status != null) s = s.param("status", status)
            if (kind != null) s = s.param("kind", kind)
            return s
        }
        val total = bind(jdbc.sql("SELECT count(*) FROM work_item $where")).query(Long::class.javaObjectType).single()
        val rows = bind(jdbc.sql("SELECT * FROM work_item $where ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset"))
            .param("limit", size).param("offset", page.toLong() * size)
            .query { rs, _ -> view(rs) }.list()
        return PageOf(rows, page, size, total)
    }

    override fun find(itemId: UUID): WorkItemView? =
        jdbc.sql("SELECT * FROM work_item WHERE id = :id").param("id", itemId).query { rs, _ -> view(rs) }.optional().orElse(null)

    override fun requeueDead(itemId: UUID): Boolean = queue.requeueDead(itemId)

    private fun view(rs: ResultSet) = WorkItemView(
        id = rs.getObject("id", UUID::class.java),
        kind = rs.getString("kind"),
        dedupeKey = rs.getString("dedupe_key"),
        status = rs.getString("status"),
        attempts = rs.getInt("attempts"),
        maxAttempts = rs.getInt("max_attempts"),
        runAfter = rs.instant("run_after")!!,
        leaseOwner = rs.getString("lease_owner"),
        leaseUntil = rs.instant("lease_until"),
        lastError = rs.getString("last_error"),
        createdAt = rs.instant("created_at")!!,
        updatedAt = rs.instant("updated_at")!!,
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
