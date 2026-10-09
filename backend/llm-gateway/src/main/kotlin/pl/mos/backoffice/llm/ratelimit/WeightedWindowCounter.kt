package pl.mos.backoffice.llm.ratelimit

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Sliding-window log of weighted events (ADR 0020).
 * A request counts 1, a token budget counts the estimated tokens; an entry's weight can be corrected
 * later ([Entry.weight]) when the actual usage is known. Time is a parameter of every operation, so the
 * counter is deterministic without a clock; the window is half-open (an entry exactly `length` old no
 * longer counts). Not thread-safe: the limiter synchronises around it.
 */
class WeightedWindowCounter(private val length: Duration) {

    class Entry internal constructor(val at: Instant, weight: Long) {
        var weight: Long = weight
            internal set
    }

    private val entries = ArrayDeque<Entry>()

    fun used(now: Instant): Long {
        evict(now)
        return entries.sumOf { it.weight }
    }

    /**
     * Whether [weight] fits under [limit] now. A single event heavier than the whole limit is admitted only
     * into an empty window; otherwise it could never run.
     */
    fun hasCapacity(now: Instant, limit: Long, weight: Long): Boolean {
        val used = used(now)
        return used + weight <= limit || (weight > limit && used == 0L)
    }

    fun record(now: Instant, weight: Long): Entry {
        evict(now)
        return Entry(now, weight.coerceAtLeast(0)).also { entries.addLast(it) }
    }

    /** Corrects the weight of a recorded entry (estimate replaced by the actual value). */
    fun correct(entry: Entry, weight: Long) {
        entry.weight = weight.coerceAtLeast(0)
    }

    /** Earliest moment at which [weight] fits: entries leave the window oldest first. */
    fun nextFreeAt(now: Instant, limit: Long, weight: Long): Instant {
        var used = used(now)
        if (used + weight <= limit || (weight > limit && used == 0L)) return now
        for (entry in entries) {
            used -= entry.weight
            val freeAt = entry.at.plus(length)
            if (used + weight <= limit || (weight > limit && used == 0L)) return freeAt
        }
        return entries.peekLast()?.at?.plus(length) ?: now
    }

    private fun evict(now: Instant) {
        val boundary = now.minus(length)
        while (entries.isNotEmpty() && !entries.peekFirst().at.isAfter(boundary)) entries.removeFirst()
    }
}
