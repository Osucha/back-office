package pl.mos.backoffice.llm.connections

import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.llm.gateway.ProviderEndpoint
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR 0046: the optional request limits of a provider connection (e.g. 50 free-model requests a day on OpenRouter), kept by
 * Back Office so that work stops before the provider refuses it. Counts every request sent through the connection, from
 * workflow steps, benchmarks and agents alike, in the database: all replicas share one count.
 */
interface ConnectionQuota {
    /**
     * Counts one request to [endpoint]. Throws [IntegrationException] when a limit is reached: per minute retryable,
     * per day persistent (every further call fails the same way until 00:00 UTC).
     */
    fun admit(endpoint: ProviderEndpoint)

    /** Requests counted today (UTC) per connection key. */
    fun usedToday(): Map<String, Int> = emptyMap()

    companion object {
        val NONE = object : ConnectionQuota {
            override fun admit(endpoint: ProviderEndpoint) = Unit
        }
    }
}

/** [ConnectionQuota] over `llm_connection_request_count` (V7): fixed UTC minute and day buckets per connection. */
class JdbcConnectionQuota(private val jdbc: JdbcClient, private val clock: Clock) : ConnectionQuota {

    private val lastCleanup = AtomicReference(Instant.EPOCH)

    override fun admit(endpoint: ProviderEndpoint) {
        val key = endpoint.connection ?: return
        val limits = endpoint.limits
        if (!limits.limited) return
        val now = clock.now()
        cleanup(now)
        val system = endpoint.provider.name.lowercase()
        val day = dayBucket(now)
        limits.requestsPerDay?.let { limit ->
            if (!take(key, day, limit, now)) {
                throw IntegrationException(
                    system, "daily request limit of connection $key reached ($limit a day, set in LLM providers); it resets at 00:00 UTC",
                    retryable = false, persistent = true,
                )
            }
        }
        limits.requestsPerMinute?.let { limit ->
            if (!take(key, "m:" + now.truncatedTo(ChronoUnit.MINUTES), limit, now)) {
                // the request is not sent: give its share of the day back
                if (limits.requestsPerDay != null) giveBack(key, day)
                throw IntegrationException(system, "request limit of connection $key reached ($limit a minute)", retryable = true)
            }
        }
    }

    override fun usedToday(): Map<String, Int> =
        jdbc.sql("SELECT connection_key, requests FROM llm_connection_request_count WHERE bucket = :day")
            .param("day", dayBucket(clock.now()))
            .query { rs, _ -> rs.getString("connection_key") to rs.getInt("requests") }
            .list().toMap()

    /** Adds one request to the bucket unless it already holds [limit]; false when the limit is reached. */
    private fun take(key: String, bucket: String, limit: Int, now: Instant): Boolean =
        jdbc.sql(
            """
            INSERT INTO llm_connection_request_count (connection_key, bucket, requests, updated_at)
            VALUES (:key, :bucket, 1, :now)
            ON CONFLICT (connection_key, bucket) DO UPDATE
                SET requests = llm_connection_request_count.requests + 1, updated_at = EXCLUDED.updated_at
                WHERE llm_connection_request_count.requests < :limit
            RETURNING requests
            """.trimIndent(),
        )
            .param("key", key).param("bucket", bucket).param("now", now.atOffset(ZoneOffset.UTC)).param("limit", limit)
            .query(Int::class.java).optional().isPresent

    private fun giveBack(key: String, bucket: String) {
        jdbc.sql("UPDATE llm_connection_request_count SET requests = requests - 1 WHERE connection_key = :key AND bucket = :bucket AND requests > 0")
            .param("key", key).param("bucket", bucket).update()
    }

    /** At most hourly per replica: buckets older than two days go. */
    private fun cleanup(now: Instant) {
        val last = lastCleanup.get()
        if (Duration.between(last, now) < CLEANUP_EVERY || !lastCleanup.compareAndSet(last, now)) return
        jdbc.sql("DELETE FROM llm_connection_request_count WHERE updated_at < :before")
            .param("before", now.minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC)).update()
    }

    private fun dayBucket(now: Instant) = "d:" + LocalDate.ofInstant(now, ZoneOffset.UTC)

    private companion object {
        val CLEANUP_EVERY: Duration = Duration.ofHours(1)
    }
}
