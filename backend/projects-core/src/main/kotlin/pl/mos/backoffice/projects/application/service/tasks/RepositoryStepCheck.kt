package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.TaskRunKind
import pl.mos.backoffice.projects.application.service.tasks.RunnerTaskSupport.Companion.list
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.projects.domain.service.StepRepositoryPlan
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.projects.domain.service.WorkflowSources
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Checks the workflow's sources (ADR 0063), the directory (ADR 0062: the agent's) and the questions of the steps of a
 * workflow (ADR 0060) and its `DELIVER_CHANGES` nodes, for the designer validation ([designIssues]: errors and warnings)
 * and before a run starts ([runBlockers]: the project may have lost a repository after the workflow was saved). The
 * rules are [WorkflowSources] and [RunDirectory]; this class resolves the tasks.
 *
 * A delivery is a problem when the workflow's sources hold no project repository (nothing to write back to), when it
 * names a repository that is not one of them, or when every coding step's agent works in a `SEPARATE` directory (their
 * changes are thrown away, the run directory stays unchanged).
 */
class RepositoryStepCheck(
    private val catalog: TaskCatalog,
    /** Library sources the workflow may list (ADR 0043); null = none (tests). */
    private val librarySources: LibrarySourceRepository? = null,
) {

    data class Issues(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>)

    fun designIssues(project: Project, sources: List<WorkflowSource>, nodes: List<WorkflowNode>): Issues {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()
        val plan = WorkflowSources.plan(project, sources, librarySources.resolver())
        val steps = steps(project, nodes, plan)
        // the sources matter only when a step works in them
        if (steps.any { it.coding } || nodes.any(::delivery)) plan.errors.forEach { errors += ValidationIssue(field = WorkflowSources.FIELD, message = it) }
        steps.forEach { (node, problem, directory, questions, _) ->
            problem?.let { errors += ValidationIssue(nodeId = node.id, field = WorkflowSources.FIELD, message = it) }
            directory.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_PREPARE_ONLY, message = it) }
            directory.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_PREPARE_ONLY, message = it) }
            questions.forEach { errors += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_QUESTIONS, message = it) }
        }
        deliveryProblems(plan, nodes, steps).forEach { (node, message) -> errors += ValidationIssue(nodeId = node.id, field = FIELD_DELIVERY, message = message) }
        return Issues(errors, warnings)
    }

    fun runBlockers(project: Project, sources: List<WorkflowSource>, nodes: List<WorkflowNode>): List<String> {
        val plan = WorkflowSources.plan(project, sources, librarySources.resolver())
        val steps = steps(project, nodes, plan)
        val sourceErrors = if (steps.any { it.coding } || nodes.any(::delivery)) plan.errors.map { "sources of the workflow: $it" } else emptyList()
        return sourceErrors + steps.flatMap { (node, problem, directory, questions, _) ->
            (listOfNotNull(problem) + directory.errors + questions).map { "step \"${node.name}\" (${node.id}): $it" }
        } + deliveryProblems(plan, nodes, steps).map { (node, message) -> "step \"${node.name}\" (${node.id}): $message" }
    }

    /** One task step: what keeps it from working in the sources, its directory and its question settings; [coding] = a coding agent runs it. */
    private data class Step(val node: WorkflowNode.Task, val problem: String?, val directory: RunDirectory.Setting, val questions: List<String>, val coding: Boolean)

    /** The task steps that resolve (a step that does not is the validator's finding). */
    private fun steps(project: Project, nodes: List<WorkflowNode>, plan: StepRepositoryPlan): List<Step> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            val task = runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull() ?: return@mapNotNull null
            if (!runnerStep(task)) return@mapNotNull Step(node, null, RunDirectory.of(task.config, task.agent, runnerStep = false), RunDirectory.questions(task.config).errors, coding = false)
            Step(node, WorkflowSources.stepProblem(project, plan, task.config), RunDirectory.of(task.config, task.agent, runnerStep = true), RunDirectory.questions(task.config).errors, coding = true)
        }

    private fun deliveryProblems(plan: StepRepositoryPlan, nodes: List<WorkflowNode>, steps: List<Step>): List<Pair<WorkflowNode, String>> {
        val deliveries = nodes.filterIsInstance<WorkflowNode.Action>().filter(::delivery)
        if (deliveries.isEmpty() || !plan.valid) return emptyList()
        val agentsWork = steps.filter { it.coding && !it.directory.prepareOnly }
        val onlySeparate = agentsWork.isNotEmpty() && agentsWork.none { it.directory.runDirectory }
        return deliveries.flatMap { node ->
            val config = ActionCatalog.effectiveConfig(node.action)
            val problems = mutableListOf<String>()
            if (plan.projectRepositories.isEmpty()) {
                problems += "nothing to deliver: the workflow's sources hold no project repository (add a repository to the project or list one in the sources)"
            } else {
                problems += WorkflowSources.delivery(plan, config.list(ActionCatalog.REPOSITORIES)).errors
            }
            if (onlySeparate) {
                problems += "every coding step's agent works in a SEPARATE directory, so the run directory has nothing to deliver; " +
                    "choose an agent with directory RUN for the work to deliver"
            }
            problems.map { node to it }
        }
    }

    companion object {
        const val FIELD_DELIVERY = "action.config"

        /** ADR 0056: a coding agent step runs in a sandbox with a git workspace. */
        fun runnerStep(task: ResolvedTask): Boolean = TaskRunKind.of(task).runner

        private fun delivery(node: WorkflowNode): Boolean = node is WorkflowNode.Action && node.action.type == ActionType.DELIVER_CHANGES

        /** Lookup of library sources by key for [WorkflowSources.plan]; nothing without a repository. */
        fun LibrarySourceRepository?.resolver(): (String) -> LibrarySource? = { key -> this?.findByKey(key) }
    }
}
