package pl.mos.backoffice.platform.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.InetAddress
import java.time.Duration
import java.util.UUID

/** The roles one process plays (ADR 0023): `backoffice.role`, a comma list of `api`, `worker`, `migrate`. */
data class PlatformRoles(val api: Boolean, val worker: Boolean, val migrate: Boolean) {

    val names: List<String> get() = listOfNotNull("api".takeIf { api }, "worker".takeIf { worker }, "migrate".takeIf { migrate })

    /** `migrate` alone: run Flyway and exit. */
    val migrateOnly: Boolean get() = migrate && !api && !worker

    override fun toString(): String = names.joinToString(",")

    companion object {
        const val PROPERTY = "backoffice.role"
        const val ENV = "BACKOFFICE_ROLE"
        const val DEFAULT = "api,worker"
        private val KNOWN = setOf("api", "worker", "migrate")

        fun parse(value: String?): PlatformRoles {
            val parts = (value?.takeIf { it.isNotBlank() } ?: DEFAULT).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val unknown = parts - KNOWN
            require(unknown.isEmpty()) { "$PROPERTY contains unknown roles $unknown; allowed: $KNOWN" }
            require(parts.isNotEmpty()) { "$PROPERTY must name at least one role" }
            return PlatformRoles(api = "api" in parts, worker = "worker" in parts, migrate = "migrate" in parts)
        }
    }
}

/** `backoffice.platform.*` (docs/architecture/09-scale-and-operations.md). */
@ConfigurationProperties(prefix = "backoffice.platform")
data class PlatformProperties(
    /** Name of this replica in leases, ShedLock and the instance registry; default `<hostname>-<random>`. */
    val instanceId: String = "",
    /** Derived from `backoffice.role` by [PlatformRoleEnvironmentPostProcessor]; may be overridden explicitly. */
    val apiEnabled: Boolean = true,
    val workerEnabled: Boolean = true,
    val worker: Worker = Worker(),
    val events: Events = Events(),
    val retention: Retention = Retention(),
    val instanceHeartbeat: Duration = Duration.ofSeconds(15),
    /** Replicas whose heartbeat is older than this are shown as stale and not counted. */
    val instanceStaleAfter: Duration = Duration.ofMinutes(2),
    /** Refresh period of the `backoffice_work_items` gauges. */
    val metricsRefresh: Duration = Duration.ofSeconds(15),
) {
    data class Worker(
        /** Work items handled concurrently by one worker (virtual threads). */
        val concurrency: Int = 16,
        /** Lease length; heartbeats extend it every lease/3. */
        val lease: Duration = Duration.ofSeconds(60),
        /** Fallback poll period (inserts also wake workers through `NOTIFY backoffice_work`). */
        val pollInterval: Duration = Duration.ofSeconds(2),
        /** Upper bound of items claimed per poll. */
        val claimBatch: Int = 8,
        /** How long a stopping worker waits for in-flight items before it releases their leases. */
        val drainTimeout: Duration = Duration.ofSeconds(30),
        /** Backoff of handler exceptions: initial * 2^(attempt-1), capped. */
        val initialBackoff: Duration = Duration.ofSeconds(5),
        val maxBackoff: Duration = Duration.ofMinutes(5),
    )

    data class Events(
        /**
         * JDBC URL of the dedicated LISTEN connection (`BACKOFFICE_DATASOURCE_DIRECT_URL`): the primary, never a
         * transaction pooler. Same username/password as the data source. Blank = the data source URL.
         */
        val directUrl: String = "",
        /** Delay between reconnect attempts of the LISTEN connection. */
        val reconnectDelay: Duration = Duration.ofSeconds(5),
        /** Wait of one `getNotifications` call (also the shutdown latency of the bridge). */
        val pollTimeout: Duration = Duration.ofMillis(500),
    )

    data class Retention(
        val eventLog: Duration = Duration.ofHours(24),
        val doneWorkItems: Duration = Duration.ofDays(7),
        val instances: Duration = Duration.ofDays(1),
        val sweepInterval: Duration = Duration.ofHours(1),
    )

    fun resolvedInstanceId(): String = instanceId.ifBlank { DEFAULT_INSTANCE_ID }

    companion object {
        val DEFAULT_INSTANCE_ID: String = run {
            val host = runCatching { System.getenv("HOSTNAME") ?: InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "backoffice"
            "$host-${UUID.randomUUID().toString().take(8)}"
        }
    }
}
