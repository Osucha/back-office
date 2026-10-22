package pl.mos.backoffice.runnercontroller.git

import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import tools.jackson.databind.JsonNode
import java.net.URI

/**
 * Git rights of a job (ADR 0031), `spec.gitAccess` of the signed assignment. Back Office sends only the level;
 * the credential is chosen here, in the runner cluster.
 */
enum class GitAccess {
    NONE, READ, PUSH_MR;

    companion object {
        private val PUSHING_MODES = setOf("IMPLEMENT", "SPEC_KIT")

        /**
         * The level of [spec]: `gitAccess` when present, else derived as Back Office derives it (specs from a
         * Back Office before ADR 0031): no repository -> NONE, IMPLEMENT/SPEC_KIT with `push` -> PUSH_MR, else READ.
         * A job without a repository URL never gets a credential, whatever it asks for.
         */
        fun of(spec: JsonNode): GitAccess {
            val repository = spec.get("repository")?.takeIf { it.isObject }
            val url = repository?.get("url")?.takeIf { it.isString }?.asString()
            if (url.isNullOrBlank()) return NONE
            val declared = spec.get("gitAccess")?.takeIf { it.isString }?.asString()
            if (declared != null) {
                return entries.firstOrNull { it.name == declared.uppercase() }
                    ?: throw PolicyViolation("git credential: unknown gitAccess '$declared' (expected NONE, READ or PUSH_MR)")
            }
            val mode = spec.get("mode")?.takeIf { it.isString }?.asString()?.uppercase()
            val push = repository.get("push")?.takeIf { it.isBoolean }?.asBoolean() == true
            return if (mode in PUSHING_MODES && push) PUSH_MR else READ
        }
    }
}

/**
 * One `runner-controller.git.repo-secrets` entry (chart `gitCredentials.repoSecrets`): repositories matching
 * [repository] use the Secret [read] (key `token`, read-only token) for READ jobs and [push] (token that can push
 * a branch and open a merge request, never merge) for PUSH_MR jobs. For the Docker backend the values are names
 * of controller environment variables instead of Secrets.
 *
 * Patterns: `*` (every repository), `gitlab.example.com` (every repository on the host), `*.example.com`
 * (sub-domains), `gitlab.example.com/group/project` (one repository; `.git` and case are ignored) and
 * `gitlab.example.com/group/` followed by `*` (the star matches any characters, slashes included). The most specific match wins.
 */
data class RepoCredential(val repository: String = "", val read: String? = null, val push: String? = null)

/** The credential selected for a job: [credential] is a Secret name (Kubernetes) or an env var name (Docker). */
data class GitCredentialSelection(
    val access: GitAccess,
    val credential: String? = null,
    /** Level of the selected credential (PUSH_MR for a push token handed to a READ job by explicit opt-in). */
    val credentialLevel: GitAccess? = null,
    /** Operator-facing notes (logged per job): fallbacks, anonymous access. */
    val notes: List<String> = emptyList(),
) {
    companion object {
        val NONE = GitCredentialSelection(GitAccess.NONE)
    }
}

/**
 * Least-privilege git credential selection (ADR 0031). For each job the controller picks, by repository and
 * level: the `read` credential for READ jobs, the `push` credential only for PUSH_MR jobs, nothing for NONE.
 *
 * - [entries] (`git.repo-secrets`) are consulted first, most specific pattern first;
 * - [legacyPush] (`git.secrets`, chart `existingSecret` / `hostSecrets`, Docker `BO_GIT_TOKEN`) is the
 *   compatibility fallback: host (or `*`) -> credential treated as PUSH level, used only when no entry matches;
 * - a READ job whose only match is a push credential is refused unless [allowPushCredentialForRead];
 * - a PUSH_MR job whose match has no push credential is refused;
 * - no match at all = anonymous clone (public repositories), noted in the job log.
 */
