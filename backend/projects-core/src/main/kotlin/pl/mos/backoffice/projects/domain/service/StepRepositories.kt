package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.shared.runner.GitAccess

/** One repository a step uses, with its access (ADR 0034). [primary] = the job's primary entry (agent working directory). */
data class StepRepository(val repository: GitRepository, val access: GitAccess, val primary: Boolean) {
    val key: String get() = repository.key

    fun toTemplateMap(): Map<String, Any?> = mapOf(
        "key" to repository.key, "url" to repository.url, "path" to repository.key, "access" to access.name,
        "purpose" to repository.purpose.name, "baseBranch" to repository.defaultBranch, "primary" to primary,
        "description" to repository.description,
    )
}

/** The repositories of one step plus the problems found while resolving them; [repositories] is empty when [errors] is not. */
data class StepRepositoryPlan(
    val repositories: List<StepRepository>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val valid: Boolean get() = errors.isEmpty()
    val primary: StepRepository? get() = repositories.firstOrNull { it.primary }
    val pushing: List<StepRepository> get() = repositories.filter { it.access == GitAccess.PUSH_MR }
}

/**
 * Which repositories a step works on and how (ADR 0034, docs/architecture/03-pipelines-and-agents.md):
 *
 * - task config `repositories: [{key, access: READ | PUSH_MR}]` names them explicitly; only runner steps
 *   (IMPLEMENT, SPEC_KIT - [runnerStep]) use repositories, every other step needs none;
 * - absent (or empty): a runner step uses the project's primary repository with the access it derives today
 *   (PUSH_MR when the step [pushes], else READ, ADR 0031);
 * - errors: a runner step in a project without repositories, no primary and no explicit list, unknown or
 *   duplicate keys, an access other than READ / PUSH_MR, PUSH_MR on a step that does not push, `repositories` on a
 *   non-runner step;
 * - warning: one step pushes to a DOCS and a SOURCE repository (allowed, made visible).
 *
 * The job's primary entry is the project's primary repository when the step uses it, else the step's first one.
 */
object StepRepositories {
    const val CONFIG_KEY = "repositories"
    const val REPOSITORY_OPTIONAL = "repositoryOptional"
    const val FIELD = "task.config.repositories"

    private val ALLOWED = setOf(GitAccess.READ, GitAccess.PUSH_MR)

    /** A requested entry; [access] is null when the config value is not a known level. */
    data class Request(val key: String, val access: GitAccess?, val rawAccess: String?)

    /** The explicit list of [config], or null when the step names none (absent, null or an empty list). */
    fun requested(config: Map<String, Any?>): List<Request>? {
        val raw = config[CONFIG_KEY] ?: return null
        val items = raw as? List<*> ?: return listOf(Request("", null, raw.toString()))
        if (items.isEmpty()) return null
        return items.map { item ->
            when (item) {
                is Map<*, *> -> {
                    val access = item["access"]?.toString()?.trim()
                    Request(item["key"]?.toString()?.trim() ?: "", access?.let { a -> GitAccess.entries.firstOrNull { it.name == a.uppercase() } } ?: if (access == null) GitAccess.READ else null, access)
                }
                // shorthand "docs" = read access
                is String -> Request(item.trim(), GitAccess.READ, null)
                else -> Request(item?.toString() ?: "", null, null)
            }
        }
    }

    fun plan(project: Project, config: Map<String, Any?>, runnerStep: Boolean, pushes: Boolean): StepRepositoryPlan {
        val requested = try {
            requested(config)
        } catch (e: RuntimeException) {
            return StepRepositoryPlan(emptyList(), listOf("repositories must be a list of {key, access}"))
        }
        if (requested == null) {
            // a step that may work without code (config repositoryOptional, e.g. agent-task) gets an empty workspace
            if (runnerStep && project.repositories.isEmpty() && repositoryOptional(config)) return StepRepositoryPlan(emptyList())
            return if (runnerStep) defaultPlan(project, pushes) else StepRepositoryPlan(emptyList())
        }
        if (!runnerStep) {
            return StepRepositoryPlan(emptyList(), listOf("only runner steps (IMPLEMENT, SPEC_KIT) work in repositories; remove repositories from this step"))
        }
        val errors = mutableListOf<String>()
        if (project.repositories.isEmpty()) errors += noRepositories(project)
        requested.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "repository $it is listed twice" }
        val resolved = requested.distinctBy { it.key }.mapNotNull { request ->
            val repository = project.repository(request.key)
            when {
                project.repositories.isEmpty() -> null
                request.key.isBlank() -> null.also { errors += "repository entries need a key" }
                repository == null -> null.also { errors += "repository ${request.key} is not a repository of project ${project.key} (${project.describeRepositories()})" }
                request.access == null || request.access !in ALLOWED ->
                    null.also { errors += "access of repository ${request.key} must be READ or PUSH_MR, not ${request.rawAccess}" }
                request.access == GitAccess.PUSH_MR && !pushes ->
                    null.also { errors += "repository ${request.key} has access PUSH_MR but this step does not push (push is off or the step cannot push); use READ" }
                else -> repository to request.access
            }
        }
        if (errors.isNotEmpty()) return StepRepositoryPlan(emptyList(), errors)
        val primaryKey = resolved.firstOrNull { it.first.primary }?.first?.key ?: resolved.first().first.key
        val repositories = resolved.map { (repo, access) -> StepRepository(repo, access, repo.key == primaryKey) }
        return StepRepositoryPlan(repositories, warnings = warnings(repositories))
    }

    /** `config.repositoryOptional`: the runner step also runs in a project without repositories (empty workspace). */
    fun repositoryOptional(config: Map<String, Any?>): Boolean = config[REPOSITORY_OPTIONAL]?.toString()?.lowercase() == "true"

    private fun defaultPlan(project: Project, pushes: Boolean): StepRepositoryPlan {
        if (project.repositories.isEmpty()) return StepRepositoryPlan(emptyList(), listOf(noRepositories(project)))
        val primary = project.primaryRepository ?: return StepRepositoryPlan(
            emptyList(),
            listOf("project ${project.key} has no primary repository: choose the repositories of this step (${project.describeRepositories()}) or mark one repository primary"),
        )
        return StepRepositoryPlan(listOf(StepRepository(primary, if (pushes) GitAccess.PUSH_MR else GitAccess.READ, primary = true)))
    }

    private fun noRepositories(project: Project) =
        "this step works in a git repository but project ${project.key} has none; add a repository to the project (Projects > Repositories) or use an LLM step"

    private fun warnings(repositories: List<StepRepository>): List<String> {
        val pushedPurposes = repositories.filter { it.access == GitAccess.PUSH_MR }.map { it.repository.purpose }.toSet()
        return if (RepositoryPurpose.DOCS in pushedPurposes && RepositoryPurpose.SOURCE in pushedPurposes) {
            listOf(
                "this step pushes to a documentation repository and a source repository (" +
                    repositories.filter { it.access == GitAccess.PUSH_MR }.joinToString { "${it.key}: ${it.repository.purpose}" } +
                    "); allowed, but reviewers get one merge request per repository",
            )
        } else {
            emptyList()
        }
    }
}
