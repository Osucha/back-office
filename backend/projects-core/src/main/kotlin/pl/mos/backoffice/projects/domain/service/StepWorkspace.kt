package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.RunnerWorkspace

/**
 * ADR 0044: the run workspace and agent session of a runner step, from its config:
 * `workspace` (key of a workspace of the project; absent = a throwaway workspace), `sessionRole` (continue
 * the coding agent's session of that role in the workspace) and `prepareOnly` (clone and run setup, no agent).
 */
object StepWorkspace {
    const val WORKSPACE = "workspace"
    const val SESSION_ROLE = "sessionRole"
    const val PREPARE_ONLY = "prepareOnly"
    const val FIELD = "task.config.workspace"

    data class Setting(
        val key: String?,
        val sessionRole: String?,
        val prepareOnly: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        /** The project's workspace of that key; null without a workspace or when the project has none of that key. */
        val definition: ProjectWorkspace? = null,
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    /** [workspaces] = the workspaces of the project; a step may only name one of them. */
    fun of(config: Map<String, Any?>, runnerStep: Boolean, workspaces: List<ProjectWorkspace> = emptyList()): Setting {
        val key = text(config[WORKSPACE])
        val role = text(config[SESSION_ROLE])
        val prepareOnly = config[PREPARE_ONLY]?.toString()?.lowercase() == "true"
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!runnerStep) {
            if (key != null || role != null || prepareOnly) warnings += "only coding steps (Implement, Spec Kit) use a workspace; remove workspace, sessionRole and prepareOnly"
            return Setting(null, null, false, emptyList(), warnings)
        }
        val definition = key?.let { k -> workspaces.firstOrNull { it.key == k } }
        if (key != null && !RunnerWorkspace.KEY_PATTERN.matches(key)) {
            errors += "workspace \"$key\": 1-20 lower case letters, digits or hyphens, starting with a letter"
        } else if (key != null && definition == null) {
            errors += "workspace \"$key\" is not defined in this project; add it in the project tab Workspaces"
        }
        if (role != null && !RunnerWorkspace.KEY_PATTERN.matches(role)) errors += "sessionRole \"$role\": 1-20 lower case letters, digits or hyphens, starting with a letter"
        if (role != null && definition != null && definition.roles.isNotEmpty() && role !in definition.roles) {
            errors += "sessionRole \"$role\" is not a role of workspace \"$key\" (${definition.roles.joinToString()})"
        }
        if (prepareOnly && key == null) errors += "a prepare step needs a workspace (config workspace), otherwise what it prepares is thrown away"
        if (role != null && key == null) warnings += "sessionRole \"$role\" is kept only in a workspace: set workspace, or the agent starts a new session"
        if (role != null && prepareOnly) warnings += "a prepare step starts no agent: sessionRole \"$role\" is ignored"
        return Setting(key, role.takeIf { key != null && !prepareOnly }, prepareOnly, errors, warnings, definition)
    }

    /**
     * The step config the repository plan uses: a step of a workspace that names no repositories of its own works on
     * the workspace's sources. The project's primary repository among them (else the first project repository) gets the
     * access the step's push setting gives it; every other source is READ. A step's own `repositories` win.
     */
    fun effectiveConfig(config: Map<String, Any?>, workspace: ProjectWorkspace?, project: Project, pushes: Boolean): Map<String, Any?> {
        if (workspace == null || workspace.sources.isEmpty() || config[StepRepositories.CONFIG_KEY] is List<*>) return config
        val own = workspace.sources.filterNot { it.library }
        val primary = own.firstOrNull { s -> project.repositories.any { it.key == s.key && it.primary } }?.key ?: own.firstOrNull()?.key
        val entries = workspace.sources.map { s ->
            val access = if (!s.library && s.key == primary && pushes) GitAccess.PUSH_MR else GitAccess.READ
            buildMap {
                put("key", s.key)
                put("access", access.name)
                if (s.library) put("library", true)
            }
        }
        return config + (StepRepositories.CONFIG_KEY to entries)
    }

    private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
