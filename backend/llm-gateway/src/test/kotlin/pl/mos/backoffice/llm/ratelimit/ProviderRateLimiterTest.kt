package pl.mos.backoffice.llm.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.usage.UsageKind
import java.time.Duration
import java.time.Instant

/** ADR 0020: weighted sliding windows, interactive reserve, estimate correction and bounded waits, on a manual clock. */
class ProviderRateLimiterTest {

    private val start: Instant = Instant.parse("2026-09-27T10:00:00Z")

    private class ManualClock(var time: Instant) : Clock {
        override fun now(): Instant = time
    }

    private val clock = ManualClock(start)
    private val sleeps = mutableListOf<Duration>()
    private val sleeper: (Duration) -> Unit = { sleeps += it; clock.time = clock.time.plus(it) }

    private fun limiter(limits: ProviderLimits) = ProviderRateLimiter(mapOf(Provider.ANTHROPIC to limits), clock, sleeper)

    private fun at(seconds: Long) {
        clock.time = start.plusSeconds(seconds)
    }

    @Test
    fun `window counter weighs events, forgets them after one minute and knows when capacity frees`() {
        val counter = WeightedWindowCounter(Duration.ofMinutes(1))
        counter.record(start, 600)
        counter.record(start.plusSeconds(20), 300)

        assertThat(counter.used(start.plusSeconds(30))).isEqualTo(900)
        assertThat(counter.hasCapacity(start.plusSeconds(30), 1000, 100)).isTrue()
        assertThat(counter.hasCapacity(start.plusSeconds(30), 1000, 200)).isFalse()
        assertThat(counter.nextFreeAt(start.plusSeconds(30), 1000, 200)).isEqualTo(start.plusSeconds(60))
        assertThat(counter.nextFreeAt(start.plusSeconds(30), 1000, 800)).isEqualTo(start.plusSeconds(80))
        assertThat(counter.used(start.plusSeconds(60))).describedAs("half-open window").isEqualTo(300)
        assertThat(counter.used(start.plusSeconds(80))).isZero()
    }

    @Test
    fun `an event heavier than the limit is admitted only into an empty window`() {
        val counter = WeightedWindowCounter(Duration.ofMinutes(1))
        assertThat(counter.hasCapacity(start, 100, 500)).isTrue()
        counter.record(start, 500)
        assertThat(counter.hasCapacity(start.plusSeconds(1), 100, 500)).isFalse()
        assertThat(counter.nextFreeAt(start.plusSeconds(1), 100, 500)).isEqualTo(start.plusSeconds(60))
    }

    @Test
    fun `requests per minute are enforced and long waits fail with a retryable error`() {
        val limiter = limiter(ProviderLimits(requestsPerMinute = 3, interactiveReserve = 0.0, maxWait = Duration.ofSeconds(2)))
        repeat(3) { limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 10) }

        at(10)
        assertThatThrownBy { limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 10) }
            .isInstanceOf(IntegrationException::class.java)
            .satisfies({ assertThat((it as IntegrationException).retryable).isTrue() })
            .hasMessageContaining("rate limit").hasMessageContaining("50s")
        assertThat(sleeps).isEmpty()
        assertThat(limiter.usage(Provider.ANTHROPIC).first).describedAs("a refused call is not recorded").isEqualTo(3)
    }

    @Test
    fun `a short wait is absorbed by sleeping until the window frees`() {
        val limiter = limiter(ProviderLimits(requestsPerMinute = 2, interactiveReserve = 0.0, maxWait = Duration.ofSeconds(2)))
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 1)
        at(1)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 1)

        at(59)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 1)

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1))
        assertThat(clock.time).isEqualTo(start.plusSeconds(60))
    }

    @Test
    fun `batch calls leave the interactive reserve free`() {
        val limiter = limiter(ProviderLimits(requestsPerMinute = 10, interactiveReserve = 0.2, maxWait = Duration.ZERO))
        repeat(8) { limiter.acquire(Provider.ANTHROPIC, CallPriority.of(UsageKind.BENCHMARK_ATTEMPT), 1) }

        assertThatThrownBy { limiter.acquire(Provider.ANTHROPIC, CallPriority.BATCH, 1) }.isInstanceOf(IntegrationException::class.java)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.of(UsageKind.PIPELINE_STEP), 1)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.of(UsageKind.CODE_REVIEW), 1)
        assertThatThrownBy { limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 1) }.isInstanceOf(IntegrationException::class.java)
        assertThat(CallPriority.of(UsageKind.MODEL_TEST)).isEqualTo(CallPriority.INTERACTIVE)
    }

    @Test
    fun `the token estimate is corrected with the actual usage`() {
        val limiter = limiter(ProviderLimits(tokensPerMinute = 10_000, interactiveReserve = 0.0, maxWait = Duration.ZERO))
        val permit = limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 9_000)
        assertThatThrownBy { limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 2_000) }.isInstanceOf(IntegrationException::class.java)

        permit.complete(1_500)

        assertThat(limiter.usage(Provider.ANTHROPIC).second).isEqualTo(1_500)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 2_000)
        assertThat(limiter.usage(Provider.ANTHROPIC).second).isEqualTo(3_500)
    }

    @Test
    fun `tokens and requests are both checked and the tighter window decides the wait`() {
        val limiter = limiter(ProviderLimits(requestsPerMinute = 100, tokensPerMinute = 1_000, interactiveReserve = 0.0, maxWait = Duration.ofSeconds(30)))
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 900)
        at(40)
        limiter.acquire(Provider.ANTHROPIC, CallPriority.INTERACTIVE, 500)
        assertThat(sleeps).containsExactly(Duration.ofSeconds(20))
    }

    @Test
    fun `providers without limits and the estimate helper`() {
        val limiter = limiter(ProviderLimits(requestsPerMinute = 1, maxWait = Duration.ZERO))
        repeat(100) { limiter.acquire(Provider.OPENAI, CallPriority.BATCH, 1_000_000).complete(5) }
        assertThat(limiter.usage(Provider.OPENAI)).isEqualTo(0L to 0L)
        assertThat(ProviderRateLimiter.estimateTokens(promptChars = 4_001, maxOutputTokens = 1_000)).isEqualTo(2_001)
    }

    @Test
    fun `limits are validated`() {
        assertThatThrownBy { ProviderLimits(requestsPerMinute = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProviderLimits(tokensPerMinute = 10, interactiveReserve = 1.0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
