package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

@JvmInline
value class LibraryWorkflowId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = LibraryWorkflowId(UUID.randomUUID())
        fun of(value: String) = LibraryWorkflowId(UUID.fromString(value))
    }
}

/** Where a library version was published from (kept when the project or workflow is deleted later). */
data class LibraryWorkflowSource(
    val projectId: ProjectId?,
    val projectKey: String?,
    val workflowId: WorkflowDefinitionId?,
    val workflowVersion: Int?,
)

/**
 * One immutable published version of a library workflow (ADR 0037): the graph without project-local settings,
 * the suggested triggers and the viewport.
 */
data class LibraryWorkflowVersion(
    val version: Int,
    val changeNote: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val triggers: List<Trigger>,
    val canvas: Canvas?,
    val publishedAt: Instant,
    val publishedBy: Actor?,
    val source: LibraryWorkflowSource,
) {
    /** Nodes that do work or route (notes excluded). */
    val nodeCount: Int get() = nodes.count { it.kind != NodeKind.NOTE }
}

/**
 * Aggregate root: a workflow shared across projects through the Library (ADR 0037). Versions are append-only;
 * projects adopt a version as a linked copy ([LibraryLink]) and runs record the version they ran.
 */
data class LibraryWorkflow(
    val id: LibraryWorkflowId,
    val key: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val versions: List<LibraryWorkflowVersion>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("must be a lower-case slug of 2-64 characters", "key")
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (versions.isEmpty()) throw ValidationException("must have a version", "versions")
    }

    val latest: LibraryWorkflowVersion get() = versions.maxBy { it.version }

    val latestVersion: Int get() = latest.version

    fun version(number: Int): LibraryWorkflowVersion =
        versions.firstOrNull { it.version == number } ?: throw ValidationException("library workflow $key has no version $number", "version")

    /** Appends the next version; the graph must differ from the latest one. */
    fun publish(version: LibraryWorkflowVersion, now: Instant, actor: Actor?): LibraryWorkflow {
        if (deprecated) throw InvalidStateException("library workflow $key is deprecated; restore it before publishing a new version")
        return copy(versions = versions + version.copy(version = latestVersion + 1), updatedAt = now, updatedBy = actor ?: updatedBy)
    }

    fun withDetails(name: String, description: String, tags: List<String>, deprecated: Boolean, now: Instant, actor: Actor?): LibraryWorkflow =
        copy(name = name, description = description, tags = normalizeTags(tags), deprecated = deprecated, updatedAt = now, updatedBy = actor ?: updatedBy)

    companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")
        const val MAX_CHANGE_NOTE = 2000
        const val MAX_TAGS = 20

        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)
    }
}

/**
 * The link of a project workflow to the library version it follows (ADR 0037). [modified] tells whether the graph
 * differs from that version, ignoring project-local settings and positions (`LibraryPortability.sameGraph`).
 */
data class LibraryLink(
    val libraryWorkflowId: LibraryWorkflowId,
    val key: String,
    val version: Int,
    val modified: Boolean = false,
)
