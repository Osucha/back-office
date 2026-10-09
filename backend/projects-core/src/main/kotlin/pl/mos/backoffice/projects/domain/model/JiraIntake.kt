package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Duration
import java.time.Instant

/**
 * How the Jira intake watcher decides that an issue starts a run (ADR 0033), derived from the `JIRA_TRANSITION`
 * trigger of the workflow it starts: with a status, an issue that ENTERS that status; without one, an issue that is
 * NEW (created after the watcher's cursor) and matches the JQL.
 */
enum class IntakeMode { TRANSITION, NEW_ISSUE }

/**
 * Aggregate root (ADR 0033): the Jira intake watcher of one project. Configuration ([enabled], [pollIntervalMinutes],
 * [workflowId]; the JQL filter is the project's `issueTracker.jql`) plus the polling state: [cursor] is the start of
 * the last successful poll (the next poll looks back from there, with an overlap), [lastPollAt] / [lastSuccessAt],
 * the last error and the back-off ([consecutiveFailures], [nextPollAt]), and how many runs it started.
 * The watcher only ever reads (an `ISSUE_READ` connection, ADR 0030); it never writes to Jira.
 */
data class JiraIntake(
    val projectId: ProjectId,
    val enabled: Boolean = false,
    val pollIntervalMinutes: Int = DEFAULT_POLL_MINUTES,
    val workflowId: WorkflowDefinitionId? = null,
    val cursor: Instant? = null,
    val lastPollAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val lastErrorAt: Instant? = null,
    val consecutiveFailures: Int = 0,
    val nextPollAt: Instant? = null,
    val issuesStarted: Long = 0,
    /** Connection used by the last poll and the least-privilege warning (over-privileged), if any. */
    val lastConnection: String? = null,
    val lastWarning: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: Actor? = null,
) {
    init {
        if (pollIntervalMinutes !in MIN_POLL_MINUTES..MAX_POLL_MINUTES) {
            throw ValidationException("must be between $MIN_POLL_MINUTES and $MAX_POLL_MINUTES minutes", "pollIntervalMinutes")
        }
        if (enabled && workflowId == null) throw ValidationException("choose the workflow the watcher starts", "workflowId")
    }

    /**
     * New configuration. Enabling (again) or switching the workflow resets the cursor, so the first poll only takes a
     * baseline and never starts runs for the existing backlog.
     */
    fun configure(enabled: Boolean, pollIntervalMinutes: Int, workflowId: WorkflowDefinitionId?, actor: Actor, now: Instant): JiraIntake {
        val restart = enabled && (!this.enabled || workflowId != this.workflowId)
        return copy(
            enabled = enabled,
            pollIntervalMinutes = pollIntervalMinutes,
            workflowId = workflowId,
            cursor = if (restart) null else cursor,
            nextPollAt = if (enabled) now else null,
            consecutiveFailures = if (restart) 0 else consecutiveFailures,
            lastError = if (restart) null else lastError,
            updatedAt = now,
            updatedBy = actor,
        )
    }

    fun due(now: Instant): Boolean = enabled && (nextPollAt == null || !nextPollAt.isAfter(now))

    /** Poll as soon as a worker picks it up ("Poll now"). */
    fun pollNow(now: Instant): JiraIntake = copy(nextPollAt = now)

    /** A successful poll that started [started] runs; [advanceCursor] false keeps the window (more events than one poll starts). */
    fun succeeded(now: Instant, started: Int, advanceCursor: Boolean, connection: String?, warning: String?): JiraIntake = copy(
        cursor = if (advanceCursor || cursor == null) now else cursor,
        lastPollAt = now,
        lastSuccessAt = now,
        lastError = null,
        lastErrorAt = null,
        consecutiveFailures = 0,
        nextPollAt = now.plus(interval()),
        issuesStarted = issuesStarted + started,
        lastConnection = connection,
        lastWarning = warning,
    )

    /**
     * A failed poll: exponential back-off from the poll interval (doubling per consecutive failure, at most
     * [MAX_BACKOFF]); a server-sent `Retry-After` wins when it is longer.
     */
    fun failed(now: Instant, error: String, retryAfter: Duration? = null, connection: String? = lastConnection): JiraIntake {
        val failures = consecutiveFailures + 1
        val backoff = interval().multipliedBy(1L shl (failures - 1).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF)
        val wait = if (retryAfter != null && retryAfter > backoff) retryAfter.coerceAtMost(MAX_BACKOFF) else backoff
        return copy(
            lastPollAt = now,
            lastError = error.take(MAX_ERROR_CHARS),
            lastErrorAt = now,
            consecutiveFailures = failures,
            nextPollAt = now.plus(wait),
            lastConnection = connection,
        )
    }

    fun interval(): Duration = Duration.ofMinutes(pollIntervalMinutes.toLong())

    companion object {
        const val DEFAULT_POLL_MINUTES = 5
        const val MIN_POLL_MINUTES = 1
        const val MAX_POLL_MINUTES = 1440
        const val MAX_DOUBLINGS = 6
        const val MAX_ERROR_CHARS = 1000
        val MAX_BACKOFF: Duration = Duration.ofHours(1)
    }
}

/**
 * What the watcher reads of an issue: key, current status, `updated`, `created` and, when Jira returned the changelog,
 * the latest change INTO each status (history id and time). Issue text is never read by the watcher.
 */
data class WatchedIssue(
    val key: String,
    val status: String?,
    val updated: Instant?,
    val created: Instant? = null,
    val statusChanges: List<StatusChange> = emptyList(),
)

/** One status change of an issue's changelog: history [id], when, and the status it went to. */
data class StatusChange(val id: String, val at: Instant?, val toStatus: String)

/** An issue that should start a run, with the key that makes the start happen at most once. */
data class IntakeEvent(val issueKey: String, val dedupeKey: String, val reason: String)
