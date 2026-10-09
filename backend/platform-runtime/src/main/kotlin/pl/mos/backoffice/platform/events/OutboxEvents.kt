package pl.mos.backoffice.platform.events

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.mos.backoffice.platform.metrics.PlatformMetrics
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.platform.EventStream
import pl.mos.backoffice.shared.platform.StoredEvent
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [EventPublisher] of the platform (ADR 0023): the event is written to `event_log` in the caller's transaction
 * (outbox) and announced with `NOTIFY backoffice_events '<id>'`, which PostgreSQL delivers on commit (and
 * drops on rollback). It is also re-published as a Spring application event for in-process listeners.
 * Outside a transaction a failing insert is logged, not thrown: events are notifications, the domain row is
 * the truth.
 */
class OutboxEventPublisher(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val local: ApplicationEventPublisher?,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(OutboxEventPublisher::class.java)

    override fun publish(event: DomainEvent) {
        try {
            val id = jdbc.sql(
                """
                INSERT INTO event_log (type, project_id, payload, occurred_at)
                VALUES (:type, :projectId, CAST(:payload AS jsonb), :occurredAt)
                RETURNING id
                """.trimIndent(),
            )
                .param("type", event.type)
                .param("projectId", projectIdOf(event.payload))
                .param("payload", json.writeValueAsString(event.payload))
                .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
                .query(Long::class.javaObjectType).single()
            jdbc.sql("SELECT pg_notify('$CHANNEL', :id)").param("id", id.toString()).query { _: ResultSet, _: Int -> 1 }.list()
        } catch (e: RuntimeException) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw e
            log.warn("could not write event {} to the outbox: {}", event.type, e.toString())
        }
        local?.publishEvent(event)
    }

    companion object {
        const val CHANNEL = "backoffice_events"

        fun projectIdOf(payload: Map<String, Any?>): UUID? =
            payload["projectId"]?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

/**
 * [EventStream] over `event_log` and the LISTEN bridge. A notification carries only the row id; the rows of a
 * batch are loaded with one query and delivered in id order. Ids already delivered are remembered (bounded),
 * so the gap fill after a reconnect (rows created since shortly before the connection was lost) never
 * delivers an event twice.
 */
class PlatformEventStream(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val metrics: PlatformMetrics? = null,
) : EventStream {

    private val log = LoggerFactory.getLogger(PlatformEventStream::class.java)
    private val listeners = CopyOnWriteArrayList<(StoredEvent) -> Unit>()
    private val delivered = LinkedHashSet<Long>()
    @Volatile private var lastSeenAt: Instant? = null

    override fun subscribe(listener: (StoredEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun replayAfter(lastId: Long, limit: Int): List<StoredEvent> =
        jdbc.sql("SELECT id, type, project_id, payload::text AS payload, occurred_at FROM event_log WHERE id > :id ORDER BY id LIMIT :limit")
            .param("id", lastId).param("limit", limit.coerceIn(1, MAX_REPLAY)).query { rs, _ -> row(rs) }.list()

    override fun subscribers(): Int = listeners.size

    /** Notification payloads of channel `backoffice_events` (row ids). */
    fun onNotified(ids: List<String>) {
        val parsed = ids.mapNotNull { it.toLongOrNull() }.filterNot { isDelivered(it) }.distinct()
        if (parsed.isEmpty()) return
        val events = jdbc.sql("SELECT id, type, project_id, payload::text AS payload, occurred_at FROM event_log WHERE id IN (:ids) ORDER BY id")
            .param("ids", parsed).query { rs, _ -> row(rs) }.list()
        deliver(events)
    }

    /** After a reconnect: deliver what was written while the connection was down. */
    fun onReconnected(first: Boolean) {
        val since = lastSeenAt
        if (first || since == null) {
            lastSeenAt = Instant.now()
            return
        }
        // created_at is the database clock, lastSeenAt the JVM clock: compare durations, not instants, so clock
        // skew between the replica and the database server cannot hide the gap.
        val window = java.time.Duration.between(since, Instant.now()).seconds.coerceAtLeast(0) + GAP_SAFETY_SECONDS
        val missed = jdbc.sql(
            "SELECT id, type, project_id, payload::text AS payload, occurred_at FROM event_log " +
                "WHERE created_at >= now() - make_interval(secs => :window) ORDER BY id LIMIT :limit",
        ).param("window", window.toDouble()).param("limit", MAX_REPLAY).query { rs, _ -> row(rs) }.list()
            .filterNot { isDelivered(it.id) }
        if (missed.isNotEmpty()) log.info("delivering {} events missed while the LISTEN connection was down", missed.size)
        deliver(missed)
    }

    /** Delivers [events] to the local subscribers (also used by tests to inject events). */
    fun deliver(events: List<StoredEvent>) {
        if (events.isEmpty()) return
        events.forEach { event ->
            markDelivered(event.id)
            listeners.forEach { listener ->
                try {
                    listener(event)
                } catch (e: Exception) {
                    log.debug("event subscriber failed: {}", e.toString())
                }
            }
        }
        lastSeenAt = Instant.now()
        metrics?.delivered(events.size)
    }

    private fun isDelivered(id: Long): Boolean = synchronized(delivered) { id in delivered }

    private fun markDelivered(id: Long) = synchronized(delivered) {
        delivered += id
        if (delivered.size > REMEMBERED) delivered.iterator().let { it.next(); it.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun row(rs: ResultSet) = StoredEvent(
        id = rs.getLong("id"),
        type = rs.getString("type"),
        projectId = rs.getObject("project_id", UUID::class.java),
        occurredAt = rs.getObject("occurred_at", java.time.OffsetDateTime::class.java).toInstant(),
        payload = json.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>,
    )

    private companion object {
        const val REMEMBERED = 10_000
        const val MAX_REPLAY = 5_000
        const val GAP_SAFETY_SECONDS = 10L
    }
}
