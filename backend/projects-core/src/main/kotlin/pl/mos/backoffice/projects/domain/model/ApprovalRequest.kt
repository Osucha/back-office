package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

enum class ApprovalStatus { OPEN, DECIDED, EXPIRED }

/**
 * What an approval asks: a `HUMAN_GATE` decision ([GATE]) or the confirmation of an external write a step prepared
 * ([WRITE_CONFIRMATION], ADR 0033: options approve / reject, the exact write in [ApprovalRequest.write]).
 */
enum class ApprovalKind { GATE, WRITE_CONFIRMATION }

data class EditedArtifact(val artifactId: ArtifactId, val content: String)

/**
 * An approver's answer to an approval request. `option` must be one of the request's options
 * (case-insensitive); [checkedItems] are the ids of the gate checklist items the approver ticked and
 * [decidedBy] is who decided (null only for synthetic timeout decisions).
 */
data class Decision(
    val option: String,
    val comment: String? = null,
    val editedArtifacts: List<EditedArtifact> = emptyList(),
    val decidedAt: Instant,
    val checkedItems: List<String> = emptyList(),
    val decidedBy: Actor? = null,
    /** ADR 0035, write confirmations only: who writes to Jira after the approval (the system connection or the approver's own). */
    val writeAs: WriteIdentity? = null,
) {
    val isApprove: Boolean get() = option.equals(GateSpec.APPROVE, ignoreCase = true)
    val isRevise: Boolean get() = option.equals(GateSpec.REVISE, ignoreCase = true)
    val isReject: Boolean get() = option.equals(GateSpec.REJECT, ignoreCase = true)

    fun describe(): String =
        "Decision: ${option.uppercase()}" + (decidedBy?.let { " by ${it.displayName}" } ?: "") +
            (comment?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")

    fun toStateMap(): Map<String, Any?> = mapOf(
        "option" to option, "comment" to comment, "checkedItems" to checkedItems, "decidedBy" to decidedBy?.displayName,
        "decidedByUserId" to decidedBy?.userId?.toString(), "writeAs" to writeAs?.name,
    )

    companion object {
        const val TIMEOUT = "timeout"
    }
}

data class ApprovalContext(
    val summary: String? = null,
    val confidence: Double? = null,
    val artifactIds: List<ArtifactId> = emptyList(),
    val mergeRequestUrl: String? = null,
)

/**
 * Run-time assignment of an open approval (ADR 0028): [userIds] replace the gate's `approvers.users` for
 * this approval only; the roles rule and the four-eyes rule still apply.
 */
data class ApprovalAssignment(
    val userIds: List<UUID>,
    val assignedBy: Actor?,
    val assignedAt: Instant,
    val comment: String? = null,
) {
    init {
        if (userIds.isEmpty()) throw ValidationException("an assignment needs at least one user", "userIds")
    }
}

/**
 * Aggregate root: one item of the inbox, created when a run pauses at a `HUMAN_GATE` node. It copies the
 * gate's approver rule and checklist and the run's `startedBy` so the rule that applied is the one of the
 * version the run started with. A project owner or maintainer may [assign] the open approval to named users.
 */
data class ApprovalRequest(
    val id: ApprovalRequestId,
    val runId: WorkflowRunId,
    val nodeId: String,
    val projectId: ProjectId,
    val projectKey: String,
    val workflowName: String,
    val issueKey: String?,
    val question: String,
    val options: List<String> = GateSpec.DEFAULT_OPTIONS,
    val context: ApprovalContext = ApprovalContext(),
    val status: ApprovalStatus = ApprovalStatus.OPEN,
    val decision: Decision? = null,
    val createdAt: Instant,
    val approvers: GateApprovers = GateApprovers.ANYONE,
    val checklist: List<ChecklistItem> = emptyList(),
    val startedBy: Actor? = null,
    val assignment: ApprovalAssignment? = null,
    val kind: ApprovalKind = ApprovalKind.GATE,
    /** The exact write awaiting confirmation (only for [ApprovalKind.WRITE_CONFIRMATION]); never re-rendered. */
    val write: PendingWrite? = null,
) {
    init {
        if (options.isEmpty()) throw ValidationException("approval options must not be empty", "options")
        if ((kind == ApprovalKind.WRITE_CONFIRMATION) != (write != null)) {
            throw ValidationException("a write confirmation carries its write, a gate does not", "write")
        }
    }

    val isWriteConfirmation: Boolean get() = kind == ApprovalKind.WRITE_CONFIRMATION

    /** The named users who may decide: the run-time assignment, else the gate's `approvers.users` (empty = no named users). */
    val assignees: List<UUID> get() = assignment?.userIds ?: approvers.users

    /**
     * Sets (non-empty [userIds]) or clears (empty: back to the gate's own rule) the run-time assignment of this
     * open approval. Duplicates are dropped.
     */
    fun assign(userIds: List<UUID>, by: Actor?, at: Instant, comment: String? = null): ApprovalRequest {
        if (status != ApprovalStatus.OPEN) throw InvalidStateException("approval $id is already $status")
        val distinct = userIds.distinct()
        if (distinct.size > MAX_ASSIGNEES) throw ValidationException("at most $MAX_ASSIGNEES assignees", "userIds")
        return copy(assignment = if (distinct.isEmpty()) null else ApprovalAssignment(distinct, by, at, comment?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /**
     * Records [decision]. Ticked items must exist in the checklist; the `approve` option additionally needs
     * every required item ticked (Definition of Ready/Done).
     */
    fun decide(decision: Decision): ApprovalRequest {
        if (status != ApprovalStatus.OPEN) throw InvalidStateException("approval $id is already $status")
        if (options.none { it.equals(decision.option, ignoreCase = true) }) {
            throw ValidationException("option must be one of $options", "option")
        }
        if (isWriteConfirmation && decision.editedArtifacts.isNotEmpty()) {
            // the approved write is exactly the previewed one: nothing can be edited on the way
            throw ValidationException("a write confirmation cannot edit artefacts", "editedArtifacts")
        }
        val known = checklist.map { it.id }.toSet()
        val unknown = decision.checkedItems.filterNot { it in known }
        if (unknown.isNotEmpty()) throw ValidationException("unknown checklist items ${unknown.joinToString()}", "checkedItems")
        if (decision.isApprove) {
            val missing = missingRequiredItems(decision.checkedItems)
            if (missing.isNotEmpty()) throw ValidationException("approve needs the required checklist items ${missing.joinToString()} ticked", "checkedItems")
        }
        val normalised = decision.copy(
            option = options.first { it.equals(decision.option, ignoreCase = true) },
            checkedItems = decision.checkedItems.distinct(),
        )
        return copy(status = ApprovalStatus.DECIDED, decision = normalised)
    }

    fun missingRequiredItems(checked: Collection<String>): List<String> = checklist.filter { it.required && it.id !in checked }.map { it.id }

    fun expire(): ApprovalRequest {
        if (status != ApprovalStatus.OPEN) throw InvalidStateException("approval $id is already $status")
        return copy(status = ApprovalStatus.EXPIRED)
    }

    companion object {
        const val MAX_ASSIGNEES = 20
    }
}
