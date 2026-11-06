package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.application.port.out.TaskRunKind
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.SessionContinuation
import pl.mos.backoffice.projects.domain.service.StepRepositories
import pl.mos.backoffice.projects.domain.service.StepRepositoryPlan
import pl.mos.backoffice.projects.domain.service.StepWorkspace
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog

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
        val resolved = resolveAll(project, nodes)
        steps(project, nodes, resolved).forEach { (node, plan, workspace) ->
            plan.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            plan.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            workspace.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepWorkspace.FIELD, message = it) }
            workspace.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepWorkspace.FIELD, message = it) }
        }
        continuationProblems(nodes, resolved).forEach { (node, problem) ->
            errors += ValidationIssue(nodeId = node.id, field = SessionContinuation.FIELD, message = problem)
        }
        return Issues(errors, warnings)
    }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>): List<String> {
        val resolved = resolveAll(project, nodes)
        return steps(project, nodes, resolved).flatMap { (node, plan, workspace) ->
            (plan.errors + workspace.errors).map { "step \"${node.name}\" (${node.id}): $it" }
        } + continuationProblems(nodes, resolved).map { (node, problem) -> "step \"${node.name}\" (${node.id}): $problem" }
    }

    private data class Step(val node: WorkflowNode.Task, val plan: StepRepositoryPlan, val workspace: StepWorkspace.Setting)

    /** The resolved task of every task step that resolves (by node id). */
    private fun resolveAll(project: Project, nodes: List<WorkflowNode>): Map<String, ResolvedTask> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull()?.let { node.id to it }
        }.toMap()

    private fun steps(project: Project, nodes: List<WorkflowNode>, resolved: Map<String, ResolvedTask>): List<Step> {
        val workspaces = projectWorkspaces?.findByProjectId(project.id).orEmpty()
        return nodes.filterIsInstance<WorkflowNode.Task>().mapNotNull { node ->
            val task = resolved[node.id] ?: return@mapNotNull null
            // ADR 0057: continuing (or being continued by) another step's session sets workspace and session role
            val config = if (runnerStep(task)) SessionContinuation.apply(node.id, task.config, nodes) else task.config
            val workspace = StepWorkspace.of(config, runnerStep(task), workspaces)
            // ADR 0044: a step of a workspace without repositories of its own works on the workspace's sources
            val effective = task.copy(config = StepWorkspace.effectiveConfig(config, workspace.definition, project, pushes(task)))
            Step(node, plan(project, effective, librarySources.resolver()), workspace)
        }
    }

    /**
     * ADR 0057: problems of `continueSession` that block a run: a model answer step has no session, a step cannot
     * continue itself, the named step must exist, be a coding agent step that starts an agent and use the same harness,
     * and the continuations must not form a cycle.
     */
    private fun continuationProblems(nodes: List<WorkflowNode>, resolved: Map<String, ResolvedTask>): List<Pair<WorkflowNode.Task, String>> {
        val tasks = nodes.filterIsInstance<WorkflowNode.Task>()
        val byId = nodes.associateBy { it.id }
        return tasks.mapNotNull { node ->
            val target = SessionContinuation.target(node.task.overrides.config) ?: return@mapNotNull null
            val task = resolved[node.id] ?: return@mapNotNull null
            val earlier = resolved[target]
            val problem = when {
                !runnerStep(task) -> "a model answer has no agent session to continue; remove continueSession"
                target == node.id -> "a step cannot continue its own session"
                byId[target] == null -> "continueSession names step $target, which does not exist"
                earlier == null || !runnerStep(earlier) -> "continueSession names step $target, which is not a coding agent step"
                earlier.config[StepWorkspace.PREPARE_ONLY]?.toString()?.lowercase() == "true" -> "continueSession names step $target, a prepare step that starts no agent session"
                ModelStepCheck.harness(earlier) != ModelStepCheck.harness(task) ->
                    "step $target uses ${ModelStepCheck.harness(earlier)} and this step ${ModelStepCheck.harness(task)}: a session cannot continue in another harness"
                cyclic(node.id, tasks) -> "continueSession forms a cycle"
                else -> null
            }
            problem?.let { node to it }
        }
    }

    private fun cyclic(start: String, tasks: List<WorkflowNode.Task>): Boolean {
        val targets = tasks.associate { it.id to SessionContinuation.target(it.task.overrides.config) }
        val seen = mutableSetOf(start)
        var current = targets[start]
        while (current != null) {
            if (!seen.add(current)) return true
            current = targets[current]
        }
        return false
    }

    companion object {
        /** ADR 0056: a coding agent step runs in a sandbox with a git workspace. */
        fun runnerStep(task: ResolvedTask): Boolean = TaskRunKind.of(task).runner

        /** A runner step pushes unless `config.push` is false (ADR 0031); a prepare step never pushes (ADR 0044). */
        fun pushes(task: ResolvedTask): Boolean = runnerStep(task) && task.config["push"]?.toString()?.lowercase() != "false" &&
            task.config[StepWorkspace.PREPARE_ONLY]?.toString()?.lowercase() != "true"

        fun plan(project: Project, task: ResolvedTask, library: (String) -> LibrarySource? = { null }): StepRepositoryPlan =
            StepRepositories.plan(project, task.config, runnerStep(task), pushes(task), library)

        /** Lookup of library sources by key for [StepRepositories.plan]; nothing without a repository. */
        fun LibrarySourceRepository?.resolver(): (String) -> LibrarySource? = { key -> this?.findByKey(key) }
    }
}
