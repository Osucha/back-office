package pl.mos.backoffice.llm.connections

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.llm.gateway.ConnectionLimits
import pl.mos.backoffice.llm.gateway.ProviderEndpoint
import pl.mos.backoffice.llm.gateway.ProviderErrorClassifier
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.llm.Provider
import java.time.Instant

/** ADR 0046: request limits of a provider connection, shared by all replicas; quota errors stop instead of retrying. */
class ConnectionQuotaTest {

    private class ManualClock(var time: Instant) : Clock {
        override fun now(): Instant = time
    }

    private fun endpoint(limits: ConnectionLimits, key: String? = "openrouter") =
        ProviderEndpoint("db:$key", Provider.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = "https://x"), key, limits)

    @Test
    fun `replicas share the daily and per-minute counts of a connection`() {
        PostgreSQLContainer("postgres:16-alpine").use { db ->
            db.start()
            val ds = DriverManagerDataSource(db.jdbcUrl, db.username, db.password)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val clock = ManualClock(Instant.parse("2026-10-19T23:58:30Z"))
            val a = JdbcConnectionQuota(JdbcClient.create(ds), clock)
            val b = JdbcConnectionQuota(JdbcClient.create(ds), clock)
            val limited = endpoint(ConnectionLimits(requestsPerMinute = 2, requestsPerDay = 3))

            a.admit(limited)
            b.admit(limited)
            // third in the same minute: retryable, and it does not use up the day
            assertThatThrownBy { a.admit(limited) }.isInstanceOfSatisfying(IntegrationException::class.java) {
                assertThat(it.retryable).isTrue()
                assertThat(it.persistent).isFalse()
            }
            assertThat(b.usedToday()).containsEntry("openrouter", 2)

            clock.time = clock.time.plusSeconds(60)
            b.admit(limited)
            assertThatThrownBy { a.admit(limited) }.isInstanceOfSatisfying(IntegrationException::class.java) {
                assertThat(it.retryable).isFalse()
                assertThat(it.persistent).isTrue()
                assertThat(it.message).contains("daily request limit", "00:00 UTC")
            }

            // a new UTC day starts over; connections without limits and environment endpoints are not counted
            clock.time = Instant.parse("2026-10-20T00:00:05Z")
            a.admit(limited)
            repeat(5) { a.admit(endpoint(ConnectionLimits.NONE, "groq")) }
            a.admit(endpoint(ConnectionLimits(requestsPerDay = 1), key = null))
            assertThat(a.usedToday()).containsOnly(org.assertj.core.api.Assertions.entry("openrouter", 1))
        }
    }

    @Test
    fun `a used up daily quota or a refused model is persistent, a plain 429 is retried`() {
        val daily = ProviderErrorClassifier.status(429, "Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day")
        assertThat(daily.retryable).isFalse()
        assertThat(daily.persistent).isTrue()
        assertThat(daily.message).contains("quota used up")

        val perMinute = ProviderErrorClassifier.status(429, "Rate limit exceeded: free-models-per-min.")
        assertThat(perMinute.retryable).isTrue()
        assertThat(perMinute.persistent).isFalse()

        assertThat(ProviderErrorClassifier.status(403, "only available on agentic harnesses").persistent).isTrue()
        assertThat(ProviderErrorClassifier.status(503, "overloaded").let { it.retryable && !it.persistent }).isTrue()
    }
}
