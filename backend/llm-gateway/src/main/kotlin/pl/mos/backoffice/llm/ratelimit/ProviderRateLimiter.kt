package pl.mos.backoffice.llm.ratelimit

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.usage.UsageKind
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/** Who is asking (ADR 0020): interactive work may use the whole budget, batch work leaves a reserve. */
enum class CallPriority {
    /** Workflow steps, code reviews, model tests: a person or a pipeline is waiting. */
    INTERACTIVE,

    /** Benchmark attempts: can come back later. */
    BATCH;

    companion object {
        fun of(kind: UsageKind): CallPriority = if (kind == UsageKind.BENCHMARK_ATTEMPT) BATCH else INTERACTIVE
    }
}

/** Per-provider budget; a null limit means that dimension is not limited. */
data class ProviderLimits(
    val requestsPerMinute: Int? = null,
    val tokensPerMinute: Long? = null,
    /** Share of each budget that only [CallPriority.INTERACTIVE] calls may use, 0..<1. */
    val interactiveReserve: Double = 0.2,
    /** Longest wait absorbed inside the call; longer waits fail with a retryable error. */
    val maxWait: Duration = Duration.ofSeconds(2),
) {
    init {
        require(requestsPerMinute == null || requestsPerMinute > 0) { "requests-per-minute must be positive" }
        require(tokensPerMinute == null || tokensPerMinute > 0) { "tokens-per-minute must be positive" }
        require(interactiveReserve >= 0.0 && interactiveReserve < 1.0) { "interactive-reserve must be within [0, 1)" }
        require(!maxWait.isNegative) { "max-wait must not be negative" }
    }

    fun limited(): Boolean = requestsPerMinute != null || tokensPerMinute != null
}

/**
 * Weighted sliding-window limiter per provider (ADR 0020): requests and tokens per minute. The token
 * weight of a call is estimated before it (input characters / 4 + maximum output tokens) and corrected
 * with the actual usage afterwards ([Permit.complete]). Batch calls see the budget minus the interactive
 * reserve. Short waits (up to `maxWait`) are absorbed with [sleeper]; longer ones throw
 * turns into a delayed retry.
 *
 * Two stores (ADR 0023): without [buckets] the weighted sliding windows live in this process; with [buckets]
 * (bucket4j, PostgreSQL in production: [RateBuckets.postgres]) every replica draws from the same per-provider
 * request and token buckets (capacity = the limit, refilled in full every minute), so the configured quota is
 * the cluster's quota. The interactive reserve is kept exactly: a batch call must find its weight *plus the
 * reserve* available and consumes only its weight (the reserve is refunded at once).
 */
