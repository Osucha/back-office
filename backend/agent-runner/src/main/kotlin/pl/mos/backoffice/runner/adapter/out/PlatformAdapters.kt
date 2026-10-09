package pl.mos.backoffice.runner.adapter.out

import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.runner.RunnerResultJson
import pl.mos.backoffice.runner.application.port.out.LeaseSignal
import pl.mos.backoffice.runner.application.port.out.ResultArchive
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.PresignedUrl
import pl.mos.backoffice.shared.runner.RunnerResult
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** [Transactions] over Spring's `TransactionTemplate` (REQUIRED: joins a caller's transaction). */
class SpringTransactions(private val template: TransactionTemplate) : Transactions {
    @Suppress("UNCHECKED_CAST")
    override fun <T> inTransaction(block: () -> T): T = template.execute { block() } as T
}

/**
 * In-process wake-ups of lease long polls, one condition per pool. Only this replica's submissions wake a
 * poll early; the poll re-checks the database every `lease-poll-interval` anyway, which covers the others.
 */
class InProcessLeaseSignal : LeaseSignal {
    private class Slot {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        var generation = 0L
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    private fun slot(poolId: String) = slots.computeIfAbsent(poolId) { Slot() }

    override fun wake(poolId: String?) {
        val targets = if (poolId == null) slots.values.toList() else listOf(slot(poolId))
        targets.forEach { s -> s.lock.withLock { s.generation++; s.condition.signalAll() } }
    }

    override fun await(poolId: String, timeoutMillis: Long) {
        if (timeoutMillis <= 0) return
        val s = slot(poolId)
        s.lock.withLock {
            val seen = s.generation
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (s.generation == seen && remaining > 0) remaining = s.condition.awaitNanos(remaining)
        }
    }
}

/** Large results and uploads in the shared [BlobStore] under `agent-jobs/<jobId>/...`; inline only without one. */
class BlobResultArchive(private val blobs: () -> BlobStore?, private val json: JsonMapper) : ResultArchive {

    override fun sizeOf(result: RunnerResult): Int = json.writeValueAsBytes(RunnerResultJson.of(result)).size

    override fun store(jobId: RunnerJobId, result: RunnerResult): String? {
        val store = blobs() ?: return null
        val key = "agent-jobs/$jobId/result.json"
        store.put(key, "application/json", json.writeValueAsBytes(RunnerResultJson.of(result)))
        return key
    }

    override fun read(blobKey: String): RunnerResult? {
        val text = blobs()?.readText(blobKey, MAX_RESULT_BYTES) ?: return null
        return json.readValue(text, RunnerResultJson::class.java).toRunnerResult(Instant.EPOCH)
    }

    override fun presignUpload(key: String, contentType: String, sizeBytes: Long, ttl: Duration): PresignedUrl? =
        blobs()?.presignPut(key, contentType, sizeBytes, ttl)

    companion object {
        const val MAX_RESULT_BYTES = 64 * 1024 * 1024
    }
}
