package pl.mos.backoffice.llm.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.llm.Provider
import java.time.Duration
import java.time.Instant

/**
 * Two replicas (two limiters) on one PostgreSQL share the provider budget (ADR 0023): together they admit the
 * configured requests per minute, not twice that, and the interactive reserve holds across both.
 */
class DistributedRateLimiterTest {

    private class ManualClock(var time: Instant) : Clock {
        override fun now(): Instant = time
    }

    @Test
    fun `two replicas draw from one bucket per provider and keep the interactive reserve`() {
        PostgreSQLContainer("postgres:16-alpine").use { db ->
            db.start()
            val ds = DriverManagerDataSource(db.jdbcUrl, db.username, db.password)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val clock = ManualClock(Instant.parse("2026-09-28T10:00:00Z"))
            val limits = mapOf(Provider.ANTHROPIC to ProviderLimits(requestsPerMinute = 10, tokensPerMinute = 10_000, interactiveReserve = 0.2, maxWait = Duration.ZERO))
            val a = ProviderRateLimiter(limits, clock, RateBuckets.postgres(ds, clock)) { }
            val b = ProviderRateLimiter(limits, clock, RateBuckets.postgres(ds, clock)) { }

            repeat(4) { a.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 100) }
            repeat(4) { b.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 100) }
            // 8 of 10 used: batch calls leave the reserve of 2 to interactive work on either replica
            assertThatThrownBy { a.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 100) }.isInstanceOf(IntegrationException::class.java)
            assertThatThrownBy { b.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 100) }.isInstanceOf(IntegrationException::class.java)
            b.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 100)
            a.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 100).complete(50)
            assertThatThrownBy { a.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 100) }
                .isInstanceOf(IntegrationException::class.java).hasMessageContaining("rate limit")
            assertThat(b.usage(Provider.ANTHROPIC)).describedAs("usage seen from the other replica").isEqualTo(10L to 950L)

            // the next minute refills the shared bucket
            clock.time = clock.time.plusSeconds(61)
            b.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 100)
        }
    }
}
