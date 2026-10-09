package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.IssueSnapshot
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import java.time.Duration

/** Outbound port to the issue tracker (Jira Cloud REST v3 in `projects-adapters`). */
interface IssueTracker {
    fun isConfigured(): Boolean
    fun fetch(issueKey: String): IssueSnapshot
    fun search(jql: String, limit: Int = 50): List<IssueSnapshot>
    fun comment(issueKey: String, body: String)

    /** Throws [TransitionUnavailableException] when the issue's workflow offers no transition to [toStatus]. */
    fun transition(issueKey: String, toStatus: String)

    /**
     * Intake watcher query (ADR 0033): only key, status, `updated`, `created` and the status changes of the issues
     * matching [jql], at most [limit]. Throws [IssueTrackerUnavailableException] on rate limits and server errors.
     */
    fun watch(jql: String, limit: Int = 100): List<WatchedIssue> = throw UnsupportedOperationException("this issue tracker cannot be watched")
}

/** The issue tracker refused for now (HTTP 429 or 5xx); [retryAfter] is the server's `Retry-After`, when sent. */
class IssueTrackerUnavailableException(val status: Int, val retryAfter: Duration?, message: String) : RuntimeException(message)

/** The issue cannot move to the requested status from where it is (not a connection problem). */
class TransitionUnavailableException(val issueKey: String, val toStatus: String) :
    RuntimeException("issue $issueKey has no transition to status $toStatus")

/** Serialises and parses JSON; core code has no Jackson so adapters provide this. */
interface JsonCodec {
    fun toJson(value: Any?): String
    fun parseObject(json: String): Map<String, Any?>
}

/** Runs a task outside the caller's thread (virtual-thread executor in adapters). */
fun interface BackgroundExecutor {
    fun submit(name: String, task: () -> Unit)
}

/** Hashes CI tokens and generates new ones. SHA-256 hex plus a secure random generator. */
interface TokenHasher {
    fun hash(token: String): String
    fun generate(prefix: String, randomLength: Int): String
}

data class IntegrationDescriptor(
    val name: String,
    val configured: Boolean,
    val url: String? = null,
    val extra: Map<String, String?> = emptyMap(),
)

data class IntegrationTestOutcome(val ok: Boolean, val message: String, val latencyMs: Long? = null)

/** One external system the settings screen can inspect and test (jira, kubernetes, langfuse). */
interface IntegrationProbe {
    val name: String
    fun describe(): IntegrationDescriptor
    fun test(): IntegrationTestOutcome
}
