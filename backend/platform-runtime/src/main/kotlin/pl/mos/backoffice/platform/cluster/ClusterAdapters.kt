package pl.mos.backoffice.platform.cluster

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.ClusterSignals
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [ClusterLock] on ShedLock (`shedlock` table, database time). */
class ShedLockClusterLock(provider: LockProvider) : ClusterLock {

    private val log = LoggerFactory.getLogger(ShedLockClusterLock::class.java)
    private val executor = DefaultLockingTaskExecutor(provider)

    override fun runExclusively(name: String, lockAtMost: Duration, lockAtLeast: Duration, block: () -> Unit): Boolean {
        val config = LockConfiguration(Instant.now(), name, lockAtMost, lockAtLeast.coerceAtMost(lockAtMost))
        val result = executor.executeWithLock(LockingTaskExecutor.TaskWithResult<Unit> { block() }, config)
        if (!result.wasExecuted()) log.debug("skipped {}: another replica holds the lock", name)
        return result.wasExecuted()
    }
}

/**
 * [ClusterSignals] on channel `backoffice_cache`. The notification payload is `<instance>|<topic>|<key>`;
 * local subscribers are called at once (not through the round trip), remote replicas through their LISTEN
 * bridge, which ignores the notifications this instance sent itself.
 */
class PgClusterSignals(private val jdbc: JdbcClient, private val instanceId: String) : ClusterSignals {

    private val log = LoggerFactory.getLogger(PgClusterSignals::class.java)
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    override fun publish(topic: String, key: String) {
        require('|' !in topic) { "topic must not contain '|'" }
        dispatch(topic, key)
        try {
            val payload = "$instanceId|$topic|$key"
            require(payload.length < MAX_PAYLOAD) { "cluster signal too long" }
            jdbc.sql("SELECT pg_notify('$CHANNEL', :payload)").param("payload", payload).query { _: ResultSet, _: Int -> 1 }.list()
        } catch (e: RuntimeException) {
            log.warn("could not broadcast cluster signal {}:{}: {}", topic, key, e.toString())
        }
    }

    override fun subscribe(topic: String, listener: (key: String) -> Unit) {
        listeners.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(listener)
    }

    /** Payloads received on `backoffice_cache`. */
    fun onNotified(payloads: List<String>) {
        payloads.forEach { payload ->
            val parts = payload.split('|', limit = 3)
            if (parts.size != 3 || parts[0] == instanceId) return@forEach
            dispatch(parts[1], parts[2])
        }
    }

    /** After a reconnect every subscriber gets an empty key: "you may have missed something, invalidate all". */
    fun onReconnected(first: Boolean) {
        if (first) return
        listeners.keys.forEach { dispatch(it, "") }
    }

    private fun dispatch(topic: String, key: String) {
        listeners[topic]?.forEach { listener ->
            try {
                listener(key)
            } catch (e: Exception) {
                log.warn("cluster signal listener failed topic={} error={}", topic, e.toString())
            }
        }
    }

    companion object {
        const val CHANNEL = "backoffice_cache"
        private const val MAX_PAYLOAD = 7_900
    }
}