class GitCredentialSelector(
    entries: List<RepoCredential> = emptyList(),
    legacyPush: Map<String, String> = emptyMap(),
    private val allowPushCredentialForRead: Boolean = false,
    /** Where the fallback comes from, for messages (`git.secrets` / `BO_GIT_TOKEN`). */
    private val legacyName: String = "gitCredentials.existingSecret / hostSecrets",
) {
    private val patterns: List<Pair<RepositoryPattern, RepoCredential>> =
        entries.filter { it.repository.isNotBlank() && (!it.read.isNullOrBlank() || !it.push.isNullOrBlank()) }
            .map { RepositoryPattern.parse(it.repository) to it }
            .sortedByDescending { it.first.specificity }
    private val legacy: List<Pair<RepositoryPattern, String>> =
        legacyPush.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .map { (host, ref) -> RepositoryPattern.parse(host.substringBefore('/')) to ref }
            .sortedByDescending { it.first.specificity }

    val isEmpty: Boolean get() = patterns.isEmpty() && legacy.isEmpty()

    /** Start-up warnings: fallbacks grant push to every job they cover. */
    fun warnings(): List<String> = buildList {
        if (legacy.isNotEmpty()) {
            add(
                "git credentials from $legacyName (${legacy.joinToString { "${it.first.text} -> ${it.second}" }}) are push-level and " +
                    "would be handed to every job they cover; READ jobs that only have such a credential are refused" +
                    (if (allowPushCredentialForRead) " - but allowPushCredentialForRead=true hands them the push token" else "") +
                    ". Configure gitCredentials.repoSecrets with separate read and push Secrets (ADR 0031)",
            )
        } else if (allowPushCredentialForRead) {
            add("allowPushCredentialForRead=true: READ jobs without a read credential receive the push credential (ADR 0031)")
        }
    }

    /** Selects the credential of a job; throws [PolicyViolation] (the job fails with that reason) when it must not run. */
    fun select(repositoryUrl: String?, access: GitAccess): GitCredentialSelection {
        if (access == GitAccess.NONE || repositoryUrl.isNullOrBlank()) return GitCredentialSelection.NONE
        val repo = RepositoryKey.of(repositoryUrl)
        val entry = patterns.firstOrNull { it.first.matches(repo) }
        if (entry != null) return fromEntry(repo, access, entry.first.text, entry.second)
        val fallback = legacy.firstOrNull { it.first.matches(repo) }
        if (fallback != null) return fromEntry(repo, access, "${fallback.first.text} ($legacyName)", RepoCredential(fallback.first.text, push = fallback.second))
        val anonymous = when (access) {
            GitAccess.PUSH_MR -> "no git credential configured for $repo: anonymous clone, the push will be rejected unless the remote accepts anonymous pushes"
            else -> "no git credential configured for $repo: anonymous clone"
        }
        return GitCredentialSelection(access, notes = listOf(anonymous))
    }

    /**
     * The credentials of a whole job: legacy specs select once (`spec.repository` + `spec.gitAccess`);
     * multi-repository specs select per repository by its own URL and `access`. A refusal names the repository:
     * `repository 'docs': git credential: READ job for ..., but ... has only a push credential`.
     */
    fun plan(spec: JsonNode, covered: Set<String> = emptySet()): JobGitPlan {
        val repos = JobRepositories.of(spec)
        if (!repos.multi) {
            val legacy = if ("" in covered) GitCredentialSelection(repos.access, notes = listOf(PROJECT_TOKEN)) else select(repos.repositories.firstOrNull()?.url, repos.access)
            return JobGitPlan(repos, legacy)
        }
        val selections = repos.repositories.map { repo ->
            // ADR 0051: a repository with a project token released by Back Office needs none of the pool's
            if (repo.key in covered) return@map repo to GitCredentialSelection(repo.access, notes = listOf(PROJECT_TOKEN))
            val selection = try {
                select(repo.url, repo.access)
            } catch (e: PolicyViolation) {
                throw PolicyViolation("repository '${repo.key}' (${RepositoryKey.of(repo.url)}): ${e.message}")
            }
            repo to selection
        }
        return JobGitPlan(repos, GitCredentialSelection.NONE, selections)
    }

    private fun fromEntry(repo: RepositoryKey, access: GitAccess, matched: String, entry: RepoCredential): GitCredentialSelection {
        val read = entry.read?.takeIf { it.isNotBlank() }
        val push = entry.push?.takeIf { it.isNotBlank() }
        return when (access) {
            GitAccess.READ -> when {
                read != null -> GitCredentialSelection(access, read, GitAccess.READ)
                push != null && allowPushCredentialForRead -> GitCredentialSelection(
                    access, push, GitAccess.PUSH_MR,
                    listOf("READ job for $repo gets the PUSH credential '$push' of $matched (allowPushCredentialForRead=true)"),
                )
                else -> throw PolicyViolation(
                    "git credential: READ job for $repo, but $matched has only a push credential; add a read-only Secret " +
                        "(gitCredentials.repoSecrets[].read) or set gitCredentials.allowPushCredentialForRead=true (ADR 0031)",
                )
            }
            GitAccess.PUSH_MR -> push?.let { GitCredentialSelection(access, it, GitAccess.PUSH_MR) }
                ?: throw PolicyViolation(
                    "git credential: PUSH_MR job for $repo, but $matched has no push credential (gitCredentials.repoSecrets[].push, ADR 0031)",
                )
            GitAccess.NONE -> GitCredentialSelection.NONE
        }
    }

    companion object {
        val EMPTY = GitCredentialSelector()
        const val PROJECT_TOKEN = "project token released sealed by Back Office (ADR 0051)"

        /** Kubernetes backends: Secrets of `git.repo-secrets`, `git.secrets` as push-level fallback. */
        fun forSecrets(git: ControllerProperties.Git) =
            GitCredentialSelector(git.repoSecrets, git.secrets, git.allowPushCredentialForRead, "git.secrets (gitCredentials.existingSecret / hostSecrets)")

        /**
         * Docker backend: `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` (names configurable) for every repository,
         * `BO_GIT_TOKEN` as push-level fallback. The selection holds the variable name; only set variables count.
         */
        fun forEnvironment(git: ControllerProperties.Git, env: (String) -> String?): GitCredentialSelector {
            fun set(name: String) = name.takeIf { it.isNotBlank() && !env(it).isNullOrBlank() }
            val read = set(git.tokenReadEnv)
            val push = set(git.tokenPushEnv)
            val entries = if (read != null || push != null) listOf(RepoCredential("*", read, push)) else emptyList()
            val legacy = set(git.tokenEnv)?.let { mapOf("*" to it) } ?: emptyMap()
            return GitCredentialSelector(entries, legacy, git.allowPushCredentialForRead, git.tokenEnv)
        }
    }
}

