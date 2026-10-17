package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.shared.runner.RunnerWorkspace

/**
 * ADR 0044: the run workspace and agent session of a runner step, from its config:
 * `workspace` (key of the run workspace; absent = a throwaway workspace), `sessionRole` (continue the coding agent's
 * session of that role in the workspace) and `prepareOnly` (clone and run `setupCommand`, no agent).
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
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun of(config: Map<String, Any?>, runnerStep: Boolean): Setting {
        val key = text(config[WORKSPACE])
        val role = text(config[SESSION_ROLE])
        val prepareOnly = config[PREPARE_ONLY]?.toString()?.lowercase() == "true"
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!runnerStep) {
            if (key != null || role != null || prepareOnly) warnings += "only coding steps (Implement, Spec Kit) use a workspace; remove workspace, sessionRole and prepareOnly"
            return Setting(null, null, false, emptyList(), warnings)
        }
        if (key != null && !RunnerWorkspace.KEY_PATTERN.matches(key)) errors += "workspace \"$key\": 1-20 lower case letters, digits or hyphens, starting with a letter"
        if (role != null && !RunnerWorkspace.KEY_PATTERN.matches(role)) errors += "sessionRole \"$role\": 1-20 lower case letters, digits or hyphens, starting with a letter"
        if (prepareOnly && key == null) errors += "a prepare step needs a workspace (config workspace), otherwise what it prepares is thrown away"
        if (role != null && key == null) warnings += "sessionRole \"$role\" is kept only in a workspace: set workspace, or the agent starts a new session"
        if (role != null && prepareOnly) warnings += "a prepare step starts no agent: sessionRole \"$role\" is ignored"
        return Setting(key, role.takeIf { key != null && !prepareOnly }, prepareOnly, errors, warnings)
    }

    private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
