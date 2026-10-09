package pl.mos.backoffice.llm.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.TimeMeter
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper
import io.github.bucket4j.distributed.jdbc.LockIdSupplier
import java.util.function.Supplier
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL
import pl.mos.backoffice.shared.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** One budget dimension of a provider: capacity per minute, refilled in full every minute. */
interface RateBucket {
    /** Consumes [tokens] when available; otherwise returns the nanoseconds until they are. 0 = consumed. */
    fun tryConsume(tokens: Long): Long

    /** Returns [tokens] to the bucket (estimate corrected downwards, refund of a partial acquire). */
    fun refund(tokens: Long)

    /** Consumes [tokens] even beyond the budget (estimate corrected upwards). */
    fun force(tokens: Long)

    fun available(): Long
}

/**
 * Where the provider buckets live (ADR 0020 / 0023): [local] per process (tests, single replica) or
 * [postgres] shared by every replica through bucket4j's PostgreSQL proxy (`llm_rate_bucket`, advisory-lock
 * based), so N replicas together respect the provider quota instead of N times it.
 */
fun interface RateBuckets {
    fun bucket(key: String, capacity: Long): RateBucket

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)

        fun configuration(capacity: Long): BucketConfiguration = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, WINDOW).build())
            .build()

        /** The clock of the limiter as bucket4j's time source, so tests stay deterministic. */
        fun timeMeter(clock: Clock): TimeMeter = object : TimeMeter {
            override fun currentTimeNanos(): Long = clock.now().let { TimeUnit.SECONDS.toNanos(it.epochSecond) + it.nano }
            override fun isWallClockBased(): Boolean = true
        }

        fun local(clock: Clock): RateBuckets {
            val buckets = ConcurrentHashMap<String, Bucket4jBucket>()
            val meter = timeMeter(clock)
            return RateBuckets { key, capacity ->
                buckets.computeIfAbsent("$key:$capacity") {
                    Bucket4jBucket(Bucket.builder().addLimit(configuration(capacity).bandwidths.single()).withCustomTimePrecision(meter).build())
                }
            }
        }

        fun postgres(dataSource: DataSource, clock: Clock): RateBuckets {
            val proxy: ProxyManager<String> = Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .lockIdSupplier(LockIdSupplier<String> { key -> key.hashCode().toLong() })
                .table("llm_rate_bucket")
                .idColumn("id")
                .stateColumn("state")
                .clientClock(timeMeter(clock))
                .build()
            val cache = ConcurrentHashMap<String, Bucket4jBucket>()
            // the capacity is part of the key: a changed limit starts a fresh bucket instead of fighting the stored one
            return RateBuckets { key, capacity ->
                cache.computeIfAbsent("$key:$capacity") { id -> Bucket4jBucket(proxy.builder().build(id, Supplier { configuration(capacity) })) }
            }
        }
    }
}

/** [RateBucket] over a bucket4j [Bucket] (local or a distributed proxy). */
class Bucket4jBucket(private val bucket: Bucket) : RateBucket {
    override fun tryConsume(tokens: Long): Long {
        val probe = bucket.tryConsumeAndReturnRemaining(tokens)
        return if (probe.isConsumed) 0 else probe.nanosToWaitForRefill.coerceAtLeast(1)
    }

    override fun refund(tokens: Long) {
        if (tokens > 0) bucket.addTokens(tokens)
    }

    override fun force(tokens: Long) {
        if (tokens > 0) bucket.consumeIgnoringRateLimits(tokens)
    }

    override fun available(): Long = bucket.availableTokens
}
