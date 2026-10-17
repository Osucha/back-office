package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.StepRepositories
import pl.mos.backoffice.projects.domain.service.StepRepositoryPlan
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor

/**
 * Checks the repositories of the steps of a workflow (ADR 0034) for the designer validation ([designIssues]: errors
 * and warnings on `task.config.repositories`) and before a run starts ([runBlockers]: the project may have lost a
 * repository after the workflow was saved). The rules are [StepRepositories]; this class resolves the tasks.
 */
class RepositoryStepCheck(
    private val catalog: TaskCatalog,
    /** Library sources steps may attach (ADR 0043); null = none (tests). */
    private val librarySources: LibrarySourceRepository? = null,
) {

    data class Issues(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>)

    fun designIssues(project: Project, nodes: List<WorkflowNode>): Issues {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()
        steps(project, nodes).forEach { (node, plan) ->
            plan.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            plan.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
        }
        return Issues(errors, warnings)
    }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>): List<String> = steps(project, nodes).flatMap { (node, plan) ->
        plan.errors.map { "step \"${node.name}\" (${node.id}): $it" }
    }

    private fun steps(project: Project, nodes: List<WorkflowNode>): List<Pair<WorkflowNode.Task, StepRepositoryPlan>> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            val task = runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull() ?: return@mapNotNull null
            node to plan(project, task, librarySources.resolver())
        }

    companion object {
        /** Executors that run in a sandbox with a git workspace. */
        val RUNNER_EXECUTORS = setOf(TaskExecutor.IMPLEMENT, TaskExecutor.SPEC_KIT)

        fun runnerStep(task: ResolvedTask): Boolean = task.executor in RUNNER_EXECUTORS

        /** A runner step pushes unless `config.push` is false (ADR 0031). */
        fun pushes(task: ResolvedTask): Boolean = runnerStep(task) && task.config["push"]?.toString()?.lowercase() != "false"

        fun plan(project: Project, task: ResolvedTask, library: (String) -> LibrarySource? = { null }): StepRepositoryPlan =
            StepRepositories.plan(project, task.config, runnerStep(task), pushes(task), library)

        /** Lookup of library sources by key for [StepRepositories.plan]; nothing without a repository. */
        fun LibrarySourceRepository?.resolver(): (String) -> LibrarySource? = { key -> this?.findByKey(key) }
    }
}
