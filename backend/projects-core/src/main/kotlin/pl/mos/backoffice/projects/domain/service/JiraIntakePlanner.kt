package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.IntakeEvent
import pl.mos.backoffice.projects.domain.model.IntakeMode
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import java.time.Duration
import java.time.Instant

/**
 * Pure rules of the Jira intake watcher (ADR 0033): the JQL it sends and which issues start a run.
 *
 * JQL: `project = "<KEY>" AND (<project JQL>) AND <window> ORDER BY updated ASC`, where the window is
 * `status CHANGED TO "<status>" AFTER -<n>m` (TRANSITION) or `created >= -<n>m` (NEW_ISSUE). Relative dates avoid the
 * Jira user's time zone; `n` covers the time since the cursor plus an overlap, so nothing is missed between polls,
 * and the dedupe keys make the overlap harmless. The connection's allow-list is added on top by the scoped client
 * (`project in (...) AND (...)`, ADR 0030).
 *
 * Dedupe: TRANSITION -> `T:<issue>:<history id>` of the latest change into the status (from the changelog), or
 * `T:<issue>:<status>:<updated>` without a changelog; NEW_ISSUE -> `N:<issue>`. An issue therefore starts at most one
 * run per transition (and one per creation).
 */
class JiraIntakePlanner {

    fun mode(triggerStatus: String?): IntakeMode = if (triggerStatus.isNullOrBlank()) IntakeMode.NEW_ISSUE else IntakeMode.TRANSITION

    /** Minutes the next poll looks back: since [cursor] plus [OVERLAP], at least [MIN_WINDOW_MINUTES], at most [MAX_WINDOW]. */
    fun windowMinutes(cursor: Instant, now: Instant): Long {
        val since = Duration.between(cursor, now).plus(OVERLAP)
        val minutes = (since.toSeconds() + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return minutes.coerceIn(MIN_WINDOW_MINUTES, MAX_WINDOW.toMinutes())
    }

    fun jql(jiraProjectKey: String, projectJql: String?, triggerStatus: String?, windowMinutes: Long): String {
        val filter = projectJql?.let(::stripOrderBy)?.trim()?.takeIf { it.isNotEmpty() }
        val window = when (mode(triggerStatus)) {
            IntakeMode.TRANSITION -> "status CHANGED TO ${quote(triggerStatus!!.trim())} AFTER -${windowMinutes}m"
            IntakeMode.NEW_ISSUE -> "created >= -${windowMinutes}m"
        }
        return listOfNotNull("project = ${quote(jiraProjectKey)}", filter?.let { "($it)" }, window).joinToString(" AND ") + " ORDER BY updated ASC"
    }

    /** The issues of [issues] that start a run, oldest first, each with its dedupe key. */
    fun events(issues: List<WatchedIssue>, triggerStatus: String?): List<IntakeEvent> = when (mode(triggerStatus)) {
        IntakeMode.NEW_ISSUE -> issues.map { IntakeEvent(it.key, "N:${it.key}", "new issue") }
        IntakeMode.TRANSITION -> {
            val status = triggerStatus!!.trim()
            issues.filter { it.status.equals(status, ignoreCase = true) }.map { issue ->
                val change = issue.statusChanges.filter { it.toStatus.equals(status, ignoreCase = true) }.maxWithOrNull(compareBy({ it.at }, { it.id.toLongOrNull() ?: 0L }))
                val key = if (change != null) "T:${issue.key}:${change.id}" else "T:${issue.key}:${status.lowercase()}:${issue.updated?.toEpochMilli() ?: 0}"
                IntakeEvent(issue.key, key, "entered $status")
            }
        }
    }.distinctBy { it.dedupeKey }

    companion object {
        val OVERLAP: Duration = Duration.ofMinutes(2)
        val MAX_WINDOW: Duration = Duration.ofDays(7)
        const val MIN_WINDOW_MINUTES = 2L
        private const val SECONDS_PER_MINUTE = 60L
        private val ORDER_BY = Regex("(?i)\\border\\s+by\\b.*$", RegexOption.DOT_MATCHES_ALL)

        /** JQL string literal: double quotes, backslash and quote escaped. */
        fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun stripOrderBy(jql: String): String = ORDER_BY.replace(jql, "")
    }
}
