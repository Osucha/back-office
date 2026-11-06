package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.runner.RunnerWorkspace

/**
 * ADR 0057 section 4: a coding agent step continues the agent session of an earlier step with the config key
 * `continueSession: <node id>`. Both run in the same run workspace (the earlier step's `workspace`, else the implicit
 * workspace [RUN_WORKSPACE] that needs no definition) under the same session role (the earlier step's `sessionRole`,
 * else one derived from its node id). The earlier step gets that workspace and role too when it names none, so its
 * session is kept for the continuation; ADR 0044 does the rest (Claude Code `--resume`, OpenCode `--continue`).
 *
 * Works on the step config of the workflow's nodes (`overrides.config`): workspace and session role are step settings.
 */
object SessionContinuation {
    const val KEY = "continueSession"

    /** The implicit run workspace every project has (ADR 0057); valid without a workspace definition. */
    const val RUN_WORKSPACE = "run"

    const val FIELD = "task.config.continueSession"

    /** The node id [config] continues, or null. */
    fun target(config: Map<String, Any?>): String? = config[KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A session role from a node id that matches [RunnerWorkspace.KEY_PATTERN]: lower case, other characters as `-`,
     * a leading `s-` when it does not start with a letter, at most 20 characters.
     */
    fun derivedRole(nodeId: String): String {
        val cleaned = nodeId.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-{2,}"), "-").trim('-')
        val lettered = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "s-$cleaned"
        return lettered.take(MAX_ROLE).trimEnd('-').ifEmpty { "session" }
    }

    /**
     * The config of step [nodeId] ([config]: its effective config) with the workspace and session role that continuing
     * (or being continued) gives it; [config] unchanged when the step takes part in no continuation. [nodes] are the
     * workflow's nodes.
     */
    fun apply(nodeId: String, config: Map<String, Any?>, nodes: List<WorkflowNode>): Map<String, Any?> {
        val tasks = nodes.filterIsInstance<WorkflowNode.Task>().associateBy { it.id }
        val target = target(config)
        if (target != null && target != nodeId) {
            val session = sessionOf(target, tasks, mutableSetOf(nodeId)) ?: return config
            return config + (StepWorkspace.WORKSPACE to session.first) + (StepWorkspace.SESSION_ROLE to session.second)
        }
        val continued = tasks.values.any { it.id != nodeId && target(it.task.overrides.config) == nodeId }
        if (!continued) return config
        return config + (StepWorkspace.WORKSPACE to (text(config[StepWorkspace.WORKSPACE]) ?: RUN_WORKSPACE)) +
            (StepWorkspace.SESSION_ROLE to (text(config[StepWorkspace.SESSION_ROLE]) ?: derivedRole(nodeId)))
    }

    /** Workspace and role of the session step [id] works in: what it continues, else its own settings or the defaults. */
    private fun sessionOf(id: String, tasks: Map<String, WorkflowNode.Task>, seen: MutableSet<String>): Pair<String, String>? {
        val node = tasks[id] ?: return null
        if (!seen.add(id)) return null
        val config = node.task.overrides.config
        target(config)?.takeIf { it != id }?.let { earlier -> sessionOf(earlier, tasks, seen)?.let { return it } }
        return (text(config[StepWorkspace.WORKSPACE]) ?: RUN_WORKSPACE) to (text(config[StepWorkspace.SESSION_ROLE]) ?: derivedRole(id))
    }

    private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private const val MAX_ROLE = 20
}
