package pl.mos.backoffice.identity.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.identity.adapter.`in`.web.LoginThrottle
import pl.mos.backoffice.identity.adapter.`in`.web.ThrottleAuditMemory
import pl.mos.backoffice.identity.adapter.out.security.UsedTokenStore
import pl.mos.backoffice.shared.Clock
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/*
 * Authentication state shared by every replica (ADR 0023, V403): with N api replicas the login throttle
 * counts all attempts of an address, an MFA step token is single use cluster-wide and a throttled burst is
 * audited once. Plain rows rather than token buckets: the sliding-window semantics of ADR 0021 stay exact.
 */

/**
 * Sliding window per key in `identity_login_attempt`: at most [maxAttempts] within [window]. Each call runs in
 * one transaction holding a transaction-scoped advisory lock on the key, so concurrent requests on different
 * replicas serialise per address.
 */
class JdbcLoginThrottle(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val maxAttempts: Int,
    private val window: Duration,
    private val clock: Clock,
) : LoginThrottle {

    override fun tryAcquire(key: String): Duration? = tx.execute {
        val now = clock.now()
        val boundary = now.minus(window)
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))").param("k", LOCK_PREFIX + key).query { _: ResultSet, _: Int -> 1 }.list()
        jdbc.sql("DELETE FROM identity_login_attempt WHERE throttle_key = :k AND at <= :boundary").param("k", key).param("boundary", utc(boundary)).update()
        val inWindow = jdbc.sql("SELECT at FROM identity_login_attempt WHERE throttle_key = :k ORDER BY at LIMIT :n")
            .param("k", key).param("n", maxAttempts).query(OffsetDateTime::class.java).list().filterNotNull()
        if (inWindow.size >= maxAttempts) {
            val waitMillis = inWindow.first().toInstant().plus(window).toEpochMilli() - now.toEpochMilli()
            Duration.ofMillis(waitMillis.coerceAtLeast(1))
        } else {
            jdbc.sql("INSERT INTO identity_login_attempt (throttle_key, at) VALUES (:k, :at)").param("k", key).param("at", utc(now)).update()
            if (now.toEpochMilli() % PRUNE_EVERY == 0L) {
                jdbc.sql("DELETE FROM identity_login_attempt WHERE at <= :boundary").param("boundary", utc(boundary)).update()
            }
            null
        }
    }

    override fun reset() {
        jdbc.sql("DELETE FROM identity_login_attempt").update()
        jdbc.sql("DELETE FROM identity_throttle_audit").update()
    }

    private companion object {
        const val LOCK_PREFIX = "login-throttle:"
        const val PRUNE_EVERY = 50L
    }
}

/** [ThrottleAuditMemory] in `identity_throttle_audit` (upsert only when the previous burst is over). */
class JdbcThrottleAuditMemory(private val jdbc: JdbcClient, private val clock: Clock) : ThrottleAuditMemory {
    override fun firstOfBurst(key: String, wait: Duration): Boolean {
        val now = clock.now()
        return jdbc.sql(
            """
            INSERT INTO identity_throttle_audit (throttle_key, until) VALUES (:k, :until)
            ON CONFLICT (throttle_key) DO UPDATE SET until = EXCLUDED.until WHERE identity_throttle_audit.until <= :now
            """.trimIndent(),
        ).param("k", key).param("until", utc(now.plus(wait))).param("now", utc(now)).update() == 1
    }
}

/** [UsedTokenStore] in `mfa_token_use`: a nonce can be inserted once; expired rows are pruned. */
class JdbcUsedTokenStore(private val jdbc: JdbcClient) : UsedTokenStore {
    override fun markUsed(nonce: String, expiresAt: Instant, now: Instant): Boolean {
        if (now.toEpochMilli() % PRUNE_EVERY == 0L) jdbc.sql("DELETE FROM mfa_token_use WHERE expires_at <= :now").param("now", utc(now)).update()
        return jdbc.sql("INSERT INTO mfa_token_use (jti, expires_at) VALUES (:jti, :exp) ON CONFLICT (jti) DO NOTHING")
            .param("jti", nonce.take(MAX_JTI)).param("exp", utc(expiresAt)).update() == 1
    }

    private companion object {
        const val PRUNE_EVERY = 20L
        const val MAX_JTI = 64
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
