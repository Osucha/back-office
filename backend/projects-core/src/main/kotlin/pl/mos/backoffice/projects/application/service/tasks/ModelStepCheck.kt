package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.service.ModelResolver.Companion.producesReview
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.StepWorkspace
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import java.util.UUID

/**
 * ADR 0050: the model of every task step, resolved as a run resolves it ([ModelResolver]: node or template, then the
 * project default or, for review steps, the review default), and whether the user can use it ([ModelAvailability]).
 * Gives the designer warnings ([designIssues]), the start check ([runBlockers]) and the per-step [plan] shown before
 * a run starts. Steps without a model (Jira, notifications, workspace preparation) are left out.
 */
class ModelStepCheck(
    private val catalog: TaskCatalog,
    private val availability: ModelAvailability,
    /** ADR 0050 step 2: the user's own and the platform default models; null = none (tests). */
    private val defaults: ModelDefaults? = null,
) {

    /** Where a step's model comes from. */
    enum class Source { NODE, TEMPLATE, PROJECT, PROJECT_REVIEW, PERSONAL_DEFAULT, PLATFORM_DEFAULT, NONE }

    data class StepModel(
        val nodeId: String,
        val nodeName: String,
        val stepKind: StepKind,
        val harness: AgentHarness?,
        val source: Source,
        val profileId: ModelProfileId?,
        val model: ModelOption?,
    ) {
        /** Why the step cannot run with its model; null = it can. */
        val problem: String?
            get() = when {
                profileId == null -> "no model profile is set on the step, its template, the project defaults or a default model"
                model == null -> "its model profile no longer exists"
                !model.usable -> "${model.displayName}: ${model.problem}"
                else -> null
            }
    }

    fun plan(project: Project, nodes: List<WorkflowNode>, userId: UUID?): List<StepModel> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            val task = runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull() ?: return@mapNotNull null
            val kind = stepKind(task) ?: return@mapNotNull null
            val harness = if (kind == StepKind.CODING_AGENT) harness(task) else null
            val (profileId, source) = modelOf(node, task, project) ?: defaultModel(userId, kind)
            val use = ModelUse(kind, harness, project.personal, userId)
            StepModel(node.id, node.name, kind, harness, source, profileId, profileId?.let { availability.check(it, use) })
        }

    /** Warnings: a workflow is saved even when a model is not usable now (a runner may connect, a key may be added). */
    /** ADR 0050 step 2: the user's own default for the kind of step, then the platform's (as [ModelResolver] does). */
    private fun defaultModel(userId: UUID?, kind: StepKind): Pair<ModelProfileId?, Source> =
        defaults?.resolve(userId, kind)?.let { it.profileId to if (it.personal) Source.PERSONAL_DEFAULT else Source.PLATFORM_DEFAULT }
            ?: (null to Source.NONE)

    fun designIssues(project: Project, nodes: List<WorkflowNode>, userId: UUID?): List<ValidationIssue> =
        plan(project, nodes, userId).mapNotNull { step ->
            step.problem?.let { ValidationIssue(nodeId = step.nodeId, field = FIELD, message = it) }
                ?: step.model?.note?.let { ValidationIssue(nodeId = step.nodeId, field = FIELD, message = "${step.model.displayName}: $it") }
        }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>, userId: UUID?): List<String> =
        plan(project, nodes, userId).mapNotNull { step -> step.problem?.let { "step \"${step.nodeName}\" (${step.nodeId}): $it" } }

    companion object {
        const val FIELD = "task.overrides.modelProfileId"

        fun stepKind(task: ResolvedTask): StepKind? = when (task.executor) {
            TaskExecutor.LLM -> StepKind.LLM_CALL
            TaskExecutor.IMPLEMENT, TaskExecutor.SPEC_KIT ->
                StepKind.CODING_AGENT.takeUnless { task.config[StepWorkspace.PREPARE_ONLY]?.toString()?.lowercase() == "true" }
            else -> null
        }

        /** The coding agent of a runner step (`config.agent`, OpenCode by default). */
        fun harness(task: ResolvedTask): AgentHarness =
            AgentHarness.entries.firstOrNull { it.name.equals(task.config["agent"]?.toString(), ignoreCase = true) } ?: AgentHarness.OPENCODE

        private fun modelOf(node: WorkflowNode.Task, task: ResolvedTask, project: Project): Pair<ModelProfileId?, Source>? = when {
            node.task.overrides.modelProfileId != null -> node.task.overrides.modelProfileId to Source.NODE
            task.modelProfileId != null -> task.modelProfileId to Source.TEMPLATE
            task.producesReview && project.defaults.reviewModelProfileId != null -> project.defaults.reviewModelProfileId to Source.PROJECT_REVIEW
            project.defaults.modelProfileId != null -> project.defaults.modelProfileId to Source.PROJECT
            else -> null
        }
    }
}
