package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.StepRepositories
import pl.mos.backoffice.projects.domain.service.StepRepositoryPlan
import pl.mos.backoffice.projects.domain.service.StepWorkspace
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
    /** Run workspaces of projects (ADR 0044); null = none (tests). */
    private val projectWorkspaces: ProjectWorkspaceRepository? = null,
) {

    data class Issues(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>)

    fun designIssues(project: Project, nodes: List<WorkflowNode>): Issues {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()
        steps(project, nodes).forEach { (node, plan, workspace) ->
            plan.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            plan.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            workspace.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepWorkspace.FIELD, message = it) }
            workspace.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepWorkspace.FIELD, message = it) }
        }
        return Issues(errors, warnings)
    }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>): List<String> =
        steps(project, nodes).flatMap { (node, plan, workspace) ->
        (plan.errors + workspace.errors).map { "step \"${node.name}\" (${node.id}): $it" }
    }

    private data class Step(val node: WorkflowNode.Task, val plan: StepRepositoryPlan, val workspace: StepWorkspace.Setting)

    private fun steps(project: Project, nodes: List<WorkflowNode>): List<Step> {
        val workspaces = projectWorkspaces?.findByProjectId(project.id).orEmpty()
        return nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            val task = runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull() ?: return@mapNotNull null
            val workspace = StepWorkspace.of(task.config, runnerStep(task), workspaces)
            // ADR 0044: a step of a workspace without repositories of its own works on the workspace's sources
            val effective = task.copy(config = StepWorkspace.effectiveConfig(task.config, workspace.definition, project, pushes(task)))
            Step(node, plan(project, effective, librarySources.resolver()), workspace)
        }
    }

    companion object {
        /** Executors that run in a sandbox with a git workspace. */
        val RUNNER_EXECUTORS = setOf(TaskExecutor.IMPLEMENT, TaskExecutor.SPEC_KIT)

        fun runnerStep(task: ResolvedTask): Boolean = task.executor in RUNNER_EXECUTORS

        /** A runner step pushes unless `config.push` is false (ADR 0031); a prepare step never pushes (ADR 0044). */
        fun pushes(task: ResolvedTask): Boolean = runnerStep(task) && task.config["push"]?.toString()?.lowercase() != "false" &&
            task.config[StepWorkspace.PREPARE_ONLY]?.toString()?.lowercase() != "true"

        fun plan(project: Project, task: ResolvedTask, library: (String) -> LibrarySource? = { null }): StepRepositoryPlan =
            StepRepositories.plan(project, task.config, runnerStep(task), pushes(task), library)

        /** Lookup of library sources by key for [StepRepositories.plan]; nothing without a repository. */
        fun LibrarySourceRepository?.resolver(): (String) -> LibrarySource? = { key -> this?.findByKey(key) }
    }
}
