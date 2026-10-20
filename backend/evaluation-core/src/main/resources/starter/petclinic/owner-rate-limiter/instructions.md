Protect the owner forms from spam with a token-bucket rate limiter per client IP. The acceptance tests compile against this exact API in package `org.springframework.samples.petclinic.system.ratelimit`:

- `public record Decision(boolean allowed, int remaining, java.time.Duration retryAfter)`
- `public final class RateLimiter` with
  - `public RateLimiter(int capacity, java.time.Duration refillPeriod, java.time.Clock clock)`: throws `IllegalArgumentException` when `capacity < 1` or `refillPeriod` is zero or negative;
  - `public Decision tryAcquire(String key)`;
  - `public void evictIdle()`;
  - `public int bucketCount()`.

Semantics: every key starts with a full bucket of `capacity` tokens. Tokens refill continuously (fractionally) at one token per `refillPeriod`, never above `capacity`; time comes only from the injected clock. An allowed call takes one token and returns `allowed = true`, `remaining` = whole tokens left (rounded down), `retryAfter = Duration.ZERO`. A denied call takes nothing and returns `allowed = false`, `remaining = 0`, `retryAfter` = time until one token is available. Thread-safe: concurrent calls for one key never admit more requests than there are tokens. `evictIdle()` removes every bucket that is full again at the clock's current instant (equivalent to having no bucket); `bucketCount()` returns the number of buckets in memory. Eviction must also happen automatically (e.g. from `tryAcquire` from time to time) so memory stays bounded.

Configuration properties: `petclinic.rate-limit.capacity` (int, default `20`) and `petclinic.rate-limit.refill-period` (`java.time.Duration`, default `3s`).

Web: a servlet filter limits `POST` requests to `/owners` and `/owners/**` (not other paths such as `/ownersearch`). The key is `request.getRemoteAddr()`; all such POSTs of one IP share one bucket. A denied request gets status `429` and the header `Retry-After` in whole seconds (rounded up, at least 1). GET requests, other methods and other paths are never limited.

Existing tests stay unchanged and green (note that `@WebMvcTest` slices include `Filter` beans). Add your own tests: burst and fractional refill with a fake clock, a concurrency test proving no over-admission, eviction, and a MockMvc test of the 429 path; no sleeps.

Run ./mvnw -B -q spring-javaformat:apply before you finish.
