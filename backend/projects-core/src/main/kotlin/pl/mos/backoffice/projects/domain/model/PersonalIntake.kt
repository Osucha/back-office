package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@JvmInline
value class IntakeRuleId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = IntakeRuleId(UUID.randomUUID())
        fun of(value: String) = IntakeRuleId(UUID.fromString(value))
    }
}

@JvmInline
value class IntakeProposalId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = IntakeProposalId(UUID.randomUUID())
        fun of(value: String) = IntakeProposalId(UUID.fromString(value))
    }
}

/**
 * A personal intake rule (ADR 0038): one user's JQL, read through one of their own personal connections every
 * [pollIntervalMinutes]; every issue that appears in the results for the first time becomes a proposal to start
 * [workflowId]. [initialized] = the first poll happened ([includeExisting] decides whether the issues found then are
 * proposed). Errors back off exponentially like the project watcher (ADR 0033).
 */
data class PersonalIntakeRule(
    val id: IntakeRuleId,
    val ownerUserId: UUID,
    val ownerName: String,
    val name: String,
    val connectionId: ConnectionId,
    val jql: String,
    val pollIntervalMinutes: Int = DEFAULT_INTERVAL,
    val workflowId: WorkflowDefinitionId,
    val enabled: Boolean = true,
    val includeExisting: Boolean = true,
    val initialized: Boolean = false,
    val nextPollAt: Instant? = null,
    val lastPollAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (name.length > MAX_NAME) throw ValidationException("must be at most $MAX_NAME characters", "name")
        if (jql.isBlank()) throw ValidationException("must not be blank", "jql")
        if (jql.length > MAX_JQL) throw ValidationException("must be at most $MAX_JQL characters", "jql")
        if (pollIntervalMinutes !in MIN_INTERVAL..MAX_INTERVAL) throw ValidationException("must be between $MIN_INTERVAL and $MAX_INTERVAL", "pollIntervalMinutes")
    }

    fun due(now: Instant): Boolean = enabled && (nextPollAt == null || !nextPollAt.isAfter(now))

    /** New settings; enabling (or changing what is read) polls right away. */
    fun configure(
        name: String, connectionId: ConnectionId, jql: String, pollIntervalMinutes: Int, workflowId: WorkflowDefinitionId,
        enabled: Boolean, includeExisting: Boolean, now: Instant,
    ): PersonalIntakeRule {
        val readsOtherIssues = connectionId != this.connectionId || jql.trim() != this.jql
        return copy(
            name = name.trim(), connectionId = connectionId, jql = jql.trim(), pollIntervalMinutes = pollIntervalMinutes, workflowId = workflowId,
            enabled = enabled, includeExisting = includeExisting, updatedAt = now,
            nextPollAt = if (enabled && (!this.enabled || readsOtherIssues)) now else nextPollAt,
            lastError = if (enabled) lastError else null, consecutiveFailures = if (enabled) consecutiveFailures else 0,
        )
    }

    fun pollNow(now: Instant): PersonalIntakeRule {
        if (!enabled) throw InvalidStateException("the rule $name is switched off")
        return copy(nextPollAt = now)
    }

    fun succeeded(now: Instant): PersonalIntakeRule = copy(
        initialized = true, lastPollAt = now, lastSuccessAt = now, lastError = null, consecutiveFailures = 0,
        nextPollAt = now.plus(Duration.ofMinutes(pollIntervalMinutes.toLong())),
    )

    /** Records an error and backs off: the interval doubled per consecutive failure (at most 6 times, at most 1 h), or [retryAfter]. */
    fun failed(now: Instant, error: String, retryAfter: Duration? = null): PersonalIntakeRule {
        val failures = consecutiveFailures + 1
        val backoff = Duration.ofMinutes(pollIntervalMinutes.toLong()).multipliedBy(1L shl minOf(failures - 1, MAX_DOUBLINGS))
        val wait = maxOf(retryAfter ?: Duration.ZERO, minOf(backoff, MAX_BACKOFF).coerceAtLeast(Duration.ofMinutes(pollIntervalMinutes.toLong())))
        return copy(lastPollAt = now, lastError = error.take(MAX_ERROR_CHARS), consecutiveFailures = failures, nextPollAt = now.plus(wait))
    }

    companion object {
        const val DEFAULT_INTERVAL = 5
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 1440
        const val MAX_NAME = 100
        const val MAX_JQL = 2000
        const val MAX_RULES_PER_USER = 10
        const val MAX_DOUBLINGS = 6
        const val MAX_ERROR_CHARS = 1000
        val MAX_BACKOFF: Duration = Duration.ofHours(1)
    }
}

/** What the personal intake reads of an issue, for display only (ADR 0038): never sent to a model by the intake. */
data class IssueHeadline(
    val key: String,
    val summary: String?,
    val type: String?,
    val status: String?,
    val priority: String?,
)

enum class ProposalState { NEW, STARTED, DISMISSED, BASELINE }

/**
 * An issue a personal intake rule found for the first time (ADR 0038). The owner starts a workflow for it (the run is
 * theirs), dismisses it, or restores a dismissed one. `BASELINE`: found on a first poll without `includeExisting`;
 * never shown, only remembered so the issue is not proposed later.
 */
data class IntakeProposal(
    val id: IntakeProposalId,
    val ruleId: IntakeRuleId,
    val ownerUserId: UUID,
    val issueKey: String,
    val summary: String? = null,
    val issueType: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val state: ProposalState = ProposalState.NEW,
    val detectedAt: Instant,
    val decidedAt: Instant? = null,
    val workflowId: WorkflowDefinitionId? = null,
    val runId: WorkflowRunId? = null,
) {
    fun started(workflowId: WorkflowDefinitionId, runId: WorkflowRunId, now: Instant): IntakeProposal {
        if (state != ProposalState.NEW && state != ProposalState.DISMISSED) throw InvalidStateException("proposal for $issueKey is $state")
        return copy(state = ProposalState.STARTED, workflowId = workflowId, runId = runId, decidedAt = now)
    }

    fun dismissed(now: Instant): IntakeProposal {
        if (state != ProposalState.NEW) throw InvalidStateException("proposal for $issueKey is $state, only a new one can be dismissed")
        return copy(state = ProposalState.DISMISSED, decidedAt = now)
    }

    fun restored(): IntakeProposal {
        if (state != ProposalState.DISMISSED) throw InvalidStateException("proposal for $issueKey is $state, only a dismissed one can be restored")
        return copy(state = ProposalState.NEW, decidedAt = null)
    }

    companion object {
        const val MAX_SUMMARY = 500

        fun found(rule: PersonalIntakeRule, issue: IssueHeadline, state: ProposalState, now: Instant) = IntakeProposal(
            id = IntakeProposalId.new(), ruleId = rule.id, ownerUserId = rule.ownerUserId, issueKey = issue.key,
            summary = issue.summary?.take(MAX_SUMMARY), issueType = issue.type, status = issue.status, priority = issue.priority,
            state = state, detectedAt = now,
        )
    }
}
