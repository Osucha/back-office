package pl.mos.backoffice.platform.queue

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.SmartLifecycle
import pl.mos.backoffice.platform.metrics.PlatformMetrics
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkOutcome
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The worker role's consumer of `work_item` (ADR 0023). One dispatcher thread reclaims expired leases,
 * claims READY items of the kinds it has [WorkHandler]s for (up to [Settings.concurrency] in flight) and runs
 * every item on its own virtual thread; a heartbeat extends the leases every lease/3. Wake-ups come from
 * `NOTIFY backoffice_work` ([wakeUp]) and a poll every [Settings.pollInterval].
 *
 * Outcomes: `Done` -> DONE, `Retry` -> READY after the delay (DEAD when out of attempts), `Dead` -> DEAD; an
 * exception is a retry with exponential backoff. A lease that was lost meanwhile (the item was reclaimed by
 * another worker after an expiry) is logged and its completion is refused by the queue.
 *
 * Graceful shutdown ([stop]): stop claiming, wait up to [Settings.drainTimeout] for in-flight items, then
 * release the remaining leases (without counting the attempt) and interrupt their threads.
 */
class WorkerLoop(
    private val queue: JdbcWorkQueue,
    handlers: List<WorkHandler>,
    private val settings: Settings,
    private val metrics: PlatformMetrics? = null,
) : SmartLifecycle {

    data class Settings(
        val owner: String,
        val concurrency: Int = 16,
        val lease: Duration = Duration.ofSeconds(60),
        val pollInterval: Duration = Duration.ofSeconds(2),
        val claimBatch: Int = 8,
        val drainTimeout: Duration = Duration.ofSeconds(30),
        val initialBackoff: Duration = Duration.ofSeconds(5),
        val maxBackoff: Duration = Duration.ofMinutes(5),
    ) {
        /** Delay after the [attempt]-th failure (1-based): initial * 2^(attempt-1), capped at max. */
        fun backoff(attempt: Int): Duration {
            val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
            val millis = initialBackoff.toMillis().coerceAtLeast(1) * (1L shl shift)
            return Duration.ofMillis(millis.coerceAtMost(maxBackoff.toMillis()))
        }
    }

    private class InFlight(val leased: LeasedItem, @Volatile var thread: Thread? = null) {
        @Volatile var lost = false
        @Volatile var released = false
        /** Set before the completion write: a heartbeat racing with it must not take the item for lost. */
        @Volatile var completing = false
    }

    private val log = LoggerFactory.getLogger(WorkerLoop::class.java)
    private val handlers: Map<String, WorkHandler> = handlers.associateBy { it.kind }.also { byKind ->
        val duplicates = handlers.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "more than one WorkHandler for kinds $duplicates" }
        check(byKind.keys.none { it.isBlank() }) { "a WorkHandler has a blank kind" }
    }
    private val slots = Semaphore(settings.concurrency.coerceAtLeast(1))
    private val wake = Semaphore(0)
    private val running = AtomicBoolean(false)
    private val inflight = ConcurrentHashMap<UUID, InFlight>()
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("work-", 0).factory())
    private var dispatcher: Thread? = null
    private var heartbeats: ScheduledExecutorService? = null

    val owner: String get() = settings.owner
    val kinds: Set<String> get() = handlers.keys

    /** Number of items this worker is executing now. */
    fun inFlight(): Int = inflight.size

    /** Called on `NOTIFY backoffice_work`: claim without waiting for the next poll. */
    fun wakeUp() {
        if (wake.availablePermits() == 0) wake.release()
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        if (handlers.isEmpty()) log.info("worker {} has no work handlers; it will only reclaim expired leases", owner)
        heartbeats = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "work-heartbeat").apply { isDaemon = true } }.also {
            val period = (settings.lease.toMillis() / HEARTBEATS_PER_LEASE).coerceAtLeast(MIN_HEARTBEAT_MILLIS)
            it.scheduleWithFixedDelay(::heartbeat, period, period, TimeUnit.MILLISECONDS)
        }
        dispatcher = Thread.ofPlatform().name("work-dispatcher").daemon(true).start(::dispatchLoop)
        log.info("worker started owner={} kinds={} concurrency={} lease={}", owner, handlers.keys, settings.concurrency, settings.lease)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        wakeUp()
        dispatcher?.let { runCatching { it.join(settings.pollInterval.toMillis() + JOIN_GRACE_MILLIS) } }
        val deadline = System.nanoTime() + settings.drainTimeout.toNanos()
        while (inflight.isNotEmpty() && System.nanoTime() < deadline) {
            runCatching { Thread.sleep(DRAIN_POLL_MILLIS) }
        }
        val remaining = inflight.values.toList()
        if (remaining.isNotEmpty()) {
            remaining.forEach { it.released = true }
            val released = runCatching { queue.release(owner, remaining.map { it.leased.item.id }) }.getOrDefault(0)
            log.warn("worker {} released {} leases at shutdown (in flight after {}): {}", owner, released, settings.drainTimeout, remaining.map { it.leased.item.kind })
            remaining.forEach { it.thread?.interrupt() }
        }
        heartbeats?.shutdownNow()
        executor.shutdown()
        log.info("worker stopped owner={}", owner)
    }

    override fun isRunning(): Boolean = running.get()

    /** Stops before the web server and the data source (lifecycles stop in descending phase order). */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - PHASE_OFFSET

    /** Runs one poll cycle on the calling thread (tests). Returns the number of claimed items. */
    fun pollOnce(): Int {
        runCatching { queue.reclaimExpired() }
            .onSuccess { reclaimed -> reclaimed.forEach { (id, status) -> log.warn("work item lease expired id={} now={}", id, status) } }
            .onFailure { log.warn("reclaiming expired leases failed: {}", it.toString()) }
        val free = slots.availablePermits().coerceAtMost(settings.claimBatch)
        if (free <= 0 || handlers.isEmpty()) return 0
        val claimed = queue.claim(owner, handlers.keys, free, settings.lease)
        claimed.forEach { leased ->
            slots.acquireUninterruptibly()
            val entry = InFlight(leased)
            inflight[leased.item.id] = entry
            metrics?.pickedUp(leased.item.kind, leased.waitedSeconds)
            try {
                executor.execute { execute(entry) }
            } catch (e: RuntimeException) {
                inflight.remove(leased.item.id)
                slots.release()
                runCatching { queue.release(owner, listOf(leased.item.id)) }
                throw e
            }
        }
        return claimed.size
    }

    private fun dispatchLoop() {
        while (running.get()) {
            val claimed = try {
                pollOnce()
            } catch (e: Exception) {
                log.warn("worker poll failed owner={} error={}", owner, e.toString())
                0
            }
            if (!running.get()) break
            if (claimed < settings.claimBatch || slots.availablePermits() == 0) {
                try {
                    wake.tryAcquire(settings.pollInterval.toMillis(), TimeUnit.MILLISECONDS)
                    wake.drainPermits()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun execute(entry: InFlight) {
        val item = entry.leased.item
        entry.thread = Thread.currentThread()
        val handler = handlers.getValue(item.kind)
        val started = System.nanoTime()
        MDC.put("workItemId", item.id.toString())
        MDC.put("workKind", item.kind)
        try {
            val outcome = try {
                handler.handle(item)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                WorkOutcome.Retry(Duration.ZERO, "interrupted")
            } catch (e: Throwable) {
                log.error("work handler failed kind={} id={} attempt={} error={}", item.kind, item.id, item.attempt, e.toString(), e)
                WorkOutcome.Retry(settings.backoff(item.attempt), "${e.javaClass.simpleName}: ${e.message}")
            }
            entry.completing = true
            if (entry.released) {
                log.info("work item released during shutdown, result dropped kind={} id={}", item.kind, item.id)
                return
            }
            val status = runCatching {
                when (outcome) {
                    WorkOutcome.Done -> queue.complete(owner, entry.leased)
                    is WorkOutcome.Retry -> queue.retry(owner, entry.leased, outcome.after, outcome.reason)
                    is WorkOutcome.Dead -> queue.dead(owner, entry.leased, outcome.reason)
                }
            }.onFailure { log.error("completing work item failed kind={} id={} error={}", item.kind, item.id, it.toString()) }.getOrNull()
            when (status) {
                CompletionStatus.LOST -> log.warn("work item lease lost before completion kind={} id={} outcome={}", item.kind, item.id, outcome)
                CompletionStatus.DEAD -> log.error("work item is DEAD kind={} id={} attempts={} reason={}", item.kind, item.id, item.attempt, (outcome as? WorkOutcome.Retry)?.reason ?: (outcome as? WorkOutcome.Dead)?.reason)
                else -> Unit
            }
            metrics?.finished(item.kind, outcomeName(outcome, status), Duration.ofNanos(System.nanoTime() - started))
        } finally {
            MDC.remove("workItemId")
            MDC.remove("workKind")
            entry.thread = null
            inflight.remove(item.id)
            slots.release()
            if (running.get()) wakeUp()
        }
    }

    private fun heartbeat() {
        val entries = inflight.values.toList()
        val ids = entries.map { it.leased.item.id }
        if (ids.isEmpty()) return
        try {
            val lost = queue.heartbeat(owner, ids, settings.lease)
            lost.forEach { id ->
                entries.firstOrNull { it.leased.item.id == id && !it.completing && inflight[id] === it }?.let {
                    if (!it.lost) {
                        // another worker owns the item now: stop our copy so the work is not done twice
                        log.warn("work item lease lost, interrupting owner={} id={} kind={}", owner, id, it.leased.item.kind)
                        it.lost = true
                        it.thread?.interrupt()
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("work lease heartbeat failed owner={} error={}", owner, e.toString())
        }
    }

    private fun outcomeName(outcome: WorkOutcome, status: CompletionStatus?): String = when {
        status == CompletionStatus.LOST -> "lost"
        status == CompletionStatus.DEAD -> "dead"
        outcome is WorkOutcome.Retry -> "retry"
        outcome is WorkOutcome.Dead -> "dead"
        else -> "done"
    }

    private companion object {
        const val MAX_SHIFT = 20
        const val HEARTBEATS_PER_LEASE = 3
        const val MIN_HEARTBEAT_MILLIS = 100L
        const val JOIN_GRACE_MILLIS = 1_000L
        const val DRAIN_POLL_MILLIS = 50L
        const val PHASE_OFFSET = 512
    }
}
