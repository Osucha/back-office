package pl.mos.backoffice.runnercontroller.git

import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import tools.jackson.databind.JsonNode

/** One repository of a job: an entry of `spec.repositories`, or the legacy single `spec.repository`. */
data class JobRepository(val key: String, val url: String, val access: GitAccess, val primary: Boolean = false)

/**
 * The repositories of a signed job spec. [multi] = the spec uses `repositories` (multi-repository contract):
 * every entry has its own `access` and gets its own credential, delivered as `<git dir>/<key>/token`. Otherwise
 * the legacy single `repository` with `gitAccess` (older control planes), delivered as `<git dir>/token`.
 *
 * Same rule as the runner (`is_multi_repo_spec`): `repositories` present and non-empty, or present (even empty)
 * without a legacy `repository.url`.
 */
data class JobRepositories(val multi: Boolean, val repositories: List<JobRepository>) {
    val urls: List<String> get() = repositories.map { it.url }

    /** The highest level of any repository (labels, annotations, logs). */
    val access: GitAccess
        get() = listOf(GitAccess.PUSH_MR, GitAccess.READ).firstOrNull { level -> repositories.any { it.access == level } } ?: GitAccess.NONE

    companion object {
        /** Repository keys name a directory (`<git dir>/<key>/token`, `/workspace/<path>`): no slashes, no `..`. */
        val KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$")

        fun of(spec: JsonNode): JobRepositories {
            val list = spec.get("repositories")?.takeIf { it.isArray }
            val legacyUrl = spec.get("repository")?.takeIf { it.isObject }?.get("url")?.takeIf { it.isString }?.asString()
            if (list == null || (list.size() == 0 && !legacyUrl.isNullOrBlank())) {
                val access = GitAccess.of(spec)
                val repos = if (legacyUrl.isNullOrBlank()) emptyList() else listOf(JobRepository("", legacyUrl, access, primary = true))
                return JobRepositories(false, repos)
            }
            val repos = list.mapIndexed { i, entry -> entry(i, entry) }
            val duplicates = repos.groupBy { it.key }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) throw PolicyViolation("repositories: duplicate key(s) ${duplicates.sorted().joinToString()}")
            return JobRepositories(true, repos)
        }

        private fun entry(index: Int, node: JsonNode): JobRepository {
            if (!node.isObject) throw PolicyViolation("repositories[$index] is not an object")
            val key = node.get("key")?.takeIf { it.isString }?.asString()?.trim().orEmpty()
            if (!KEY.matches(key)) throw PolicyViolation("repositories[$index]: key '$key' must match ${KEY.pattern}")
            val url = node.get("url")?.takeIf { it.isString }?.asString()?.trim()
            if (url.isNullOrBlank()) throw PolicyViolation("repository '$key': url is required")
            val declared = node.get("access")?.takeIf { it.isString }?.asString()
            // absent = READ, the safe default (the runner applies the same rule)
            val access = if (declared.isNullOrBlank()) GitAccess.READ else GitAccess.entries.firstOrNull { it.name == declared.uppercase() }
                ?: throw PolicyViolation("repository '$key': unknown access '$declared' (expected NONE, READ or PUSH_MR)")
            return JobRepository(key, url, access, node.get("primary")?.takeIf { it.isBoolean }?.asBoolean() == true)
        }
    }
}

/** A credential delivered for one repository of a multi-repository job: `<git dir>/<key>/token`. */
data class RepositoryToken(val key: String, val access: GitAccess, val credential: String)

/** The git credentials of one job, selected per repository and level (ADR 0031). */
data class JobGitPlan(
    val repositories: JobRepositories,
    /** Legacy single-repository specs: the one selection (`<git dir>/token`); NONE for multi-repository specs. */
    val legacy: GitCredentialSelection,
    /** Multi-repository specs: one selection per repository, in spec order. */
    val selections: List<Pair<JobRepository, GitCredentialSelection>> = emptyList(),
) {
    val access: GitAccess get() = if (repositories.multi) repositories.access else legacy.access

    /** Only the repositories that get a credential (NONE and anonymous ones get no file at all). */
    val tokens: List<RepositoryToken>
        get() = selections.mapNotNull { (repo, s) -> s.credential?.let { RepositoryToken(repo.key, s.access, it) } }

    val notes: List<String>
        get() = legacy.notes + selections.flatMap { (repo, s) -> s.notes.map { "repository '${repo.key}': $it" } }

    /** For the job log: `docs=READ(git-docs-read), src=PUSH_MR(git-src-push)`. */
    fun describe(): String = if (repositories.multi) {
        selections.joinToString { (repo, s) -> "${repo.key}=${s.access}(${s.credential ?: "none"})" }.ifEmpty { "no repositories" }
    } else {
        "${legacy.access} (credential ${legacy.credential ?: "none"})"
    }
}
