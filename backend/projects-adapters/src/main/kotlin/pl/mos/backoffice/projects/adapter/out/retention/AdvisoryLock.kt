package pl.mos.backoffice.projects.adapter.out.retention

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.sql.DataSource

/**
 * Cluster-wide "run at most once at a time" for scheduled jobs without ShedLock: a PostgreSQL session
 * advisory lock (`pg_try_advisory_lock`) held on one dedicated pooled connection while [block] runs on
 * other connections. Replicas that do not get the lock skip the run. The lock dies with the session, so
 * a crashed replica never blocks the others. Needs a session-mode connection (not PgBouncer transaction
 * pooling); ShedLock (ADR 0023) can replace this by wrapping the job method with the same lock name.
 */
object AdvisoryLock {

    /** Runs [block] when the lock [name] is free; returns null when another session holds it. */
    fun <T> tryRun(dataSource: DataSource, name: String, block: () -> T): T? {
        dataSource.connection.use { connection ->
            val key = key(name)
            val locked = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, key)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (!locked) return null
            try {
                return block()
            } finally {
                connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, key)
                    ps.executeQuery().close()
                }
            }
        }
    }

    /** Stable 64-bit key: the first 8 bytes of SHA-256 of the lock name. */
    fun key(name: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))).long
}
