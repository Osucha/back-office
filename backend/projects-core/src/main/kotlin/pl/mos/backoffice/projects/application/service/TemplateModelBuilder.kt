package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.StepRepository

/**
 * Builds the model available to templates: `issue`, `artifacts.<name>` (content), `feedback` (revision comments for
 * this step only: those it received on earlier attempts plus those given since the last task step ran; `feedbackList`
 * as a list, `feedbackHistory` every comment of the run),
 * `project.{key,name,constitution,repositories,repository,defaultBranch,repositoryUrl}` (ADR 0034: `repository` is the
 * primary repository and may be null, as may `defaultBranch` / `repositoryUrl`), `repos` (the repositories of the
 * current step: key, url, path, access, purpose, baseBranch, primary), `repositoryResults` (key -> latest result),
 * `mr`, `review`, `ci` (last signal), `decision`, `input` (incl. `input.ci`), `parameters`, `run.id`, `node`,
 * `branch`, `confidence`, `lastOutcome`.
 */
class TemplateModelBuilder {

    fun build(context: NodeContext, stepRepositories: List<StepRepository> = emptyList()): Map<String, Any?> =
        build(context.run, context.project, context.node, context.attempt, context.state, stepRepositories)

    @Suppress("UNCHECKED_CAST")
    fun build(
        run: WorkflowRun,
        project: Project,
        node: WorkflowNode,
        attempt: Int,
        state: Map<String, Any?>,
        stepRepositories: List<StepRepository> = emptyList(),
    ): Map<String, Any?> {
        val primary = project.primaryRepository
        val artifactContents = run.artifacts.sortedBy { it.createdAt }.associate { it.name to it.content }
        // feedback is scoped to the step (ADR 0019 rework loops): what this node received on earlier attempts plus the
        // comments given since the last task step ran; the whole history stays available as feedbackHistory
        val feedback = RunStateKeys.feedbackOf(state, node.id) + RunStateKeys.pendingFeedback(state)
        val history = (state[RunStateKeys.FEEDBACK] as? List<Any?>)?.mapNotNull { it?.toString() } ?: emptyList()
        val review = run.review?.toStateMap() ?: (state[RunStateKeys.REVIEW] as? Map<String, Any?>) ?: emptyMap()
        val mr = (state[RunStateKeys.MR] as? Map<String, Any?>) ?: run.mergeRequest?.toMap() ?: emptyMap()
        val ci = (state[RunStateKeys.LAST_CI] as? Map<String, Any?>) ?: run.lastSignal?.toStateMap() ?: emptyMap()
        val decision = (state[RunStateKeys.LAST_DECISION] as? Map<String, Any?>) ?: emptyMap()
        return mapOf(
            "issue" to ((state[RunStateKeys.ISSUE] as? Map<String, Any?>) ?: emptyMap<String, Any?>()),
            "artifacts" to artifactContents,
            "feedback" to feedback.joinToString("\n\n"),
            "feedbackList" to feedback,
            "feedbackHistory" to history.joinToString("\n\n"),
            "project" to mapOf(
                "key" to project.key,
                "name" to project.name,
                "constitution" to project.constitution,
                "repositories" to project.repositories.map { it.toTemplateMap() },
                "repository" to primary?.toTemplateMap(),
                "defaultBranch" to primary?.defaultBranch,
                "repositoryUrl" to primary?.url,
            ),
            "repos" to stepRepositories.map { it.toTemplateMap() },
            "repositoryResults" to ((state[RunStateKeys.REPOSITORIES] as? Map<String, Any?>) ?: emptyMap<String, Any?>()),
            "mr" to mr,
            "review" to review,
            "ci" to ci,
            "decision" to decision,
            "input" to mapOf("issueKey" to run.input.issueKey, "parameters" to run.input.parameters, "ci" to (run.input.ci?.toMap() ?: emptyMap<String, Any?>())),
            "parameters" to run.input.parameters,
            "run" to mapOf("id" to run.id.toString(), "short" to run.id.short, "workflow" to run.workflowName, "trigger" to run.trigger.type.name),
            "branch" to (state[RunStateKeys.BRANCH]?.toString()?.takeIf { it.isNotBlank() } ?: run.mergeRequest?.branch),
            "node" to mapOf("id" to node.id, "name" to node.name, "attempt" to attempt),
            "confidence" to state[RunStateKeys.CONFIDENCE],
            "lastOutcome" to state[RunStateKeys.LAST_OUTCOME],
            "lastError" to state[RunStateKeys.LAST_ERROR],
        )
    }
}
