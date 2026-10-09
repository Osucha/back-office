package pl.mos.backoffice.runnercontroller.egress

import pl.mos.backoffice.runnercontroller.security.CanonicalJson
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One connection decision of the egress proxy. */
data class EgressDecision(val role: String = "", val host: String = "", val allow: Boolean = false, val reason: String = "", val at: String = "")

data class ProxyCredentials(val username: String, val password: String)

data class EgressSummary(val decisions: Int, val denied: List<String>, val digest: String?)

/**
 * Per-job egress state shared with the pool's egress proxy (doc 10, "Egress"): the job's allowed hosts, the
 * SHA-256 of its proxy password, and the decisions the proxy reported. The proxy pulls [snapshot] and posts
 * decisions back; the controller turns them into `JobCompletion.egressDenied` / `egressLogDigest`.
 */
class EgressRegistry(@Volatile var pool: String = "default", private val random: SecureRandom = SecureRandom()) {

    private class Entry(val hosts: List<String>, val passwordHash: String) {
        val log = mutableListOf<EgressDecision>()
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val finished = ConcurrentHashMap<String, Entry>()

    fun register(jobId: String, hosts: List<String>): ProxyCredentials {
        val bytes = ByteArray(24).also(random::nextBytes)
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        entries[jobId] = Entry(hosts.distinct(), CanonicalJson.sha256Hex(password.toByteArray()))
        return ProxyCredentials(jobId, password)
    }

    /** Stops routing for the job; its log stays readable for [summary] until [forget]. */
    fun unregister(jobId: String) {
        entries.remove(jobId)?.let { finished[jobId] = it }
    }

    fun forget(jobId: String) {
        entries.remove(jobId)
        finished.remove(jobId)
    }

    fun record(decisions: List<EgressDecision>) {
        decisions.forEach { d ->
            val entry = entries[d.role] ?: finished[d.role] ?: return@forEach
            synchronized(entry) { if (entry.log.size < MAX_LOG) entry.log += d }
        }
    }

    fun summary(jobId: String): EgressSummary {
        val entry = entries[jobId] ?: finished[jobId] ?: return EgressSummary(0, emptyList(), null)
        return synchronized(entry) { summarise(entry.log) }
    }

    /** Snapshot served to the proxy: `{version, acl, credentials}`; `version` changes whenever the content does. */
    fun snapshot(): Map<String, Any> {
        val jobs = entries.entries.sortedBy { it.key }
        val acl = EgressAcl.forPool(pool, jobs.map { AclService(it.key, pool, it.value.hosts) })
        val credentials = jobs.associate { it.key to it.value.passwordHash }
        val version = CanonicalJson.sha256Hex((acl.toString() + credentials.toString()).toByteArray()).take(16)
        return mapOf("version" to "\"$version\"", "acl" to acl, "credentials" to credentials)
    }

    fun activeJobs(): Set<String> = entries.keys.toSet()

    companion object {
        const val MAX_LOG = 20_000

        /**
         * Canonical egress log = one line per decision, `at|host|ALLOW or DENY|reason`, in arrival order; the
         * digest is lowercase hex SHA-256 over the UTF-8 lines joined with `\n`, prefixed `sha256:`.
         */
        fun summarise(log: List<EgressDecision>): EgressSummary {
            if (log.isEmpty()) return EgressSummary(0, emptyList(), null)
            val lines = log.joinToString("\n") { "${it.at}|${it.host}|${if (it.allow) "ALLOW" else "DENY"}|${it.reason}" }
            val denied = log.filter { !it.allow }.map { it.host.substringBeforeLast(':').ifEmpty { it.host } }.distinct()
            return EgressSummary(log.size, denied, "sha256:" + CanonicalJson.sha256Hex(lines.toByteArray(Charsets.UTF_8)))
        }
    }
}
