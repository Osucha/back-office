package pl.mos.backoffice.platform.queue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.platform.PlatformTestDatabase
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkPayload
import pl.mos.backoffice.shared.platform.WorkRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The work queue under concurrency (ADR 0023): two worker loops (two "replicas", distinct lease owners) on one
 * PostgreSQL. Every test uses its own work kind so tests do not see each other's items.
 */
class WorkQueueConcurrencyTest {

    private val db = PlatformTestDatabase
    private val queue = JdbcWorkQueue(db.jdbc, db.json)
    private val loops = CopyOnWriteArrayList<WorkerLoop>()

    @AfterEach
    fun stopLoops() = loops.forEach { it.stop() }

    private fun kind() = "TEST_" + UUID.randomUUID().toString().take(8).uppercase()

    private fun loop(owner: String, handler: WorkHandler, lease: Duration = Duration.ofSeconds(30), concurrency: Int = 8) =
        WorkerLoop(
            queue, listOf(handler),
            WorkerLoop.Settings(owner = owner, concurrency = concurrency, lease = lease, pollInterval = Duration.ofMillis(50), claimBatch = 4, drainTimeout = Duration.ofSeconds(2), initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(50)),
        ).also { loops += it }

    private fun handler(kind: String, body: (WorkItem) -> WorkOutcome) = object : WorkHandler {
        override val kind = kind
        override fun handle(item: WorkItem) = body(item)
    }

    private fun statusOf(kind: String): Map<String, Long> =
        db.jdbc.sql("SELECT status, count(*) AS n FROM work_item WHERE kind = :k GROUP BY status").param("k", kind)
            .query { rs, _ -> rs.getString("status") to rs.getLong("n") }.list().toMap()

    private fun await(timeout: Duration = Duration.ofSeconds(20), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertThat(condition()).describedAs("condition not met within $timeout").isTrue()
    }

    @Test
    fun `two workers handle every item exactly once`() {
        val kind = kind()
        val handledBy = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        fun worker(name: String) = loop(name, handler(kind) { item ->
            handledBy.computeIfAbsent(item.payload["n"].toString()) { CopyOnWriteArrayList() } += name
            Thread.sleep(5)
            WorkOutcome.Done
        })
        val a = worker("worker-a")
        val b = worker("worker-b")
        repeat(200) { queue.enqueue(WorkRequest(kind, mapOf("n" to it))) }

        a.start()
        b.start()

        await { statusOf(kind)["DONE"] == 200L }
        assertThat(handledBy).hasSize(200)
        assertThat(handledBy.values.map { it.size }.toSet()).describedAs("no item handled twice").containsExactly(1)
        assertThat(handledBy.values.flatten().toSet()).describedAs("both replicas took part").containsExactlyInAnyOrder("worker-a", "worker-b")
    }

    @Test
    fun `a dedupe key merges payloads while the item is READY`() {
        val kind = kind()
        queue.enqueue(WorkRequest(kind, mapOf("runId" to "r1", "resume" to mapOf("kind" to "START")), dedupeKey = "$kind:r1"))
        queue.enqueue(WorkRequest(kind, mapOf("resume" to mapOf("kind" to "DECISION"), "extra" to 1), dedupeKey = "$kind:r1"))
        queue.enqueue(WorkRequest(kind, mapOf("resume" to mapOf("kind" to "RECOVER"), WorkPayload.MERGE to WorkPayload.KEEP_EXISTING), dedupeKey = "$kind:r1"))

        val rows = db.jdbc.sql("SELECT payload::text FROM work_item WHERE kind = :k").param("k", kind).query(String::class.java).list()
        assertThat(rows).hasSize(1)
        @Suppress("UNCHECKED_CAST")
        val payload = db.json.readValue(rows.single(), Map::class.java) as Map<String, Any?>
        assertThat(payload).containsEntry("runId", "r1").containsEntry("extra", 1).containsEntry("resume", mapOf("kind" to "DECISION"))
    }

    @Test
    fun `a request merged into a LEASED item runs it again after the current run`() {
        val kind = kind()
        val seen = CopyOnWriteArrayList<Any?>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = loop("worker-merge", handler(kind) { item ->
            seen += (item.payload["resume"] as Map<*, *>)["kind"]
            if (seen.size == 1) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            WorkOutcome.Done
        })
        queue.enqueue(WorkRequest(kind, mapOf("resume" to mapOf("kind" to "START")), dedupeKey = "$kind:x"))
        worker.start()
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

        // while the first run holds the lease, a resume signal arrives for the same key
        queue.enqueue(WorkRequest(kind, mapOf("resume" to mapOf("kind" to "DECISION")), dedupeKey = "$kind:x"))
        release.countDown()

        await { seen.size == 2 && statusOf(kind)["DONE"] == 1L }
        assertThat(seen).containsExactly("START", "DECISION")
        assertThat(statusOf(kind)).isEqualTo(mapOf("DONE" to 1L))
    }

