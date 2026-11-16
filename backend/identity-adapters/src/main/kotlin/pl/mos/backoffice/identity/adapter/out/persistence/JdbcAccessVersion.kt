package pl.mos.backoffice.identity.adapter.out.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Access versions per user (ADR 0068): the global counter `identity_access_version` plus a counter per user in
 * `identity_user_access_version` (V414). A user's version is their sum, so it moves on when either moves.
 *
 * - **Bump**: in the caller's transaction (`... RETURNING version`). After commit this instance applies the new value
 *   at once; other instances get it through a cluster signal sent in the transaction (PostgreSQL delivers the
 *   NOTIFY on commit, never on rollback). Applying only after commit means no request can cache data read before
 *   the commit under the new version.
 * - **Read**: from memory. A user's counter is read from the database once per instance (first request) and then kept
 *   up to date by the signals and by a poll every [refreshMillis] ms of the global counter and of the user rows changed
 *   since the previous poll (minus [overlap], so a row whose transaction started earlier but committed later is not
 *   missed). A lost signal therefore delays a change by at most [refreshMillis] ms, as before.
 */
class JdbcAccessVersion(
    private val jdbc: JdbcClient,
    private val refreshMillis: Long = 2_000,
    private val signals: ClusterSignals? = null,
    private val overlap: Duration = Duration.ofMinutes(1),
) : AccessVersion {

    private val log = LoggerFactory.getLogger(JdbcAccessVersion::class.java)

    /** Tells this instance's own signals apart: it applies its changes after commit, not when it publishes them. */
    private val instance = UUID.randomUUID().toString()
    private val global = AtomicLong(0)
    private val users = ConcurrentHashMap<UserId, Long>()
    private val readAt = AtomicLong(0)
    @Volatile private var pollSince: OffsetDateTime? = null

    init {
        signals?.subscribe(TOPIC, ::onSignal)
    }

    override fun current(userId: UserId): Long {
        refreshIfDue()
        val own = users[userId] ?: load(userId)
        return global.get() + own
    }

    override fun userChanged(userId: UserId) {
        val version = jdbc.sql(
            """
            INSERT INTO identity_user_access_version (user_id, version, changed_at) VALUES (:id, 1, now())
            ON CONFLICT (user_id) DO UPDATE SET version = identity_user_access_version.version + 1, changed_at = now()
            RETURNING version
            """.trimIndent(),
        ).param("id", userId.value).query(Long::class.javaObjectType).single()
        signals?.publish(TOPIC, "$instance|$USER|$userId|$version")
        afterCommit { applyUser(userId, version) }
    }

    override fun everyoneChanged() {
        val version = jdbc.sql("UPDATE identity_access_version SET version = version + 1 WHERE id = 1 RETURNING version")
            .query(Long::class.javaObjectType).single()
        signals?.publish(TOPIC, "$instance|$GLOBAL|$version")
        afterCommit { applyGlobal(version) }
    }

    private fun onSignal(key: String) {
        val parts = key.split('|')
        when {
            parts.firstOrNull() == instance -> Unit
            parts.size == 4 && parts[1] == USER -> runCatching { applyUser(UserId.of(parts[2]), parts[3].toLong()) }.onFailure { readAt.set(0) }
            parts.size == 3 && parts[1] == GLOBAL -> parts[2].toLongOrNull()?.let(::applyGlobal) ?: readAt.set(0)
            // unknown format (e.g. an older replica during a rolling update): poll at the next request
            else -> readAt.set(0)
        }
    }

    private fun applyUser(userId: UserId, version: Long) {
        users.merge(userId, version, ::maxOf)
    }

    private fun applyGlobal(version: Long) {
        global.accumulateAndGet(version, ::maxOf)
    }

    private fun load(userId: UserId): Long {
        val stored = runCatching {
            jdbc.sql("SELECT version FROM identity_user_access_version WHERE user_id = :id").param("id", userId.value)
                .query(Long::class.javaObjectType).optional().orElse(0L)
        }.onFailure { log.warn("could not read the access version of user {}: {}", userId, it.toString()) }.getOrNull()
            ?: return users[userId] ?: 0L
        return users.merge(userId, stored, ::maxOf) ?: stored
    }

    private fun refreshIfDue() {
        val now = System.currentTimeMillis()
        val last = readAt.get()
        if (now - last <= refreshMillis || !readAt.compareAndSet(last, now)) return
        runCatching {
            val dbNow = jdbc.sql("SELECT now()").query(OffsetDateTime::class.java).single()
            jdbc.sql("SELECT version FROM identity_access_version WHERE id = 1").query(Long::class.javaObjectType).single().let(::applyGlobal)
            pollSince?.let { since ->
                jdbc.sql("SELECT user_id, version FROM identity_user_access_version WHERE changed_at > :since").param("since", since)
                    .query { rs, _ -> UserId(rs.getObject("user_id", UUID::class.java)) to rs.getLong("version") }.list()
                    // only users this instance already knows; the others are read at their first request
                    .forEach { (id, version) -> users.computeIfPresent(id) { _, known -> maxOf(known, version) } }
            }
            pollSince = dbNow.minus(overlap)
        }.onFailure { log.warn("could not poll access versions: {}", it.toString()) }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    companion object {
        /** Cluster signal topic (`backoffice_cache`) announcing an access change: `instance|user|<id>|<v>` or `instance|all|<v>`. */
        const val TOPIC = "identity-access-version"
        private const val USER = "user"
        private const val GLOBAL = "all"
    }
}
