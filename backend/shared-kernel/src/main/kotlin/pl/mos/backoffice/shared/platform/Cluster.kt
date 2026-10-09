package pl.mos.backoffice.shared.platform

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// ------------------------------------------------------------------------------------------------
// Cluster coordination ports (ADR 0023, docs/architecture/09-scale-and-operations.md). Implemented by
// `platform-runtime` on PostgreSQL (ShedLock, LISTEN/NOTIFY, event_log); every port has an in-process
// fallback so module tests and single-process tools run without the platform module.
// ------------------------------------------------------------------------------------------------

/** Singleton execution across replicas (seeding, sweeps, recovery scans, retention). */
interface ClusterLock {
    /**
     * Runs [block] only when no other replica holds the lock [name]; returns false (without running) otherwise.
     * [lockAtMost] bounds how long a crashed holder blocks others; [lockAtLeast] keeps the lock after a fast
     * run so that replicas starting a moment later do not repeat the work.
     */
    fun runExclusively(name: String, lockAtMost: Duration, lockAtLeast: Duration = Duration.ZERO, block: () -> Unit): Boolean

    companion object {
        /** No coordination: always runs (one process). */
        val LOCAL: ClusterLock = object : ClusterLock {
            override fun runExclusively(name: String, lockAtMost: Duration, lockAtLeast: Duration, block: () -> Unit): Boolean {
                block()
                return true
            }
        }
    }
}

/**
 * Broadcast of small invalidation signals to every replica (channel `backoffice_cache`). Publishing inside a
 * database transaction delivers the signal after commit. Subscribers of the publishing replica receive the
 * signal too. Signals are best effort: caches must still expire on their own.
 */
interface ClusterSignals {
    fun publish(topic: String, key: String = "")

    fun subscribe(topic: String, listener: (key: String) -> Unit)

    companion object {
        /** In-process delivery only. */
        fun local(): ClusterSignals = object : ClusterSignals {
            private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
            override fun publish(topic: String, key: String) {
                listeners[topic]?.forEach { runCatching { it(key) } }
            }
            override fun subscribe(topic: String, listener: (key: String) -> Unit) {
                listeners.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(listener)
            }
        }
    }
}

/** A domain event as stored in the `event_log` outbox; [id] is the SSE event id. */
data class StoredEvent(
    val id: Long,
    val type: String,
    val projectId: UUID?,
    val occurredAt: Instant,
    val payload: Map<String, Any?>,
)

/**
 * Durable, cluster-wide event stream: every `DomainEvent` published on any replica (outbox + NOTIFY) is
 * delivered to the subscribers of every replica; [replayAfter] serves `Last-Event-ID` reconnects.
 */
interface EventStream {
    /** Registers a subscriber; closing the handle unsubscribes. Listeners run on the bridge thread and must not block. */
    fun subscribe(listener: (StoredEvent) -> Unit): AutoCloseable

    /** Events with an id greater than [lastId], oldest first, at most [limit]. */
    fun replayAfter(lastId: Long, limit: Int = DEFAULT_REPLAY_LIMIT): List<StoredEvent>

    /** Number of subscribers on this replica. */
    fun subscribers(): Int

    companion object {
        const val DEFAULT_REPLAY_LIMIT = 500
    }
}

/** Conventions of work-item payloads understood by the queue itself. */
object WorkPayload {
    /**
     * Reserved payload key: with value [KEEP_EXISTING], enqueueing while a live item with the same dedupe key
     * exists leaves that item untouched (used by recovery scans, which must not overwrite a pending resume).
     */
    const val MERGE = "_merge"
    const val KEEP_EXISTING = "keep"
}

/** Optional statistics contributed by the runner control plane for the operations overview. */
fun interface AgentJobStatistics {
    fun countsByStatus(): Map<String, Int>
}
