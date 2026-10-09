package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

private const val MUST_NOT_BE_BLANK = "must not be blank"

/** What a project repository holds (ADR 0034): a label for people and a hint for the agent. */
enum class RepositoryPurpose { DOCS, SOURCE, OTHER }

/**
 * One git repository of a project (ADR 0034; OpenAPI `ProjectRepository`). The runner clones it into
 * `/workspace/<key>` and, for steps with PUSH_MR access, pushes the job branch to it. No SCM API is involved
 * (ADR 0013). [primary] marks the repository of steps that name none (at most one per project).
 */
data class GitRepository(
    val key: String,
    val url: String,
    val defaultBranch: String = DEFAULT_BRANCH,
    val purpose: RepositoryPurpose = RepositoryPurpose.SOURCE,
    val description: String? = null,
    val primary: Boolean = false,
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("must match ${KEY_PATTERN.pattern}", "repositories.key")
        if (url.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "repositories.$key.url")
        if (defaultBranch.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "repositories.$key.defaultBranch")
    }

    fun toTemplateMap(): Map<String, Any?> = mapOf(
        "key" to key, "url" to url, "defaultBranch" to defaultBranch, "purpose" to purpose.name, "description" to description, "primary" to primary,
    )

    companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{0,30}$")
        const val DEFAULT_BRANCH = "main"

        /** Key of the repository a pre-0034 project's single repository became (migration V109, deprecated API field). */
        const val LEGACY_KEY = "main"

        /** The deprecated single `repository` of the API as the one primary SOURCE repository `main`. */
        fun legacy(url: String, defaultBranch: String?): GitRepository =
            GitRepository(LEGACY_KEY, url.trim(), defaultBranch?.takeIf { it.isNotBlank() } ?: DEFAULT_BRANCH, RepositoryPurpose.SOURCE, null, primary = true)
    }
}

data class IssueTrackerBinding(
    val jiraProjectKey: String? = null,
    val jql: String? = null,
)

/**
 * The project's Jira connections (ADR 0035): the system connection (default of every Jira step, explicitly chosen
 * from the project's or the platform's connections) and an ordered list of further connections its steps may use.
 * Personal connections are never part of it. Without a system connection the ADR 0030 rule (least privilege over every
 * project and platform connection) applies.
 */
data class ProjectJiraConnections(
    val systemConnectionId: ConnectionId? = null,
    val additionalConnectionIds: List<ConnectionId> = emptyList(),
) {
    init {
        if (additionalConnectionIds.size > MAX_ADDITIONAL) throw ValidationException("at most $MAX_ADDITIONAL additional connections", "additionalConnectionIds")
        if (additionalConnectionIds.distinct().size != additionalConnectionIds.size) {
            throw ValidationException("a connection is listed twice", "additionalConnectionIds")
        }
        if (systemConnectionId != null && systemConnectionId in additionalConnectionIds) {
            throw ValidationException("the system connection is not listed again as an additional connection", "additionalConnectionIds")
        }
    }

    /** The system connection first, then the additional ones in their order. */
    fun list(): List<ConnectionId> = listOfNotNull(systemConnectionId) + additionalConnectionIds

    /** The same settings without [id] (the connection was deleted). */
    fun without(id: ConnectionId): ProjectJiraConnections =
        ProjectJiraConnections(systemConnectionId.takeIf { it != id }, additionalConnectionIds.filterNot { it == id })

    val configured: Boolean get() = systemConnectionId != null

    companion object {
        const val MAX_ADDITIONAL = 50
    }
}

data class ProjectDefaults(
    val modelProfileId: ModelProfileId? = null,
    val reviewModelProfileId: ModelProfileId? = null,
)

/** Whether a failing CI run fails the CI job (`BLOCKING`) or only reports (`ADVISORY`). */
enum class CiMode { BLOCKING, ADVISORY }

/**
 * Maps an entrypoint key used by `backoffice-ci run <key>` to a workflow of the project.
 * `uploadDiff` tells the client script whether to send the diff with the request.
 */
data class CiEntrypoint(
    val key: String,
    val workflowId: WorkflowDefinitionId,
    val mode: CiMode = CiMode.BLOCKING,
    val description: String? = null,
    val uploadDiff: Boolean = true,
    /** Optional key of a project repository: only CI jobs running in that repository may start the entrypoint (ADR 0034). */
    val repository: String? = null,
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("must match ${KEY_PATTERN.pattern}", "ciEntrypoints.key")
    }

    companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{1,39}$")
        const val CODE_REVIEW = "code-review"
    }
}

