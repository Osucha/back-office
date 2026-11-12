package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import java.security.MessageDigest

/**
 * ADR 0060/0062: where a coding step works and which agent session it continues. ADR 0062 moved both to the agent:
 *
 * - `Agent.access.directory`: `RUN` (default) the run directory every RUN step of a run shares (the runner workspace
 *   [RunnerWorkspace.RUN_KEY]); `SEPARATE` a fresh directory with only the step's repositories, thrown away after the
 *   step (no session continuation).
 * - `Agent.session`: `PER_STEP` (default) the repeats of one step continue its conversation (session role `s-<node id>`);
 *   `PER_RUN` all steps of the agent in a run are one conversation (`a-<agent key>`); `FRESH` every execution starts anew.
 * - the step's `prepareOnly`: clone and run the setup in the run directory, no agent (the deprecated `prepare-workspace`).
 *
 * Compatibility: a legacy step `sessionRole` (ADR 0044) is the step's session role while the agent keeps the default
 * `PER_STEP`, so steps that shared a role keep sharing their session; the former step keys `directory`, `session` and
 * `continueSession` are dropped when the workflow is read (WorkflowJson).
 */
object RunDirectory {
    const val PREPARE_ONLY = "prepareOnly"

    /** ADR 0044: read compatibly, never offered for new steps. */
    const val LEGACY_SESSION_ROLE = "sessionRole"

    const val FIELD_PREPARE_ONLY = "task.config.prepareOnly"

    data class Setting(
        val directory: AgentDirectory = AgentDirectory.RUN,
        val session: AgentSession = AgentSession.PER_STEP,
        val prepareOnly: Boolean = false,
        /** ADR 0044 `sessionRole` of the step, honoured while the agent keeps [AgentSession.PER_STEP]. */
        val legacyRole: String? = null,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val valid: Boolean get() = errors.isEmpty()

        /** Whether the step works in the shared run directory. */
        val runDirectory: Boolean get() = directory == AgentDirectory.RUN
    }

    /** The setting of one step from its (effective) [config] and its [agent]; [runnerStep] = a coding agent step. */
    fun of(config: Map<String, Any?>, agent: AgentSnapshot, runnerStep: Boolean): Setting {
        val prepareOnly = flag(config[PREPARE_ONLY])
        if (!runnerStep) {
            val warnings = if (prepareOnly) listOf("only coding agent steps use $PREPARE_ONLY; a model answer has no directory") else emptyList()
            return Setting(warnings = warnings)
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val directory = agent.access.directory
        val role = text(config[LEGACY_SESSION_ROLE])
        if (directory == AgentDirectory.SEPARATE && prepareOnly) {
            errors += "a prepare step prepares the run directory, but agent ${agent.key} works in a SEPARATE directory: choose an agent with directory RUN"
        }
        if (role != null && !RunnerWorkspace.KEY_PATTERN.matches(role)) warnings += "sessionRole \"$role\" is not a valid session role and is ignored"
        return Setting(
            directory = directory,
            session = agent.session,
            prepareOnly = prepareOnly,
            legacyRole = role?.takeIf { RunnerWorkspace.KEY_PATTERN.matches(it) && agent.session == AgentSession.PER_STEP },
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * The session role step [nodeId] runs under with [setting] and agent [agentKey]: none for a prepare step, a
     * SEPARATE directory or a FRESH agent; the legacy `sessionRole`, else the step's own role ([ownRole]) for PER_STEP;
     * the agent's role in the run ([agentRole]) for PER_RUN.
     */
    fun sessionRole(nodeId: String, setting: Setting, agentKey: String): String? = when {
        setting.prepareOnly || !setting.runDirectory -> null
        setting.session == AgentSession.FRESH -> null
        setting.session == AgentSession.PER_RUN -> agentRole(agentKey)
        else -> setting.legacyRole ?: ownRole(nodeId)
    }

    /**
     * The own session role of step [nodeId]: `s-` and the node id, lower case, other characters as `-`; when that is
     * longer than [RunnerWorkspace.KEY_PATTERN] allows (20 characters) it is cut and a short hash of the node id keeps
     * it unique and deterministic.
     */
    fun ownRole(nodeId: String): String = role(STEP_PREFIX, nodeId)

    /** ADR 0062: the session role every step of agent [agentKey] shares in a run (`PER_RUN`): `a-` and the key, like [ownRole]. */
    fun agentRole(agentKey: String): String = role(AGENT_PREFIX, agentKey)

    private fun role(prefix: String, value: String): String {
        val cleaned = value.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-{2,}"), "-").trim('-')
        val candidate = "$prefix$cleaned"
        if (cleaned.isNotEmpty() && candidate.length <= MAX_ROLE && RunnerWorkspace.KEY_PATTERN.matches(candidate)) return candidate
        val hash = shortHash(value)
        val room = MAX_ROLE - prefix.length - hash.length - 1
        val head = cleaned.take(room).trimEnd('-')
        return if (head.isEmpty()) "$prefix$hash" else "$prefix$head-$hash"
    }

    /** ADR 0060: `allowQuestions` (default true) and `maxQuestionRounds` (default [DEFAULT_MAX_QUESTION_ROUNDS]) of an agent step. */
    data class Questions(val allowed: Boolean, val maxRounds: Int, val errors: List<String> = emptyList())

    const val ALLOW_QUESTIONS = "allowQuestions"
    const val MAX_QUESTION_ROUNDS = "maxQuestionRounds"
    const val DEFAULT_MAX_QUESTION_ROUNDS = 2
    const val MAX_QUESTION_ROUNDS_LIMIT = 10
    const val FIELD_QUESTIONS = "task.config.maxQuestionRounds"

    fun questions(config: Map<String, Any?>): Questions {
        val allowed = config[ALLOW_QUESTIONS]?.toString()?.lowercase() != "false"
        val raw = config[MAX_QUESTION_ROUNDS]
        val rounds = when (raw) {
            null -> DEFAULT_MAX_QUESTION_ROUNDS
            is Number -> raw.toInt()
            else -> raw.toString().trim().toIntOrNull()
        }
        if (rounds == null || rounds < 0 || rounds > MAX_QUESTION_ROUNDS_LIMIT) {
            return Questions(allowed, DEFAULT_MAX_QUESTION_ROUNDS, listOf("maxQuestionRounds \"$raw\": a whole number from 0 to $MAX_QUESTION_ROUNDS_LIMIT"))
        }
        return Questions(allowed, rounds)
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(HASH_BYTES).joinToString("") { "%02x".format(it) }

    private fun flag(value: Any?): Boolean = value?.toString()?.lowercase() == "true"

    private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private const val STEP_PREFIX = "s-"
    private const val AGENT_PREFIX = "a-"
    private const val MAX_ROLE = 20
    private const val HASH_BYTES = 3
}