    @Test
    fun `an item becomes DEAD after max attempts and an operator retry gives it a new budget`() {
        val kind = kind()
        val calls = AtomicInteger()
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val worker = loop("worker-dead", handler(kind) { _ ->
            calls.incrementAndGet()
            if (fail.get()) throw IllegalStateException("boom") else WorkOutcome.Done
        })
        queue.enqueue(WorkRequest(kind, maxAttempts = 3))
        worker.start()

        await { statusOf(kind)["DEAD"] == 1L }
        assertThat(calls.get()).isEqualTo(3)
        val row = db.jdbc.sql("SELECT id, attempts, last_error FROM work_item WHERE kind = :k").param("k", kind)
            .query { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getInt("attempts"), rs.getString("last_error")) }.single()
        assertThat(row.second).isEqualTo(3)
        assertThat(row.third).contains("boom")

        fail.set(false)
        assertThat(queue.requeueDead(row.first)).isTrue()
        worker.wakeUp()
        await { statusOf(kind)["DONE"] == 1L }
        assertThat(calls.get()).isEqualTo(4)
        assertThat(queue.requeueDead(row.first)).describedAs("only DEAD items").isFalse()
    }

    @Test
    fun `a crashed worker's expired lease is picked up by the other worker and the late completion is refused`() {
        val kind = kind()
        queue.enqueue(WorkRequest(kind, mapOf("job" to "crash-me")))
        // worker A claims the item and then "crashes": no heartbeat, no completion
        val claimedByA = queue.claim("worker-a", listOf(kind), 1, Duration.ofSeconds(30)).single()
        assertThat(queue.claim("worker-b", listOf(kind), 1, Duration.ofSeconds(30))).describedAs("leased items are not claimable").isEmpty()

        // the lease runs out
        db.jdbc.sql("UPDATE work_item SET lease_until = now() - interval '1 second' WHERE id = :id").param("id", claimedByA.item.id).update()

        val handledByB = CopyOnWriteArrayList<Int>()
        val b = loop("worker-b", handler(kind) { item -> handledByB += item.attempt; WorkOutcome.Done })
        b.start()
        await { statusOf(kind)["DONE"] == 1L }
        assertThat(handledByB).describedAs("second attempt, on worker B").containsExactly(2)

        assertThat(queue.complete("worker-a", claimedByA)).isEqualTo(CompletionStatus.LOST)
        assertThat(statusOf(kind)).isEqualTo(mapOf("DONE" to 1L))
    }

    @Test
    fun `a lost lease interrupts the stale handler on the old owner`() {
        val kind = kind()
        val interrupted = CountDownLatch(1)
        val started = CountDownLatch(1)
        val a = loop("worker-slow", handler(kind) { _ ->
            started.countDown()
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            WorkOutcome.Done
        }, lease = Duration.ofMillis(900))
        queue.enqueue(WorkRequest(kind))
        a.start()
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
        // another replica took the item over (as after an expiry it did not notice)
        db.jdbc.sql("UPDATE work_item SET lease_owner = 'worker-other' WHERE kind = :k").param("k", kind).update()
        assertThat(interrupted.await(10, TimeUnit.SECONDS)).describedAs("heartbeat notices the lost lease").isTrue()
    }

    @Test
    fun `retry outcomes back off and graceful stop releases unfinished leases without counting the attempt`() {
        val kind = kind()
        val calls = AtomicInteger()
        val worker = loop("worker-retry", handler(kind) { _ ->
            if (calls.incrementAndGet() < 3) WorkOutcome.Retry(Duration.ofMillis(100), "not yet") else WorkOutcome.Done
        })
        queue.enqueue(WorkRequest(kind))
        worker.start()
        await { statusOf(kind)["DONE"] == 1L }
        assertThat(calls.get()).isEqualTo(3)

        val slowKind = kind()
        val started = CountDownLatch(1)
        val slow = loop("worker-stop", handler(slowKind) { _ ->
            started.countDown()
            Thread.sleep(60_000)
            WorkOutcome.Done
        })
        queue.enqueue(WorkRequest(slowKind))
        slow.start()
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
        slow.stop()
        val row = db.jdbc.sql("SELECT status, attempts, lease_owner FROM work_item WHERE kind = :k").param("k", slowKind)
            .query { rs, _ -> Triple(rs.getString("status"), rs.getInt("attempts"), rs.getString("lease_owner")) }.single()
        assertThat(row).isEqualTo(Triple("READY", 0, null))
    }
}
