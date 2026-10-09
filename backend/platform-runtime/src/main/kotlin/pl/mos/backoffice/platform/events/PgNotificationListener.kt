package pl.mos.backoffice.platform.events

import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import pl.mos.backoffice.platform.metrics.PlatformMetrics
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PostgreSQL `LISTEN` bridge (ADR 0023). Holds one dedicated, non-pooled connection (PgBouncer in transaction
 * mode cannot carry LISTEN) opened by [connect], listens on [channels] and hands every notification to the
 * registered handlers on its own thread. When the connection breaks it reconnects every
 * [reconnectDelay] and calls the reconnect hooks, so consumers can fill the gap (the event stream re-reads
 * `event_log`, workers poll anyway).
 */
class PgNotificationListener(
    private val connect: () -> Connection,
    private val channels: List<String>,
    private val reconnectDelay: Duration = Duration.ofSeconds(5),
    private val pollTimeout: Duration = Duration.ofMillis(500),
    private val metrics: PlatformMetrics? = null,
) : SmartLifecycle {

    fun interface Handler {
        fun onNotifications(channel: String, payloads: List<String>)
    }

    private val log = LoggerFactory.getLogger(PgNotificationListener::class.java)
    private val handlers = CopyOnWriteArrayList<Handler>()
    private val reconnectHooks = CopyOnWriteArrayList<(first: Boolean) -> Unit>()
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var connection: Connection? = null

    fun onNotification(handler: Handler) {
        handlers += handler
    }

    /** Called after every (re)connect; `first` is true for the initial connection of this process. */
    fun onConnected(hook: (first: Boolean) -> Unit) {
        reconnectHooks += hook
    }

    fun isConnected(): Boolean = connected.get()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread.ofPlatform().name("pg-listen").daemon(true).start(::loop)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        runCatching { connection?.close() }
        thread?.let { runCatching { it.join(pollTimeout.toMillis() + JOIN_GRACE_MILLIS) } }
    }

    override fun isRunning(): Boolean = running.get()

    /** Starts early and stops late relative to the worker and the web server. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - PHASE_OFFSET

    /** Closes the current connection as if the network had dropped it (tests of the reconnect loop). */
    fun simulateConnectionLoss() {
        runCatching { connection?.close() }
    }

    private fun loop() {
        var first = true
        while (running.get()) {
            try {
                connect().use { c ->
                    subscribe(c, first)
                    first = false
                    dispatchUntilStopped(c)
                }
            } catch (e: Exception) {
                if (!running.get()) break
                log.warn("LISTEN connection lost, reconnecting in {} error={}", reconnectDelay, e.toString())
            } finally {
                connected.set(false)
                metrics?.listener(false)
                connection = null
            }
            if (!running.get() || !pauseBeforeReconnect()) break
        }
    }

    /** LISTENs on every channel of the fresh connection [c] and runs the reconnect hooks. */
    private fun subscribe(c: Connection, first: Boolean) {
        connection = c
        c.autoCommit = true
        c.createStatement().use { s -> channels.forEach { s.execute("LISTEN $it") } }
        connected.set(true)
        metrics?.listener(true)
        if (!first) metrics?.reconnected()
        log.info("listening on {}{}", channels, if (first) "" else " (reconnected)")
        reconnectHooks.forEach { hook -> runCatching { hook(first) }.onFailure { log.warn("listener reconnect hook failed: {}", it.toString()) } }
    }

    /** Polls [c] for notifications and hands them to the handlers until [stop]. */
    private fun dispatchUntilStopped(c: Connection) {
        val pg = c.unwrap(PGConnection::class.java)
        while (running.get()) {
            val notifications = pg.getNotifications(pollTimeout.toMillis().toInt().coerceAtLeast(1)) ?: continue
            if (notifications.isNotEmpty()) dispatch(notifications)
        }
    }

    /** Groups the payloads by channel; a failing handler does not stop the others. */
    private fun dispatch(notifications: Array<PGNotification>) {
        notifications.groupBy({ it.name }, { it.parameter ?: "" }).forEach { (channel, payloads) ->
            handlers.forEach { h ->
                try {
                    h.onNotifications(channel, payloads)
                } catch (e: Exception) {
                    log.warn("notification handler failed channel={} error={}", channel, e.toString(), e)
                }
            }
        }
    }

    /** Waits [reconnectDelay] before the next connection attempt; false when [stop] interrupted the wait. */
    private fun pauseBeforeReconnect(): Boolean = try {
        Thread.sleep(reconnectDelay.toMillis())
        true
    } catch (_: InterruptedException) {
        running.get()
    }

    private companion object {
        const val JOIN_GRACE_MILLIS = 2_000L
        const val PHASE_OFFSET = 256
    }
}
