package pl.mos.backoffice.identity.adapter.out.security

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Single-use memory of MFA step tokens: in memory (one process) or `JdbcUsedTokenStore` (every replica). */
interface UsedTokenStore {
    /** True the first time [nonce] is seen; remembered until [expiresAt]. */
    fun markUsed(nonce: String, expiresAt: Instant, now: Instant): Boolean
}

class InMemoryUsedTokenStore : UsedTokenStore {
    private val used = ConcurrentHashMap<String, Instant>()

    override fun markUsed(nonce: String, expiresAt: Instant, now: Instant): Boolean {
        if (used.size > PURGE_THRESHOLD) used.entries.removeIf { !it.value.isAfter(now) }
        return used.putIfAbsent(nonce, expiresAt) == null
    }

    private companion object {
        const val PURGE_THRESHOLD = 1_000
    }
}