class ProviderRateLimiter(
    private val limits: Map<Provider, ProviderLimits>,
    private val clock: Clock,
    private val buckets: RateBuckets?,
    private val sleeper: (Duration) -> Unit,
) {
    constructor(limits: Map<Provider, ProviderLimits>, clock: Clock, sleeper: (Duration) -> Unit) : this(limits, clock, null, sleeper)

    private class Windows {
        val requests = WeightedWindowCounter(WINDOW)
        val tokens = WeightedWindowCounter(WINDOW)
    }

    private val windows: Map<Provider, Windows> = limits.filterValues { it.limited() }.mapValues { Windows() }

    /** Handle of one admitted call; [complete] replaces the token estimate with the actual usage. */
    class Permit internal constructor(private val onComplete: (Long) -> Unit) {
        fun complete(actualTokens: Long) = onComplete(actualTokens)
    }

    private val unlimited = Permit { }

    /** Blocks up to `maxWait` until the call fits, records it and returns its permit. */
    fun acquire(provider: Provider, priority: CallPriority, estimatedTokens: Long): Permit {
        val w = windows[provider] ?: return unlimited
        val l = limits.getValue(provider)
        val tokens = estimatedTokens.coerceAtLeast(1)
        val requestLimit = l.requestsPerMinute?.let { effective(it.toLong(), l, priority) }
        val tokenLimit = l.tokensPerMinute?.let { effective(it, l, priority) }
        val deadline = clock.now().plus(l.maxWait)
        while (true) {
            val now = clock.now()
            val freeAt: Instant = buckets?.let { b ->
                tryBuckets(b, provider, l, requestLimit, tokenLimit, tokens)?.let { return it }
                ?: now
            } ?: synchronized(w) {
                val requestsOk = requestLimit == null || w.requests.hasCapacity(now, requestLimit, 1)
                val tokensOk = tokenLimit == null || w.tokens.hasCapacity(now, tokenLimit, tokens)
                if (requestsOk && tokensOk) {
                    w.requests.record(now, 1)
                    val entry = w.tokens.record(now, tokens)
                    return Permit { actual -> synchronized(w) { w.tokens.correct(entry, actual) } }
                }
                maxOf(
                    requestLimit?.let { w.requests.nextFreeAt(now, it, 1) } ?: now,
                    tokenLimit?.let { w.tokens.nextFreeAt(now, it, tokens) } ?: now,
                )
            }
            val effectiveFreeAt = if (buckets != null) now.plusNanos(lastWaitNanos.get()) else freeAt
            if (effectiveFreeAt.isAfter(deadline)) {
                val retryIn = Duration.between(now, effectiveFreeAt)
                throw IntegrationException(
                    provider.name.lowercase(),
                    "rate limit reached for ${priority.name.lowercase()} calls " +
                        "(requests/min ${requestLimit ?: "-"}, tokens/min ${tokenLimit ?: "-"}); capacity frees in ${retryIn.toSeconds().coerceAtLeast(1)}s",
                    retryable = true,
                )
            }
            val wait = Duration.between(now, effectiveFreeAt)
            sleeper(if (wait.isZero || wait.isNegative) Duration.ofMillis(1) else wait)
        }
    }

    private val lastWaitNanos = ThreadLocal.withInitial { 0L }

    /**
     * One attempt on the shared buckets: requests first, then tokens (refunding the request when the tokens do
     * not fit). Returns the permit, or null with [lastWaitNanos] set to the time until capacity frees.
     */
    private fun tryBuckets(b: RateBuckets, provider: Provider, l: ProviderLimits, requestLimit: Long?, tokenLimit: Long?, tokens: Long): Permit? {
        val requests = l.requestsPerMinute?.let { b.bucket("${provider.name}:requests", it.toLong()) }
        val tokenBucket = l.tokensPerMinute?.let { b.bucket("${provider.name}:tokens", it) }
        val requestReserve = l.requestsPerMinute?.let { it - requestLimit!! } ?: 0L
        val tokenReserve = l.tokensPerMinute?.let { it - tokenLimit!! } ?: 0L
        // a call heavier than the whole budget is admitted only into a full bucket (it could never run otherwise)
        val weight = tokenLimit?.let { tokens.coerceAtMost(it) } ?: tokens
        val requestWait = requests?.tryConsume(1 + requestReserve) ?: 0L
        if (requestWait > 0) {
            lastWaitNanos.set(requestWait)
            return null
        }
        requests?.refund(requestReserve)
        val tokenWait = tokenBucket?.tryConsume(weight + tokenReserve) ?: 0L
        if (tokenWait > 0) {
            requests?.refund(1)
            lastWaitNanos.set(tokenWait)
            return null
        }
        tokenBucket?.refund(tokenReserve)
        return Permit { actual ->
            val delta = weight - actual.coerceAtLeast(0)
            if (delta > 0) tokenBucket?.refund(delta) else tokenBucket?.force(-delta)
        }
    }

    /** Current usage of the windows, for diagnostics and tests: requests and tokens in the last minute. */
    fun usage(provider: Provider): Pair<Long, Long> {
        val w = windows[provider] ?: return 0L to 0L
        val l = limits.getValue(provider)
        buckets?.let { b ->
            val requests = l.requestsPerMinute?.let { it - b.bucket("${provider.name}:requests", it.toLong()).available() } ?: 0L
            val tokens = l.tokensPerMinute?.let { it - b.bucket("${provider.name}:tokens", it).available() } ?: 0L
            return requests to tokens
        }
        val now = clock.now()
        return synchronized(w) { w.requests.used(now) to w.tokens.used(now) }
    }


    private fun effective(limit: Long, l: ProviderLimits, priority: CallPriority): Long = when (priority) {
        CallPriority.INTERACTIVE -> limit
        CallPriority.BATCH -> floor(limit * (1.0 - l.interactiveReserve)).toLong().coerceAtLeast(1)
    }

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)

        val UNLIMITED = ProviderRateLimiter(emptyMap(), Clock.SYSTEM) { }

        /** Token weight before the call: prompt characters / 4 (rounded up) plus the output budget. */
        fun estimateTokens(promptChars: Long, maxOutputTokens: Int): Long = (promptChars + 3) / 4 + maxOutputTokens.coerceAtLeast(0)
    }
}
