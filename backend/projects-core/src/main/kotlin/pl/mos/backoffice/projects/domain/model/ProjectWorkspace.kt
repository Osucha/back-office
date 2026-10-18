package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

@JvmInline
value class ProjectWorkspaceId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new() = ProjectWorkspaceId(UUID.randomUUID())
    }
}

/** ADR 0044: a source cloned into a workspace: a project repository or ([library]) a library source, by key. */
data class WorkspaceSource(val key: String, val library: Boolean = false)

/**
 * ADR 0044: a run workspace of a project, used by any of its workflows. Coding steps pick it by [key]
 * (`config.workspace`) and share `/workspace` within a run. [sources] are cloned there for every step of the workspace
 * that names no repositories of its own; [setupCommand] runs once per workspace before the first step's own setup;
 * [roles] are the agent session roles its steps may continue (`config.sessionRole`), any role when empty.
 */
data class ProjectWorkspace(
    val id: ProjectWorkspaceId,
    val projectId: ProjectId,
    val key: String,
    val name: String = key,
    val description: String = "",
    val sources: List<WorkspaceSource> = emptyList(),
    val setupCommand: String? = null,
    val roles: List<String> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
) {
    init {
        if (!KEY_PATTERN.matches(key)) throw ValidationException("1-20 lower case letters, digits or hyphens, starting with a letter", "key")
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        roles.forEach { if (!KEY_PATTERN.matches(it)) throw ValidationException("role \"$it\": 1-20 lower case letters, digits or hyphens", "roles") }
        if (roles.toSet().size != roles.size) throw ValidationException("roles must be unique", "roles")
        if (sources.map { it.key to it.library }.toSet().size != sources.size) throw ValidationException("a source is listed twice", "sources")
    }

    companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{0,19}$")
    }
}
