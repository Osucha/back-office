package pl.mos.backoffice.identity.adapter.`in`.web

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttle of the local login endpoints: [SlidingWindowThrottle] in memory (tests, one process) or
 * `JdbcLoginThrottle` in PostgreSQL, shared by every replica (ADR 0023).
 */
interface LoginThrottle {
    /** Records an attempt; returns `null` when allowed, otherwise how long to wait. */
    fun tryAcquire(key: String): Duration?

    fun reset()
}

/** Remembers which throttled bursts were audited already, so only the first refused request of a burst is audited. */
interface ThrottleAuditMemory {
    /** True for the first call per [key] until [wait] has passed. */
    fun firstOfBurst(key: String, wait: Duration): Boolean
}

/** Per-replica [ThrottleAuditMemory] (tests, single process). */
class InMemoryThrottleAuditMemory : ThrottleAuditMemory {
    private val audited = ConcurrentHashMap<String, Long>()

    override fun firstOfBurst(key: String, wait: Duration): Boolean {
        val now = System.nanoTime()
        val previous = audited[key]
        if (previous != null && previous - now > 0) return false
        audited[key] = now + wait.toNanos()
        if (audited.size > MEMORY) audited.entries.removeIf { it.value - System.nanoTime() <= 0 }
        return true
    }

    private companion object {
        const val MEMORY = 10_000
    }
}