/**
 * Aggregate root: one project the owner delivers work for - software by default, any LLM work in general
 * (ADR 0034). Holds zero or more git repositories, the issue tracker binding, the Spec Kit switch and constitution
 * (ADR 0036; the constitution is kept while Spec Kit is off), the Jira connection settings (ADR 0035),
 * default model profiles and the CI entrypoints. Repository keys are unique; at most one is primary.
 */
data class Project(
    val id: ProjectId,
    val key: String,
    val name: String,
    val description: String = "",
    val repositories: List<GitRepository> = emptyList(),
    val issueTracker: IssueTrackerBinding = IssueTrackerBinding(),
    val constitution: String = "",
    val defaults: ProjectDefaults = ProjectDefaults(),
    val ciEntrypoints: List<CiEntrypoint> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: Actor? = null,
    /** ADR 0036: Spec Kit steps are available in this project (off by default for new projects). */
    val specKit: Boolean = false,
    /** ADR 0035: the system connection and the list of further connections of the project's Jira steps. */
    val jiraConnections: ProjectJiraConnections = ProjectJiraConnections(),
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("must match ${KEY_PATTERN.pattern}", "key")
        if (name.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "name")
        val duplicateRepo = repositories.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.firstOrNull()
        if (duplicateRepo != null) throw ValidationException("repository key $duplicateRepo is not unique", "repositories")
        if (repositories.count { it.primary } > 1) throw ValidationException("at most one repository can be primary", "repositories")
        entrypointRepositoryProblem()?.let { throw ValidationException(it, "ciEntrypoints") }
        val duplicate = ciEntrypoints.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.firstOrNull()
        if (duplicate != null) throw ValidationException("entrypoint key $duplicate is not unique", "ciEntrypoints")
    }

    /** Jira project key defaults to the project key when the binding does not name one. */
    val jiraProjectKey: String get() = issueTracker.jiraProjectKey?.takeIf { it.isNotBlank() } ?: key

    fun entrypoint(key: String): CiEntrypoint? = ciEntrypoints.firstOrNull { it.key == key }

    /** The repository of steps that name none; null when no repository is marked primary (or there is none). */
    val primaryRepository: GitRepository? get() = repositories.firstOrNull { it.primary }

    fun repository(key: String): GitRepository? = repositories.firstOrNull { it.key == key }

    /** `key: url` of every repository, for error messages; "none" for a project without repositories. */
    fun describeRepositories(): String =
        if (repositories.isEmpty()) "none" else repositories.joinToString(", ") { "${it.key}: ${it.url}" }

    private fun entrypointRepositoryProblem(): String? = ciEntrypoints.firstOrNull { e -> e.repository != null && repository(e.repository) == null }
        ?.let { "entrypoint ${it.key} is restricted to repository ${it.repository}, which is not a repository of the project (${describeRepositories()})" }

    fun update(
        name: String,
        description: String,
        repositories: List<GitRepository>,
        issueTracker: IssueTrackerBinding,
        constitution: String,
        defaults: ProjectDefaults,
        now: Instant,
        specKit: Boolean = this.specKit,
    ): Project = copy(
        specKit = specKit,
        name = name,
        description = description,
        repositories = repositories,
        // a restriction to a repository that was removed would lock CI out: it is dropped with the repository
        ciEntrypoints = ciEntrypoints.map { e -> if (e.repository != null && repositories.none { it.key == e.repository }) e.copy(repository = null) else e },
        issueTracker = issueTracker,
        constitution = constitution,
        defaults = defaults,
        updatedAt = now,
    )

    fun withEntrypoints(entrypoints: List<CiEntrypoint>, now: Instant): Project = copy(ciEntrypoints = entrypoints, updatedAt = now)

    fun withJiraConnections(settings: ProjectJiraConnections, now: Instant): Project = copy(jiraConnections = settings, updatedAt = now)

    companion object {
        val KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,15}$")
    }
}

/**
 * Aggregate root: a bearer token a project's CI uses to call Back Office. Only the SHA-256 hash is
 * stored; the plaintext (`bo_ci_` + 40 url-safe characters) is returned once on creation.
 */
data class ProjectCiToken(
    val id: ProjectCiTokenId,
    val projectId: ProjectId,
    val name: String,
    val tokenHash: String,
    /** First characters of the plaintext, for display in the token list. */
    val prefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdBy: Actor? = null,
) {
    init {
        if (name.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "name")
    }

    val revoked: Boolean get() = revokedAt != null

    fun used(now: Instant): ProjectCiToken = copy(lastUsedAt = now)

    fun revoke(now: Instant): ProjectCiToken = if (revoked) this else copy(revokedAt = now)

    companion object {
        const val PREFIX = "bo_ci_"
        const val RANDOM_LENGTH = 40
        const val DISPLAY_PREFIX_LENGTH = 12
    }
}
