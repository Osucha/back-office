package pl.mos.backoffice.platform.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Platform meters (docs/architecture/09-scale-and-operations.md, "Observability"), exported by the Prometheus
 * registry with the `_seconds` / `_total` suffixes:
 *
 * - `backoffice_work_items{kind,status}` live and dead items (refreshed from the database, [refresh]);
 * - `backoffice_work_oldest_ready_age_seconds` age of the oldest READY item that is due;
 * - `backoffice_work_latency_seconds{kind}` pickup latency (due -> leased);
 * - `backoffice_work_duration_seconds{kind,outcome}` handler run time;
 * - `backoffice_event_listener_connected` 1 while the LISTEN connection is up, `backoffice_event_listener_reconnects_total`;
 * - `backoffice_events_delivered_total` events fanned out to local subscribers.
 */
class PlatformMetrics(private val registry: MeterRegistry, private val jdbc: JdbcClient) {

    private val workItems = MultiGauge.builder("backoffice.work.items").description("Work items by kind and status (READY, LEASED, DEAD)").register(registry)
    private val oldestReady = AtomicLong(0)
    private val listenerConnected = AtomicLong(0)
    private val reconnects: Counter = Counter.builder("backoffice.event.listener.reconnects").description("Reconnects of the LISTEN connection").register(registry)
    private val delivered: Counter = Counter.builder("backoffice.events.delivered").description("Events fanned out to local subscribers").register(registry)

    init {
        registry.gauge("backoffice.work.oldest.ready.age.seconds", oldestReady) { it.get().toDouble() }
        registry.gauge("backoffice.event.listener.connected", listenerConnected) { it.get().toDouble() }
    }

    fun pickedUp(kind: String, waitedSeconds: Double) {
        Timer.builder("backoffice.work.latency").description("Work item pickup latency (due -> leased)").tag("kind", kind)
            .publishPercentileHistogram().register(registry)
            .record(Duration.ofMillis((waitedSeconds * MILLIS).toLong().coerceAtLeast(0)))
    }

    fun finished(kind: String, outcome: String, duration: Duration) {
        Timer.builder("backoffice.work.duration").description("Work handler duration").tags("kind", kind, "outcome", outcome)
            .register(registry).record(duration)
    }

    fun listener(connected: Boolean) = listenerConnected.set(if (connected) 1 else 0)

    fun reconnected() = reconnects.increment()

    fun delivered(count: Int) = delivered.increment(count.toDouble())

    /** Re-reads the queue counts; called periodically by the platform scheduler. */
    fun refresh() {
        val rows = jdbc.sql("SELECT kind, status, count(*) AS n FROM work_item WHERE status IN ('READY', 'LEASED', 'DEAD') GROUP BY kind, status")
            .query { rs, _ -> Triple(rs.getString("kind"), rs.getString("status"), rs.getLong("n")) }.list()
        workItems.register(rows.map { (kind, status, n) -> MultiGauge.Row.of(Tags.of("kind", kind, "status", status), n) }, true)
        oldestReady.set(
            jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(run_after))), 0) FROM work_item WHERE status = 'READY' AND run_after <= now()")
                .query(Double::class.javaObjectType).single().toLong(),
        )
    }

    private companion object {
        const val MILLIS = 1000.0
    }
}
