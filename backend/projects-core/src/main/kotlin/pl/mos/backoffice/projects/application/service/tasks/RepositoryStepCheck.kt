package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.TaskRunKind
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.projects.domain.service.StepRepositories
import pl.mos.backoffice.projects.domain.service.StepRepositoryPlan
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Checks the repositories, the directory and the session of the steps of a workflow (ADR 0034, 0060) for the designer
 * validation ([designIssues]: errors and warnings on `task.config.*`) and before a run starts ([runBlockers]: the
 * project may have lost a repository after the workflow was saved). The rules are [StepRepositories] and
 * [RunDirectory]; this class resolves the tasks.
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
        val resolved = resolveAll(project, nodes)
        steps(project, nodes, resolved).forEach { (node, plan, directory, questions) ->
            plan.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            plan.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = StepRepositories.FIELD, message = it) }
            directory.errors.forEach { errors += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_DIRECTORY, message = it) }
            directory.warnings.forEach { warnings += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_DIRECTORY, message = it) }
            questions.forEach { errors += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_QUESTIONS, message = it) }
        }
        continuationProblems(nodes, resolved).forEach { (node, problem) ->
            errors += ValidationIssue(nodeId = node.id, field = RunDirectory.FIELD_CONTINUE, message = problem)
        }
        return Issues(errors, warnings)
    }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>): List<String> {
        val resolved = resolveAll(project, nodes)
        return steps(project, nodes, resolved).flatMap { (node, plan, directory, questions) ->
            (plan.errors + directory.errors + questions).map { "step \"${node.name}\" (${node.id}): $it" }
        } + continuationProblems(nodes, resolved).map { (node, problem) -> "step \"${node.name}\" (${node.id}): $problem" }
    }

    private data class Step(val node: WorkflowNode.Task, val plan: StepRepositoryPlan, val directory: RunDirectory.Setting, val questions: List<String>)

    /** The resolved task of every task step that resolves (by node id). */
    private fun resolveAll(project: Project, nodes: List<WorkflowNode>): Map<String, ResolvedTask> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull()?.let { node.id to it }
        }.toMap()

    private fun steps(project: Project, nodes: List<WorkflowNode>, resolved: Map<String, ResolvedTask>): List<Step> =
        nodes.filterIsInstance<WorkflowNode.Task>().mapNotNull { node ->
            val task = resolved[node.id] ?: return@mapNotNull null
            Step(node, plan(project, task, librarySources.resolver()), RunDirectory.of(task.config, runnerStep(task)), RunDirectory.questions(task.config).errors)
        }

    /**
     * ADR 0057/0060: problems of a session continuation that block a run: a model answer step has no session, a step
     * cannot continue itself, the named step must exist, be a coding agent step that starts an agent session in the run
     * directory (not NEW, not SEPARATE, not a prepare step) and run the same agent (its home holds the session), and the
     * continuations must not form a cycle.
     */
    private fun continuationProblems(nodes: List<WorkflowNode>, resolved: Map<String, ResolvedTask>): List<Pair<WorkflowNode.Task, String>> {
        val tasks = nodes.filterIsInstance<WorkflowNode.Task>()
        val byId = nodes.associateBy { it.id }
        return tasks.mapNotNull { node ->
            val task = resolved[node.id] ?: return@mapNotNull null
            if (!runnerStep(task)) {
                val asked = task.config[RunDirectory.CONTINUE_SESSION]?.toString()?.isNotBlank() == true
                return@mapNotNull if (asked) node to "a model answer has no agent session to continue; remove continueSession" else null
            }
            val setting = RunDirectory.of(task.config, runnerStep = true)
            val target = setting.continueFrom ?: return@mapNotNull null
            val earlier = resolved[target]
            val earlierSetting = earlier?.let { RunDirectory.of(it.config, runnerStep(it)) }
            val problem = when {
                target == node.id -> "a step cannot continue its own session"
                byId[target] == null -> "continueSession names step $target, which does not exist"
                earlier == null || !runnerStep(earlier) -> "continueSession names step $target, which is not a coding agent step"
                earlierSetting?.prepareOnly == true -> "continueSession names step $target, a prepare step that starts no agent session"
                earlierSetting?.runDirectory == false -> "continueSession names step $target, which works in a SEPARATE directory: its session is thrown away"
                earlierSetting?.session == RunDirectory.Session.NEW -> "continueSession names step $target, which starts a new session every time (session NEW)"
                earlier.agent.key != task.agent.key ->
                    "step $target runs agent ${earlier.agent.key} and this step ${task.agent.key}: a session continues only with the same agent (its home holds the session)"
                cyclic(node.id, tasks, resolved) -> "continueSession forms a cycle"
                else -> null
            }
            problem?.let { node to it }
        }
    }

    private fun cyclic(start: String, tasks: List<WorkflowNode.Task>, resolved: Map<String, ResolvedTask>): Boolean {
        val targets = tasks.associate { node -> node.id to resolved[node.id]?.let { RunDirectory.of(it.config, runnerStep(it)).continueFrom } }
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
            task.config[RunDirectory.PREPARE_ONLY]?.toString()?.lowercase() != "true"

        fun plan(project: Project, task: ResolvedTask, library: (String) -> LibrarySource? = { null }): StepRepositoryPlan =
            StepRepositories.plan(project, task.config, runnerStep(task), pushes(task), library)

        /** Lookup of library sources by key for [StepRepositories.plan]; nothing without a repository. */
        fun LibrarySourceRepository?.resolver(): (String) -> LibrarySource? = { key -> this?.findByKey(key) }
    }
}
