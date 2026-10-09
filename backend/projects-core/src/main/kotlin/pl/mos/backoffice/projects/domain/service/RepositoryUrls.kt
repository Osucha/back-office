package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.shared.ValidationException

/**
 * Matches the repository a CI job runs in to a project repository (ADR 0034). URLs are compared normalised:
 * scheme, credentials, a `.git` suffix and trailing slashes are ignored, the host is case-insensitive and the
 * scp-like form `git@host:group/project.git` equals `https://host/group/project`. A default port (22, 80, 443) is
 * dropped; other ports are kept.
 */
object RepositoryUrls {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val SCP_LIKE = Regex("^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$")
    private val DEFAULT_PORTS = setOf("22", "80", "443")

    /** `host[:port]/path` in lower-case host form without `.git`; null for blank input. */
    fun normalise(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val (hostPart, path) = if (SCHEME.containsMatchIn(raw)) {
            val rest = raw.replaceFirst(SCHEME, "")
            val slash = rest.indexOf('/')
            if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        } else {
            val scp = SCP_LIKE.matchEntire(raw) ?: return raw.lowercase().trimEnd('/').removeSuffix(".git")
            scp.groupValues[1] to scp.groupValues[2]
        }
        val host = hostPart.substringAfterLast('@').lowercase().let { h ->
            val port = h.substringAfter(':', "")
            if (port in DEFAULT_PORTS) h.substringBefore(':') else h
        }
        val cleanPath = path.trim('/').removeSuffix(".git").trimEnd('/')
        return if (cleanPath.isEmpty()) host else "$host/$cleanPath"
    }

    /** The repository of [project] whose URL equals [url] after normalisation, or null. */
    fun find(project: Project, url: String?): GitRepository? {
        val wanted = normalise(url) ?: return null
        return project.repositories.firstOrNull { normalise(it.url) == wanted }
    }

    /**
     * The repository a CI job of [context] runs in:
     * - `repositoryUrl` sent (backoffice-ci.sh 2.1+): must match a repository, otherwise [ValidationException] listing the
     *   project's repositories;
     * - only `projectPath` (older clients): the single repository whose path ends with it, else null;
     * - nothing: null.
     */
    fun match(project: Project, context: CiContext): GitRepository? {
        val url = context.repositoryUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            return find(project, url) ?: throw ValidationException(
                "repository ${redact(url)} is not a repository of project ${project.key}; the project's repositories: ${project.describeRepositories()} " +
                    "(add it under Projects > Repositories, manual: projects#repositories)",
                "context.repositoryUrl",
            )
        }
        val path = context.projectPath?.trim()?.trim('/')?.lowercase()?.removeSuffix(".git")?.takeIf { it.isNotEmpty() } ?: return null
        return project.repositories.filter { r -> normalise(r.url)?.let { it == path || it.endsWith("/$path") } == true }.singleOrNull()
    }

    /** The URL without credentials, for messages. */
    fun redact(url: String): String = url.replace(Regex("(://)[^/@]+@"), "$1")
}
