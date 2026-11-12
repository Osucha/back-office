package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.shared.runner.GitAccess

/**
 * One repository of a job, with its access (ADR 0034, ADR 0043). [primary] = the job's primary entry (agent working
 * directory); [library] = a read-only source of the library, keyed `lib-<key>` in the job.
 */
data class StepRepository(val repository: GitRepository, val access: GitAccess, val primary: Boolean, val library: Boolean = false) {
    val key: String get() = repository.key

    fun toTemplateMap(): Map<String, Any?> = mapOf(
        "key" to repository.key, "url" to repository.url, "path" to repository.key, "access" to access.name,
        "purpose" to repository.purpose.name, "baseBranch" to repository.defaultBranch, "primary" to primary,
        "description" to repository.description, "library" to library,
    )
}

/** The repositories of a job plus the problems found while resolving them; [repositories] is empty when [errors] is not. */
data class StepRepositoryPlan(
    val repositories: List<StepRepository>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val valid: Boolean get() = errors.isEmpty()
    val primary: StepRepository? get() = repositories.firstOrNull { it.primary }
    val pushing: List<StepRepository> get() = repositories.filter { it.access == GitAccess.PUSH_MR }

    /** The project repositories (not library sources): what a delivery may write back to. */
    val projectRepositories: List<StepRepository> get() = repositories.filterNot { it.library }
}

/**
 * ADR 0063: the sources of a workflow (`WorkflowDefinition.sources`), cloned into the run directory
 * (docs/architecture/03-pipelines-and-agents.md):
 *
 * - project repositories by key, library sources (ADR 0043) with `library: true`, keyed `lib-<key>` in the job;
 * - no project repository listed: the project's primary repository (a project without repositories has none: agent
 *   steps that allow it, `repositoryOptional`, then work in an empty directory);
 * - every agent job reads them (READ); only a `DELIVER_CHANGES` job writes back, and only to project repositories
 *   ([delivery]);
 * - errors: unknown or duplicate keys, a project repository whose key clashes with a library source, no primary
 *   repository when none is listed;
 * - the job's primary entry is the project's primary repository when it is a source, else the first project source,
 *   else the first library source.
 */
object WorkflowSources {
    const val FIELD = "sources"
    const val REPOSITORY_OPTIONAL = "repositoryOptional"

    /** The sources of a workflow for [project], all READ. [library] resolves a library source by key. */
    fun plan(project: Project, sources: List<WorkflowSource>, library: (String) -> LibrarySource? = { null }): StepRepositoryPlan {
        val errors = mutableListOf<String>()
        sources.groupingBy { it.library to it.key }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "source ${it.second} is listed twice" }
        val shared = sources.filter { it.library }.distinctBy { it.key }.mapNotNull { source ->
            library(source.key) ?: null.also { errors += "library source ${source.key} does not exist (Library > Sources)" }
        }
        val listed = sources.filterNot { it.library }.distinctBy { it.key }.mapNotNull { source ->
            project.repository(source.key)
                ?: null.also { errors += "repository ${source.key} is not a repository of project ${project.key} (${project.describeRepositories()})" }
        }
        val own = when {
            listed.isNotEmpty() || errors.isNotEmpty() -> listed
            project.repositories.isEmpty() -> emptyList()
            else -> listOfNotNull(project.primaryRepository).also {
                if (it.isEmpty()) {
                    errors += "project ${project.key} has no primary repository: list the workflow's sources (${project.describeRepositories()}) or mark one repository primary"
                }
            }
        }
        shared.map { it.jobKey }.filter { key -> own.any { it.key == key } }
            .forEach { errors += "project repository $it clashes with the library source of the same name; rename the repository" }
        if (errors.isNotEmpty()) return StepRepositoryPlan(emptyList(), errors)
        val primaryKey = own.firstOrNull { it.primary }?.key ?: own.firstOrNull()?.key ?: shared.firstOrNull()?.jobKey
        val repositories = own.map { StepRepository(it, GitAccess.READ, it.key == primaryKey) } +
            shared.map { StepRepository(it.asRepository(), GitAccess.READ, it.jobKey == primaryKey, library = true) }
        return StepRepositoryPlan(repositories)
    }

    /**
     * The repositories a `DELIVER_CHANGES` job writes back to: the project repositories of [plan] named in [keys] (empty =
     * every project repository of the plan) with access PUSH_MR; library sources are never delivered. The primary entry
     * is the plan's primary when it is delivered, else the first delivered one.
     */
    fun delivery(plan: StepRepositoryPlan, keys: List<String>): StepRepositoryPlan {
        if (!plan.valid) return plan
        val own = plan.projectRepositories
        val wanted = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val errors = wanted.filter { key -> own.none { it.key == key } }.map { key ->
            if (plan.repositories.any { it.key == key && it.library }) "library source $key is read-only and cannot be delivered"
            else "repository $key is not a project repository among the workflow's sources (${own.joinToString { it.key }.ifEmpty { "none" }})"
        }
        if (errors.isNotEmpty()) return StepRepositoryPlan(emptyList(), errors)
        val delivered = if (wanted.isEmpty()) own else own.filter { it.key in wanted }
        if (delivered.isEmpty()) return StepRepositoryPlan(emptyList(), listOf("the workflow's sources have no project repository to deliver to"))
        val primaryKey = delivered.firstOrNull { it.primary }?.key ?: delivered.first().key
        val repositories = delivered.map { it.copy(access = GitAccess.PUSH_MR, primary = it.key == primaryKey) }
        return StepRepositoryPlan(repositories, warnings = warnings(repositories))
    }

    /** `config.repositoryOptional`: the agent step also runs in a project without repositories (empty directory). */
    fun repositoryOptional(config: Map<String, Any?>): Boolean = config[REPOSITORY_OPTIONAL]?.toString()?.lowercase() == "true"

    /** Why an agent step cannot run with [plan]: it needs a repository ([repositoryOptional] false) and the plan has none. */
    fun stepProblem(project: Project, plan: StepRepositoryPlan, config: Map<String, Any?>): String? = when {
        !plan.valid -> null
        plan.repositories.isEmpty() && !repositoryOptional(config) -> noRepositories(project)
        else -> null
    }

    private fun noRepositories(project: Project) =
        "this step works in a git repository but project ${project.key} has none; add a repository to the project (Projects > Repositories) or use an LLM step"

    private fun warnings(repositories: List<StepRepository>): List<String> {
        val purposes = repositories.map { it.repository.purpose }.toSet()
        return if (RepositoryPurpose.DOCS in purposes && RepositoryPurpose.SOURCE in purposes) {
            listOf(
                "this delivery pushes to a documentation repository and a source repository (" +
                    repositories.joinToString { "${it.key}: ${it.repository.purpose}" } + "); allowed, but reviewers get one merge request per repository",
            )
        } else {
            emptyList()
        }
    }
}
