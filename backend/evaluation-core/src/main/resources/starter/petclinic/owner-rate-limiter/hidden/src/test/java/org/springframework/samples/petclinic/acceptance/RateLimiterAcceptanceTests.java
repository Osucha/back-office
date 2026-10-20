package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.system.ratelimit.Decision;
import org.springframework.samples.petclinic.system.ratelimit.RateLimiter;

/**
 * Hidden acceptance tests for benchmark task 5: the token bucket behind the specified API
 * {@code RateLimiter(int capacity, Duration refillPeriod, Clock clock)}. Time only moves
 * through {@link MutableClock}; durations are compared with a tolerance of one
 * millisecond so both exact and floating point implementations pass.
 */
class RateLimiterAcceptanceTests {

	private final MutableClock clock = new MutableClock();

	@Test
	void freshBucketAllowsABurstOfCapacityThenDenies() {
		RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(3), this.clock);

		assertAllowed(limiter.tryAcquire("ip"), 2);
		assertAllowed(limiter.tryAcquire("ip"), 1);
		assertAllowed(limiter.tryAcquire("ip"), 0);
		assertDenied(limiter.tryAcquire("ip"), Duration.ofSeconds(3));
	}

	@Test
	void refillIsContinuousAndFractional() {
		RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(3), this.clock);
		drain(limiter, "ip", 3);

		this.clock.advance(Duration.ofSeconds(1));
		assertDenied(limiter.tryAcquire("ip"), Duration.ofSeconds(2));
		this.clock.advance(Duration.ofMillis(1500));
		assertDenied(limiter.tryAcquire("ip"), Duration.ofMillis(500));
		this.clock.advance(Duration.ofMillis(500));
		assertAllowed(limiter.tryAcquire("ip"), 0);
		assertDenied(limiter.tryAcquire("ip"), Duration.ofSeconds(3));
	}

	@Test
	void remainingIsTheNumberOfWholeTokensLeft() {
		RateLimiter limiter = new RateLimiter(5, Duration.ofSeconds(1), this.clock);
		drain(limiter, "ip", 5);
		this.clock.advance(Duration.ofMillis(2500));
		assertAllowed(limiter.tryAcquire("ip"), 1);
		assertAllowed(limiter.tryAcquire("ip"), 0);
		assertDenied(limiter.tryAcquire("ip"), Duration.ofMillis(500));
	}

	@Test
	void refillIsCappedAtCapacity() {
		RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(3), this.clock);
		drain(limiter, "ip", 3);
		this.clock.advance(Duration.ofDays(2));

		assertAllowed(limiter.tryAcquire("ip"), 2);
		assertAllowed(limiter.tryAcquire("ip"), 1);
		assertAllowed(limiter.tryAcquire("ip"), 0);
		assertThat(limiter.tryAcquire("ip").allowed()).isFalse();
	}

	@Test
	void deniedRequestsDoNotConsumeTokens() {
		RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(3), this.clock);
		drain(limiter, "ip", 2);
		for (int i = 0; i < 100; i++) {
			Decision denied = limiter.tryAcquire("ip");
			assertThat(denied.allowed()).isFalse();
			assertThat(denied.remaining()).isZero();
		}
		this.clock.advance(Duration.ofSeconds(3));
		assertAllowed(limiter.tryAcquire("ip"), 0);
		assertThat(limiter.tryAcquire("ip").allowed()).isFalse();
	}

	@Test
	void keysHaveIndependentBuckets() {
		RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), this.clock);
		assertAllowed(limiter.tryAcquire("10.0.0.1"), 0);
		assertThat(limiter.tryAcquire("10.0.0.1").allowed()).isFalse();
		assertAllowed(limiter.tryAcquire("10.0.0.2"), 0);
	}

	@Test
	void idleBucketsAreEvictedOnceTheyAreFullAgain() {
		RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(1), this.clock);
		limiter.tryAcquire("a");
		limiter.tryAcquire("b");
		limiter.tryAcquire("b");
		assertThat(limiter.bucketCount()).isEqualTo(2);

		this.clock.advance(Duration.ofSeconds(1));
		limiter.evictIdle();
		assertThat(limiter.bucketCount()).as("a is full again, b is not").isEqualTo(1);

		this.clock.advance(Duration.ofSeconds(1));
		limiter.evictIdle();
		assertThat(limiter.bucketCount()).isZero();

		// an evicted key starts again with a full bucket
		assertAllowed(limiter.tryAcquire("b"), 1);
	}

	@Test
	void invalidConfigurationIsRejected() {
		assertThatThrownBy(() -> new RateLimiter(0, Duration.ofSeconds(1), this.clock))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimiter(5, Duration.ZERO, this.clock))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimiter(5, Duration.ofSeconds(-1), this.clock))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentCallersNeverOverAdmitAndNeverSeeNegativeRemaining() throws Exception {
		int capacity = 1000;
		int threads = 16;
		int attemptsPerThread = 200;
		RateLimiter limiter = new RateLimiter(capacity, Duration.ofHours(1), this.clock);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean negative = new AtomicBoolean();
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					int allowed = 0;
					for (int i = 0; i < attemptsPerThread; i++) {
						Decision decision = limiter.tryAcquire("shared");
						if (decision.remaining() < 0) {
							negative.set(true);
						}
						if (decision.allowed()) {
							allowed++;
						}
					}
					return allowed;
				}));
			}
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int total = 0;
			for (Future<Integer> result : results) {
				total += result.get(60, TimeUnit.SECONDS);
			}
			assertThat(total).as("allowed requests out of " + threads * attemptsPerThread).isEqualTo(capacity);
			assertThat(negative.get()).as("negative remaining seen").isFalse();
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void drain(RateLimiter limiter, String key, int tokens) {
		for (int i = 0; i < tokens; i++) {
			assertThat(limiter.tryAcquire(key).allowed()).isTrue();
		}
	}

	private static void assertAllowed(Decision decision, int remaining) {
		assertThat(decision.allowed()).as("allowed").isTrue();
		assertThat(decision.remaining()).as("remaining").isEqualTo(remaining);
		assertThat(decision.retryAfter()).as("retryAfter when allowed").isEqualTo(Duration.ZERO);
	}

	private static void assertDenied(Decision decision, Duration retryAfter) {
		assertThat(decision.allowed()).as("allowed").isFalse();
		assertThat(decision.remaining()).as("remaining").isZero();
		assertThat(decision.retryAfter()).as("retryAfter")
			.isBetween(retryAfter.minusMillis(1), retryAfter.plusMillis(1));
	}

	static final class MutableClock extends Clock {

		private volatile Instant now = Instant.parse("2026-01-01T08:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