/** `host/path` of a repository URL: lower-case host without port and user info, path without `.git` and slashes. */
data class RepositoryKey(val host: String, val path: String) {
    override fun toString() = if (path.isEmpty()) host else "$host/$path"

    companion object {
        private val SCP_LIKE = Regex("^(?:[^@/]+@)?([^:/]+):(.+)$")

        fun of(url: String): RepositoryKey {
            val trimmed = url.trim()
            val uri = runCatching { URI.create(trimmed) }.getOrNull()
            val (host, path) = if (uri?.host != null) {
                uri.host to (uri.path ?: "")
            } else {
                SCP_LIKE.find(trimmed)?.let { it.groupValues[1] to it.groupValues[2] } ?: (trimmed to "")
            }
            return RepositoryKey(host.lowercase(), normalisePath(path))
        }

        fun normalisePath(path: String): String = path.trim().trim('/').removeSuffix(".git").trim('/').lowercase()
    }
}

/** A parsed [RepoCredential.repository] pattern. */
class RepositoryPattern private constructor(val text: String, private val host: String, private val path: Regex?, val specificity: Int) {
    fun matches(repo: RepositoryKey): Boolean = hostMatches(repo.host) && (path == null || path.matches(repo.path))

    private fun hostMatches(candidate: String): Boolean = when {
        host == "*" -> true
        host.startsWith("*.") -> candidate.endsWith(host.removePrefix("*"))
        else -> candidate == host
    }

    companion object {
        fun parse(pattern: String): RepositoryPattern {
            val text = pattern.trim()
            val withoutScheme = text.substringAfter("://")
            val hostPart = withoutScheme.substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
            val pathPart = withoutScheme.substringAfter('/', "").let(RepositoryKey::normalisePath).takeIf { it.isNotEmpty() }
            val path = pathPart?.let { p -> Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) }
            // path patterns beat hosts, exact hosts beat wildcards; among equals the longer literal text wins
            val rank = when {
                path != null -> 3_000
                hostPart == "*" -> 0
                hostPart.startsWith("*.") -> 1_000
                else -> 2_000
            }
            return RepositoryPattern(text, hostPart, path, rank + text.replace("*", "").length)
        }
    }
}
