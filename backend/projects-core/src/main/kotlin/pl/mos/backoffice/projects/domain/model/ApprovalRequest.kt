package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

enum class ApprovalStatus { OPEN, DECIDED, EXPIRED }

/**
 * What an approval asks: a `HUMAN_GATE` decision ([GATE]), the confirmation of an external write a step prepared
 * ([WRITE_CONFIRMATION], ADR 0033: options approve / reject, the exact write in [ApprovalRequest.write]) or the answers
 * to an agent's questions ([AGENT_QUESTIONS], ADR 0060: options answer / cancel, the questions in
 * [ApprovalRequest.questions]).
 */
enum class ApprovalKind { GATE, WRITE_CONFIRMATION, AGENT_QUESTIONS }

/**
 * ADR 0060: one question an agent asked the owner instead of guessing: the [question] and [why] it matters. Agent
 * output: the caller sanitises and bounds it ([AgentQuestions.of]).
 */
data class AgentQuestion(val question: String, val why: String? = null) {
    fun toStateMap(): Map<String, Any?> = mapOf("question" to question, "why" to why)

    companion object {
        fun fromStateMap(map: Map<*, *>): AgentQuestion? =
            map["question"]?.toString()?.takeIf { it.isNotBlank() }?.let { AgentQuestion(it, map["why"]?.toString()?.takeIf { w -> w.isNotBlank() }) }
    }
}

/** ADR 0060: the options of an AGENT_QUESTIONS approval and how the answers reach the step again. */
object AgentQuestions {
    const val ANSWER = "answer"
    const val CANCEL = "cancel"
    val OPTIONS = listOf(ANSWER, CANCEL)

    /** Most questions kept of one round; longer lists are cut. */
    const val MAX_QUESTIONS = 10

    /** Most characters of one question or reason. */
    const val MAX_CHARS = 2_000

    /** The questions of one round as the inbox shows them. */
    fun question(round: Int, count: Int): String = "The agent asks ${if (count == 1) "a question" else "$count questions"} (round $round)"

    /** `{{answers}}` of a step: every answered round, questions and the owner's answers, oldest first. */
    fun answersText(previous: String?, round: Int, questions: List<AgentQuestion>, answers: String): String = buildString {
        previous?.takeIf { it.isNotBlank() }?.let { append(it.trimEnd()).append("\n\n") }
        append("Round ").append(round).append(": your questions\n")
        questions.forEachIndexed { i, q ->
            append(i + 1).append(". ").append(q.question.trim())
            q.why?.takeIf { it.isNotBlank() }?.let { append(" (why: ").append(it.trim()).append(')') }
            append('\n')
        }
        append("The owner's answers:\n").append(answers.trim())
    }
}

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
    /** ADR 0060: what the agent asks, in order (only for [ApprovalKind.AGENT_QUESTIONS]). */
    val questions: List<AgentQuestion> = emptyList(),
    /** ADR 0060: 1-based round of questions of the step (only for [ApprovalKind.AGENT_QUESTIONS]). */
    val questionRound: Int? = null,
    /** ADR 0060: the step's limit of rounds (only for [ApprovalKind.AGENT_QUESTIONS]). */
    val maxQuestionRounds: Int? = null,
) {
    init {
        if (options.isEmpty()) throw ValidationException("approval options must not be empty", "options")
        if ((kind == ApprovalKind.WRITE_CONFIRMATION) != (write != null)) {
            throw ValidationException("a write confirmation carries its write, a gate does not", "write")
        }
        if ((kind == ApprovalKind.AGENT_QUESTIONS) != questions.isNotEmpty()) {
            throw ValidationException("agent questions carry the questions, other approvals do not", "questions")
        }
    }

    val isWriteConfirmation: Boolean get() = kind == ApprovalKind.WRITE_CONFIRMATION

    val isAgentQuestions: Boolean get() = kind == ApprovalKind.AGENT_QUESTIONS

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
        if (isAgentQuestions) {
            if (decision.editedArtifacts.isNotEmpty()) throw ValidationException("answers to agent questions cannot edit artefacts", "editedArtifacts")
            if (decision.option.equals(AgentQuestions.ANSWER, ignoreCase = true) && decision.comment.isNullOrBlank()) {
                throw ValidationException("write the answers to the agent's questions in the comment", "comment")
            }
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
