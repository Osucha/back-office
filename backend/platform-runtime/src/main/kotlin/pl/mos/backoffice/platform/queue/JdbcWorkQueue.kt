package pl.mos.backoffice.platform.queue

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkPayload
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/** A claimed item plus what the worker needs to complete it safely. */
data class LeasedItem(val item: WorkItem, val generation: Int, val maxAttempts: Int, val waitedSeconds: Double)

/** Final status of a completion call; [LOST] when the lease was no longer ours (expired and reclaimed). */
enum class CompletionStatus { DONE, READY, DEAD, LOST }

/**
 * The `work_item` table (ADR 0023). Enqueue writes in the caller's transaction (it uses the Spring-managed
 * connection when one is bound) and issues `NOTIFY backoffice_work` (delivered on commit). A dedupe key keeps
 * at most one live item: enqueueing again merges the payload (`||`, the newer keys win) and bumps
 * `generation`, unless the request carries `_merge=keep` ([WorkPayload.MERGE]).
 *
 * Claiming uses `FOR UPDATE SKIP LOCKED`, so concurrent workers never receive the same item; every write of
 * the worker side is guarded by `lease_owner = :owner AND status = 'LEASED'`, so a worker whose lease expired
 * and was reclaimed cannot overwrite the new owner's result.
 */
class JdbcWorkQueue(private val jdbc: JdbcClient, private val json: JsonMapper) : WorkQueue {

