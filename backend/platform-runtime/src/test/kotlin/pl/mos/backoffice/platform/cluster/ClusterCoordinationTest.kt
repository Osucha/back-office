package pl.mos.backoffice.platform.cluster

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.platform.PlatformTestDatabase
import pl.mos.backoffice.platform.events.OutboxEventPublisher
import pl.mos.backoffice.platform.events.PgNotificationListener
import pl.mos.backoffice.platform.events.PlatformEventStream
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.platform.StoredEvent
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ShedLock singletons, the event_log outbox with the LISTEN/NOTIFY bridge and the cache channel, each with two
 * "replicas" (separate lock providers, separate LISTEN connections and streams) on one PostgreSQL.
 */
class ClusterCoordinationTest {

    private val db = PlatformTestDatabase
    private val listeners = CopyOnWriteArrayList<PgNotificationListener>()

    @AfterEach
    fun stop() = listeners.forEach { it.stop() }

    private fun lock(instance: String) = ShedLockClusterLock(
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(JdbcTemplate(db.dataSource)).withTableName("shedlock").usingDbTime().withLockedByValue(instance).build(),
        ),
    )

    private data class Plain(override val type: String, override val payload: Map<String, Any?>, override val occurredAt: Instant = Instant.now()) : DomainEvent

    /** One replica's bridge: its own LISTEN connection, event stream and cluster signals. */
    private inner class Replica(val name: String) {
        val stream = PlatformEventStream(db.jdbc, db.json)
        val signals = PgClusterSignals(db.jdbc, name)
        val received = CopyOnWriteArrayList<StoredEvent>()
        val listener = PgNotificationListener(db::connect, listOf(OutboxEventPublisher.CHANNEL, PgClusterSignals.CHANNEL), Duration.ofMillis(200), Duration.ofMillis(100))
        val connectedTimes = AtomicInteger()

        init {
            listener.onNotification { channel, payloads ->
                when (channel) {
                    OutboxEventPublisher.CHANNEL -> stream.onNotified(payloads)
                    PgClusterSignals.CHANNEL -> signals.onNotified(payloads)
                }
            }
            listener.onConnected { first ->
                stream.onReconnected(first)
                signals.onReconnected(first)
                connectedTimes.incrementAndGet()
            }
            stream.subscribe { received += it }
            listeners += listener
            listener.start()
            await { listener.isConnected() }
        }
    }

    private fun await(timeout: Duration = Duration.ofSeconds(15), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertThat(condition()).describedAs("condition not met within $timeout").isTrue()
    }

    @Test
    fun `ShedLock runs a singleton on exactly one of two replicas`() {
        val a = lock("replica-a")
        val b = lock("replica-b")
        val name = "seed-" + UUID.randomUUID().toString().take(8)
        val runs = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 8).map { i ->
            pool.submit<Boolean> {
                start.await()
                (if (i % 2 == 0) a else b).runExclusively(name, Duration.ofMinutes(1), Duration.ofSeconds(30)) {
                    runs.incrementAndGet()
                    Thread.sleep(300)
                }
            }
        }
        start.countDown()
        val executed = results.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(runs.get()).isEqualTo(1)
        assertThat(executed.count { it }).isEqualTo(1)
        // lockAtLeast keeps the lock after the run: a replica starting a moment later does not seed again
        assertThat(b.runExclusively(name, Duration.ofMinutes(1)) { runs.incrementAndGet() }).isFalse()
        assertThat(runs.get()).isEqualTo(1)
        // a different job name is independent
        assertThat(b.runExclusively("$name-other", Duration.ofMinutes(1)) { runs.incrementAndGet() }).isTrue()
    }

    @Test
    fun `an event published on replica A reaches the subscribers of replica B, rolled back events never do`() {
        val a = Replica("replica-a")
        val b = Replica("replica-b")
        val publisher = OutboxEventPublisher(db.jdbc, db.json, null)
        val marker = UUID.randomUUID().toString()
        val project = UUID.randomUUID()

        publisher.publish(Plain("TestEvent", mapOf("marker" to marker, "projectId" to project.toString(), "n" to 1)))

        await { b.received.any { it.payload["marker"] == marker } }
        val event = b.received.single { it.payload["marker"] == marker }
        assertThat(event.type).isEqualTo("TestEvent")
        assertThat(event.projectId).isEqualTo(project)
        assertThat(event.payload["n"]).isEqualTo(1)
        await { a.received.any { it.payload["marker"] == marker } }
        assertThat(b.stream.replayAfter(event.id - 1).first().id).isEqualTo(event.id)

        // outbox semantics: an event written in a transaction that rolls back is neither stored nor announced
        val rolledBack = UUID.randomUUID().toString()
        val tx = TransactionTemplate(DataSourceTransactionManager(db.dataSource))
        runCatching {
            tx.executeWithoutResult {
                OutboxEventPublisher(org.springframework.jdbc.core.simple.JdbcClient.create(db.dataSource), db.json, null)
                    .publish(Plain("TestEvent", mapOf("marker" to rolledBack)))
                throw IllegalStateException("rollback")
            }
        }
        val committed = UUID.randomUUID().toString()
        publisher.publish(Plain("TestEvent", mapOf("marker" to committed)))
        await { b.received.any { it.payload["marker"] == committed } }
        assertThat(b.received.none { it.payload["marker"] == rolledBack }).isTrue()
        assertThat(db.jdbc.sql("SELECT count(*) FROM event_log WHERE payload->>'marker' = :m").param("m", rolledBack).query(Long::class.javaObjectType).single()).isZero()
    }

    @Test
    fun `a lost LISTEN connection reconnects and delivers the events written meanwhile exactly once`() {
        val b = Replica("replica-reconnect")
        val publisher = OutboxEventPublisher(db.jdbc, db.json, null)
        val before = UUID.randomUUID().toString()
        publisher.publish(Plain("TestEvent", mapOf("marker" to before)))
        await { b.received.any { it.payload["marker"] == before } }

        b.listener.simulateConnectionLoss()
        await { !b.listener.isConnected() || b.connectedTimes.get() > 1 }
        val during = UUID.randomUUID().toString()
        publisher.publish(Plain("TestEvent", mapOf("marker" to during)))

        await { b.connectedTimes.get() >= 2 && b.listener.isConnected() }
        await { b.received.any { it.payload["marker"] == during } }
        Thread.sleep(300)
        assertThat(b.received.count { it.payload["marker"] == during }).isEqualTo(1)
        assertThat(b.received.count { it.payload["marker"] == before }).isEqualTo(1)
    }

    @Test
    fun `a cache signal reaches the other replica once and the local subscribers at once`() {
        val a = Replica("replica-sig-a")
        val b = Replica("replica-sig-b")
        val topic = "test-" + UUID.randomUUID().toString().take(8)
        val onA = CopyOnWriteArrayList<String>()
        val onB = CopyOnWriteArrayList<String>()
        a.signals.subscribe(topic) { onA += it }
        b.signals.subscribe(topic) { onB += it }

        a.signals.publish(topic, "user-1")

        assertThat(onA).containsExactly("user-1")
        await { onB.contains("user-1") }
        Thread.sleep(300)
        assertThat(onA).describedAs("own notification ignored").containsExactly("user-1")
        assertThat(onB).containsExactly("user-1")
    }
}
