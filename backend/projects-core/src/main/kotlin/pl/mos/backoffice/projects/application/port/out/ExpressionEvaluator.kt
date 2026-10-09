package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiMergeRequest
import pl.mos.backoffice.projects.domain.model.IssueSnapshot
import pl.mos.backoffice.projects.domain.model.WorkflowRun

/*
 * Read-only view of the run state that decision expressions are evaluated against
 * (docs/architecture/03-pipelines-and-agents.md). Every sub-object is non-null with nullable fields so
 * `review.verdict != 'REQUEST_CHANGES'` works before any review ran; maps are used only where the
 * indexer syntax is natural (`artifacts['spec']`, `input.parameters['x']`).
 */

data class InputView(val issueKey: String? = null, val parameters: Map<String, Any?> = emptyMap(), val ci: CiContextView = CiContextView())

data class CiMergeRequestView(
    val iid: String? = null,
    val title: String? = null,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
    val url: String? = null,
    val author: String? = null,
) {
    companion object {
        fun of(mr: CiMergeRequest?) = CiMergeRequestView(mr?.iid, mr?.title, mr?.sourceBranch, mr?.targetBranch, mr?.url, mr?.author)
    }
}

data class CiContextView(
    val provider: String? = null,
    val projectPath: String? = null,
    val ref: String? = null,
    val commitSha: String? = null,
    val defaultBranch: String? = null,
    val actor: String? = null,
    val mergeRequest: CiMergeRequestView = CiMergeRequestView(),
    val hasMergeRequest: Boolean = false,
) {
    companion object {
        fun of(ci: CiContext?) = CiContextView(
            ci?.provider?.name, ci?.projectPath, ci?.ref, ci?.commitSha, ci?.defaultBranch, ci?.actor,
            CiMergeRequestView.of(ci?.mergeRequest), ci?.mergeRequest != null,
        )
    }
}

data class IssueView(
    val key: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val type: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList(),
) {
    companion object {
        fun of(issue: IssueSnapshot?) = IssueView(
            issue?.key, issue?.summary, issue?.description, issue?.type, issue?.priority, issue?.status,
            issue?.labels ?: emptyList(), issue?.components ?: emptyList(),
        )
    }
}

data class ArtifactView(val id: String, val name: String, val kind: String, val sizeBytes: Long)

data class ReviewView(
    val verdict: String? = null,
    val score: Int? = null,
    val summary: String? = null,
    val blockers: Int = 0,
    val majors: Int = 0,
    val findings: Int = 0,
    /** A guard (injection markers, invalid answer) blocked automatic approval; route to a person. */
    val humanReviewRequired: Boolean = false,
)

data class MergeRequestView(val url: String? = null, val branch: String? = null, val targetBranch: String? = null)

data class CiSignalView(val signal: String? = null, val status: String? = null, val message: String? = null)

data class DecisionView(val option: String? = null, val comment: String? = null)

data class WorkflowStateView(
    val input: InputView = InputView(),
    val issue: IssueView = IssueView(),
    val artifacts: Map<String, ArtifactView> = emptyMap(),
    val confidence: Double? = null,
    val lastOutcome: String? = null,
    val review: ReviewView = ReviewView(),
    val mr: MergeRequestView = MergeRequestView(),
    val ci: CiSignalView = CiSignalView(),
    val decision: DecisionView = DecisionView(),
    val feedback: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = WorkflowStateView()

        /** Builds the view from the persisted run and the routing state of the graph. */
        @Suppress("UNCHECKED_CAST")
        fun of(run: WorkflowRun, state: Map<String, Any?>): WorkflowStateView {
            val issue = (state["issue"] as? Map<String, Any?>)?.let { IssueSnapshot.fromMap(it) }
            val reviewState = state["review"] as? Map<String, Any?>
            val review = run.review
            val mr = (state["mr"] as? Map<String, Any?>) ?: run.mergeRequest?.toMap()
            val ci = (state["ci"] as? Map<String, Any?>) ?: run.lastSignal?.toStateMap()
            val decision = state["decision"] as? Map<String, Any?> ?: state["lastDecision"] as? Map<String, Any?>
            val feedback = (state["feedback"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            return WorkflowStateView(
                input = InputView(run.input.issueKey, run.input.parameters, CiContextView.of(run.input.ci)),
                issue = IssueView.of(issue),
                artifacts = run.artifacts.sortedBy { it.createdAt }.associate { it.name to ArtifactView(it.id.toString(), it.name, it.kind.name, it.sizeBytes) },
                confidence = (state["confidence"] as? Number)?.toDouble()?.takeIf { it >= 0 },
                lastOutcome = state["lastOutcome"]?.toString(),
                review = when {
                    review != null -> ReviewView(review.verdict.name, review.score, review.summary, review.blockers, review.majors, review.findings.size, review.humanReviewRequired)
                    reviewState != null -> ReviewView(
                        reviewState["verdict"]?.toString(), (reviewState["score"] as? Number)?.toInt(), reviewState["summary"]?.toString(),
                        (reviewState["blockers"] as? Number)?.toInt() ?: 0, (reviewState["majors"] as? Number)?.toInt() ?: 0,
                        (reviewState["findings"] as? Number)?.toInt() ?: 0, reviewState["humanReviewRequired"] == true,
                    )
                    else -> ReviewView()
                },
                mr = MergeRequestView(mr?.get("url")?.toString(), mr?.get("branch")?.toString(), mr?.get("targetBranch")?.toString()),
                ci = CiSignalView(ci?.get("signal")?.toString(), ci?.get("status")?.toString(), ci?.get("message")?.toString()),
                decision = DecisionView(decision?.get("option")?.toString(), decision?.get("comment")?.toString()),
                feedback = feedback,
            )
        }
    }
}

/** Evaluates decision expressions (SpEL in `projects-adapters`, read-only, sandboxed). */
interface ExpressionEvaluator {
    /** Returns an error message when the expression does not parse or is not boolean; null when fine. */
    fun validate(expression: String): String?

    /** Evaluates a boolean expression; throws `ValidationException` when it cannot be evaluated. */
    fun evaluate(expression: String, view: WorkflowStateView): Boolean
}