    override fun enqueue(request: WorkRequest) {
        require(request.kind.isNotBlank()) { "work kind must not be blank" }
        val keep = request.payload[WorkPayload.MERGE] == WorkPayload.KEEP_EXISTING
        val payload = request.payload - WorkPayload.MERGE
        jdbc.sql(
            """
            INSERT INTO work_item (id, kind, dedupe_key, payload, priority, run_after, status, max_attempts)
            VALUES (:id, :kind, :dedupeKey, CAST(:payload AS jsonb), :priority, COALESCE(CAST(:runAfter AS timestamptz), now()), 'READY', :maxAttempts)
            ON CONFLICT (dedupe_key) WHERE status IN ('READY', 'LEASED') DO UPDATE SET
                payload = CASE WHEN CAST(:keep AS boolean) THEN work_item.payload ELSE work_item.payload || EXCLUDED.payload END,
                generation = work_item.generation + CASE WHEN CAST(:keep AS boolean) THEN 0 ELSE 1 END,
                run_after = CASE
                    WHEN CAST(:keep AS boolean) OR work_item.status <> 'READY' THEN work_item.run_after
                    ELSE LEAST(work_item.run_after, EXCLUDED.run_after) END,
                priority = GREATEST(work_item.priority, EXCLUDED.priority),
                max_attempts = GREATEST(work_item.max_attempts, EXCLUDED.max_attempts),
                updated_at = now()
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID())
            .param("kind", request.kind)
            .param("dedupeKey", request.dedupeKey)
            .param("payload", json.writeValueAsString(payload))
            .param("priority", request.priority.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            .param("runAfter", request.runAfter?.atOffset(ZoneOffset.UTC))
            .param("maxAttempts", request.maxAttempts.coerceAtLeast(1))
            .param("keep", keep)
            .update()
        jdbc.sql("SELECT pg_notify('$CHANNEL', :kind)").param("kind", request.kind).query { _: ResultSet, _: Int -> 1 }.list()
    }

    /** Leases up to [limit] READY items of [kinds] for [owner]; each claim counts as an attempt. */
    fun claim(owner: String, kinds: Collection<String>, limit: Int, lease: Duration): List<LeasedItem> {
        if (kinds.isEmpty() || limit <= 0) return emptyList()
        return jdbc.sql(
            """
            UPDATE work_item w SET status = 'LEASED', lease_owner = :owner,
                lease_until = now() + CAST(:leaseMillis AS bigint) * interval '1 millisecond',
                attempts = w.attempts + 1, updated_at = now()
            FROM (
                SELECT id, run_after FROM work_item
                WHERE status = 'READY' AND run_after <= now() AND kind IN (:kinds)
                ORDER BY priority DESC, run_after
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            ) c
            WHERE w.id = c.id
            RETURNING w.id, w.kind, w.payload::text AS payload, w.attempts, w.max_attempts, w.dedupe_key, w.generation,
                GREATEST(EXTRACT(EPOCH FROM (now() - c.run_after)), 0) AS waited
            """.trimIndent(),
        )
            .param("owner", owner)
            .param("leaseMillis", lease.toMillis())
            .param("kinds", kinds.toList())
            .param("limit", limit)
            .query { rs, _ -> leased(rs) }
            .list()
    }

    /** Extends the leases of [ids] still held by [owner]; returns the ids whose lease is no longer ours. */
    fun heartbeat(owner: String, ids: Collection<UUID>, lease: Duration): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val kept = jdbc.sql(
            """
            UPDATE work_item SET lease_until = now() + CAST(:leaseMillis AS bigint) * interval '1 millisecond', updated_at = now()
            WHERE id IN (:ids) AND lease_owner = :owner AND status = 'LEASED'
            RETURNING id
            """.trimIndent(),
        ).param("leaseMillis", lease.toMillis()).param("ids", ids.toList()).param("owner", owner)
            .query(UUID::class.java).list().filterNotNull().toSet()
        return ids.toSet() - kept
    }

    /** Puts items whose lease expired (crashed or partitioned worker) back to READY, or DEAD when out of attempts. */
    fun reclaimExpired(): List<Pair<UUID, String>> = jdbc.sql(
        """
        UPDATE work_item SET
            status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'READY' END,
            last_error = 'lease of ' || COALESCE(lease_owner, '?') || ' expired',
            finished_at = CASE WHEN attempts >= max_attempts THEN now() ELSE NULL END,
            lease_owner = NULL, lease_until = NULL, run_after = now(), updated_at = now()
        WHERE id IN (SELECT id FROM work_item WHERE status = 'LEASED' AND lease_until < now() FOR UPDATE SKIP LOCKED)
        RETURNING id, status
        """.trimIndent(),
    ).query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("status") }.list()

    /** The handler finished; a merge that arrived meanwhile puts the item back to READY with fresh attempts. */
    fun complete(owner: String, leased: LeasedItem): CompletionStatus = finish(
        """
        UPDATE work_item SET
            status = CASE WHEN generation <> :generation THEN 'READY' ELSE 'DONE' END,
            attempts = CASE WHEN generation <> :generation THEN 0 ELSE attempts END,
            finished_at = CASE WHEN generation <> :generation THEN NULL ELSE now() END,
            last_error = NULL, lease_owner = NULL, lease_until = NULL, run_after = now(), updated_at = now()
        WHERE id = :id AND lease_owner = :owner AND status = 'LEASED'
        RETURNING status
        """.trimIndent(),
        owner, leased, null, null,
    )

    /** Try again after [after]; out of attempts it becomes DEAD (a merged signal still gets a fresh try). */
    fun retry(owner: String, leased: LeasedItem, after: Duration, error: String): CompletionStatus = finish(
        """
        UPDATE work_item SET
            status = CASE WHEN generation <> :generation THEN 'READY' WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'READY' END,
            run_after = CASE WHEN generation <> :generation THEN now() ELSE now() + CAST(:delayMillis AS bigint) * interval '1 millisecond' END,
            finished_at = CASE WHEN generation = :generation AND attempts >= max_attempts THEN now() ELSE NULL END,
            last_error = :error, lease_owner = NULL, lease_until = NULL, updated_at = now()
        WHERE id = :id AND lease_owner = :owner AND status = 'LEASED'
        RETURNING status
        """.trimIndent(),
        owner, leased, after, error,
    )

    fun dead(owner: String, leased: LeasedItem, error: String): CompletionStatus = finish(
        """
        UPDATE work_item SET
            status = CASE WHEN generation <> :generation THEN 'READY' ELSE 'DEAD' END,
            finished_at = CASE WHEN generation <> :generation THEN NULL ELSE now() END,
            run_after = now(), last_error = :error, lease_owner = NULL, lease_until = NULL, updated_at = now()
        WHERE id = :id AND lease_owner = :owner AND status = 'LEASED'
        RETURNING status
        """.trimIndent(),
        owner, leased, null, error,
    )

    /** Hands leases back without counting the attempt (graceful shutdown). */
    fun release(owner: String, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return jdbc.sql(
            """
            UPDATE work_item SET status = 'READY', attempts = GREATEST(attempts - 1, 0), lease_owner = NULL, lease_until = NULL,
                run_after = now(), last_error = 'released by ' || :owner || ' at shutdown', updated_at = now()
            WHERE id IN (:ids) AND lease_owner = :owner AND status = 'LEASED'
            """.trimIndent(),
        ).param("ids", ids.toList()).param("owner", owner).update()
    }

    /** Operator retry of a DEAD item (admin -> operations). False when it is not DEAD. */
    fun requeueDead(id: UUID): Boolean = try {
        jdbc.sql(
            """
            UPDATE work_item SET status = 'READY', attempts = 0, run_after = now(), finished_at = NULL, lease_owner = NULL,
                lease_until = NULL, updated_at = now()
            WHERE id = :id AND status = 'DEAD'
            """.trimIndent(),
        ).param("id", id).update() == 1
    } catch (e: DuplicateKeyException) {
        throw IllegalStateException("another live work item with the same dedupe key exists", e)
    }

    private fun finish(sql: String, owner: String, leased: LeasedItem, delay: Duration?, error: String?): CompletionStatus {
        var spec = jdbc.sql(sql).param("id", leased.item.id).param("owner", owner).param("generation", leased.generation)
        if (delay != null) spec = spec.param("delayMillis", delay.toMillis().coerceAtLeast(0))
        if (error != null) spec = spec.param("error", error.take(MAX_ERROR))
        val status = spec.query(String::class.java).optional().orElse(null) ?: return CompletionStatus.LOST
        return CompletionStatus.valueOf(status)
    }

    @Suppress("UNCHECKED_CAST")
    private fun leased(rs: ResultSet): LeasedItem {
        val payload = json.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>
        return LeasedItem(
            item = WorkItem(
                id = rs.getObject("id", UUID::class.java),
                kind = rs.getString("kind"),
                payload = payload,
                attempt = rs.getInt("attempts"),
                dedupeKey = rs.getString("dedupe_key"),
            ),
            generation = rs.getInt("generation"),
            maxAttempts = rs.getInt("max_attempts"),
            waitedSeconds = rs.getDouble("waited"),
        )
    }

    companion object {
        const val CHANNEL = "backoffice_work"
        const val MAX_ERROR = 4000
    }
}
