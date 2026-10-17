package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

@JvmInline
value class LibrarySourceId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new() = LibrarySourceId(UUID.randomUUID())
    }
}

/** What a source is; the first kind is a git repository (ADR 0043), documents and files come later. */
enum class SourceKind { GIT }

/**
 * A source of the library (ADR 0043): something steps of any project can attach read-only, such as a repository of
 * company standards, templates or reference code. The project's own sources are its git repositories (ADR 0034).
 * In a job it is cloned into `/workspace/lib-<key>` ([jobKey]).
 */
data class LibrarySource(
    val id: LibrarySourceId,
    val key: String,
    val name: String,
    val description: String = "",
    val kind: SourceKind = SourceKind.GIT,
    val url: String,
    val defaultBranch: String = GitRepository.DEFAULT_BRANCH,
    val purpose: RepositoryPurpose = RepositoryPurpose.STANDARDS,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("must match ${KEY_PATTERN.pattern}", "key")
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (url.isBlank()) throw ValidationException("must not be blank", "url")
        if (defaultBranch.isBlank()) throw ValidationException("must not be blank", "defaultBranch")
    }

    /** Key of the repository in a job and its directory under `/workspace`; never clashes with a project key by prefix. */
    val jobKey: String get() = JOB_PREFIX + key

    /** The source as the read-only repository of a step. */
    fun asRepository(): GitRepository = GitRepository(jobKey, url.trim(), defaultBranch.trim(), purpose, description.ifBlank { name }, primary = false)

    companion object {
        /** 2-27 characters, so that `lib-<key>` is a valid repository key (at most 31). */
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{1,26}$")
        const val JOB_PREFIX = "lib-"
    }
}
