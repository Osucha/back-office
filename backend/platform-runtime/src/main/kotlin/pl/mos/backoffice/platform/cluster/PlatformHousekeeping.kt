package pl.mos.backoffice.platform.cluster

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.platform.config.PlatformProperties
import pl.mos.backoffice.platform.config.PlatformRoles
import pl.mos.backoffice.platform.metrics.PlatformMetrics
import pl.mos.backoffice.shared.platform.ClusterLock
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodic platform tasks of one replica on a small scheduler of its own (independent of Spring's
 * `@Scheduled`, so the role decides what runs):
 *
 * - every role: the `platform_instance` heartbeat (row removed at shutdown) and the queue gauges;
 * - worker role: retention under ShedLock (`event_log` 24 h, DONE work items 7 days, stale instances).
 */
class PlatformHousekeeping(
    private val jdbc: JdbcClient,
    private val properties: PlatformProperties,
    private val roles: PlatformRoles,
    private val clusterLock: ClusterLock,
    private val metrics: PlatformMetrics?,
    private val version: String,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(PlatformHousekeeping::class.java)
    private val running = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null
    private val instanceId = properties.resolvedInstanceId()
    private val startedAt = java.time.OffsetDateTime.now()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = Executors.newScheduledThreadPool(1) { r -> Thread(r, "platform-housekeeping").apply { isDaemon = true } }
        scheduler = s
        heartbeat()
        s.scheduleWithFixedDelay(guard("instance heartbeat", ::heartbeat), period(properties.instanceHeartbeat), period(properties.instanceHeartbeat), TimeUnit.MILLISECONDS)
        if (metrics != null) {
            s.scheduleWithFixedDelay(guard("queue metrics", metrics::refresh), 0, period(properties.metricsRefresh), TimeUnit.MILLISECONDS)
        }
        if (roles.worker) {
            val sweep = period(properties.retention.sweepInterval)
            s.scheduleWithFixedDelay(guard("retention", ::retention), sweep.coerceAtMost(INITIAL_RETENTION_DELAY_MILLIS), sweep, TimeUnit.MILLISECONDS)
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler?.shutdownNow()
        runCatching { jdbc.sql("DELETE FROM platform_instance WHERE id = :id").param("id", instanceId).update() }
            .onFailure { log.debug("could not deregister instance: {}", it.toString()) }
    }

    override fun isRunning(): Boolean = running.get()

    fun heartbeat() {
        jdbc.sql(
            """
            INSERT INTO platform_instance (id, roles, hostname, version, started_at, heartbeat_at, stopping)
            VALUES (:id, :roles, :hostname, :version, :startedAt, now(), false)
            ON CONFLICT (id) DO UPDATE SET heartbeat_at = now(), roles = EXCLUDED.roles, version = EXCLUDED.version
            """.trimIndent(),
        )
            .param("id", instanceId)
            .param("roles", roles.toString())
            .param("hostname", HOSTNAME)
            .param("version", version)
            .param("startedAt", startedAt)
            .update()
    }

    /** Deletes expired rows; returns (events, work items, instances) deleted. Runs on one replica at a time. */
    fun retention(): Triple<Int, Int, Int> {
        var result = Triple(0, 0, 0)
        clusterLock.runExclusively("platform-retention", lockAtMost = Duration.ofMinutes(10), lockAtLeast = Duration.ofMinutes(1)) {
            val r = properties.retention
            val events = jdbc.sql("DELETE FROM event_log WHERE created_at < now() - CAST(:ms AS bigint) * interval '1 millisecond'").param("ms", r.eventLog.toMillis()).update()
            val items = jdbc.sql("DELETE FROM work_item WHERE status = 'DONE' AND finished_at < now() - CAST(:ms AS bigint) * interval '1 millisecond'").param("ms", r.doneWorkItems.toMillis()).update()
            val instances = jdbc.sql("DELETE FROM platform_instance WHERE heartbeat_at < now() - CAST(:ms AS bigint) * interval '1 millisecond'").param("ms", r.instances.toMillis()).update()
            if (events + items + instances > 0) log.info("platform retention deleted events={} workItems={} instances={}", events, items, instances)
            result = Triple(events, items, instances)
        }
        return result
    }

    private fun guard(name: String, task: () -> Unit): Runnable = Runnable {
        try {
            task()
        } catch (e: Exception) {
            log.warn("platform task {} failed: {}", name, e.toString())
        }
    }

    private fun period(d: Duration): Long = d.toMillis().coerceAtLeast(MIN_PERIOD_MILLIS)

    private companion object {
        const val MIN_PERIOD_MILLIS = 100L
        const val INITIAL_RETENTION_DELAY_MILLIS = 60_000L
        val HOSTNAME: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
    }
}
