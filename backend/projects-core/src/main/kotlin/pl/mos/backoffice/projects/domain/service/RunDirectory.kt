package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import java.security.MessageDigest

/**
 * ADR 0060: where a coding step works and which agent session it continues, from its config.
 *
 * - `directory`: `RUN` (default) the run directory every RUN step of a run shares (the runner workspace
 *   [RunnerWorkspace.RUN_KEY]); `SEPARATE` a fresh directory with only the step's repositories, thrown away after the
 *   step (no session continuation).
 * - `session`: `OWN` (default) repeated executions of the step continue its own session; `NEW` every execution starts
 *   fresh; `CONTINUE` with `continueSession: <node id>` shares the session of that earlier step (same agent, checked by
 *   the workflow validation). A `SEPARATE` step always starts a new session.
 * - `prepareOnly`: clone and run the setup in the run directory, no agent (the deprecated `prepare-workspace`).
 *
 * Compatibility: a `continueSession` without `session` reads as `CONTINUE` (ADR 0057); a legacy `sessionRole` (ADR
 * 0044) without `session` is the step's own session role, so steps that shared a role keep sharing their session (the
 * agent check of ADR 0060 would need the library at read time; sharing by role is what they did before). The legacy
 * `workspace` key is dropped when the workflow is read (WorkflowJson).
 */
object RunDirectory {
    const val DIRECTORY = "directory"
    const val SESSION = "session"
    const val CONTINUE_SESSION = "continueSession"
    const val PREPARE_ONLY = "prepareOnly"

    /** ADR 0044: read compatibly, never offered for new steps. */
    const val LEGACY_SESSION_ROLE = "sessionRole"

    const val FIELD_DIRECTORY = "task.config.directory"
    const val FIELD_SESSION = "task.config.session"
    const val FIELD_CONTINUE = "task.config.continueSession"

    enum class Directory { RUN, SEPARATE }

    enum class Session { OWN, NEW, CONTINUE }

    data class Setting(
        val directory: Directory = Directory.RUN,
        val session: Session = Session.OWN,
        /** The node whose session a [Session.CONTINUE] step shares. */
        val continueFrom: String? = null,
        val prepareOnly: Boolean = false,
        /** ADR 0044 `sessionRole` of a step without `session`: its own session role. */
        val legacyRole: String? = null,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val valid: Boolean get() = errors.isEmpty()

        /** Whether the step works in the shared run directory. */
        val runDirectory: Boolean get() = directory == Directory.RUN
    }

    /** The setting of one step from its (effective) [config]; [runnerStep] = a coding agent step. */
    fun of(config: Map<String, Any?>, runnerStep: Boolean): Setting {
        val directoryText = text(config[DIRECTORY])
        val sessionText = text(config[SESSION])
        val target = text(config[CONTINUE_SESSION])
        val prepareOnly = flag(config[PREPARE_ONLY])
        val legacyRole = text(config[LEGACY_SESSION_ROLE])
        if (!runnerStep) {
            val set = listOfNotNull(directoryText?.let { DIRECTORY }, sessionText?.let { SESSION }, target?.let { CONTINUE_SESSION }, PREPARE_ONLY.takeIf { prepareOnly })
            val warnings = if (set.isEmpty()) emptyList() else listOf("only coding agent steps use ${set.joinToString()}; a model answer has no directory and no session")
            return Setting(warnings = warnings)
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val directory = directoryText?.let { value ->
            Directory.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: run { errors += "directory \"$value\": RUN or SEPARATE"; null }
        } ?: Directory.RUN
        val explicit = sessionText?.let { value ->
            Session.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: run { errors += "session \"$value\": OWN, NEW or CONTINUE"; null }
        }
        var session = explicit ?: if (target != null) Session.CONTINUE else Session.OWN
        if (session == Session.CONTINUE && target == null) errors += "session CONTINUE needs continueSession: the node id of the step whose session it continues"
        if (target != null && explicit != null && explicit != Session.CONTINUE) warnings += "continueSession is ignored: session is $explicit"
        val role = legacyRole?.takeIf { sessionText == null && target == null }
        if (role != null && !RunnerWorkspace.KEY_PATTERN.matches(role)) warnings += "sessionRole \"$role\" is not a valid session role and is ignored"
        if (directory == Directory.SEPARATE) {
            if (prepareOnly) errors += "a prepare step prepares the run directory; it cannot work in a SEPARATE directory"
            if (explicit != null && explicit != Session.NEW || target != null) {
                warnings += "a SEPARATE step always starts a new session (its directory is thrown away); session ${explicit ?: Session.CONTINUE} is ignored"
            }
            session = Session.NEW
        }
        if (prepareOnly && (explicit != null || target != null)) warnings += "a prepare step starts no agent: its session settings are ignored"
        return Setting(
            directory = directory,
            session = session,
            continueFrom = target.takeIf { session == Session.CONTINUE },
            prepareOnly = prepareOnly,
            legacyRole = role?.takeIf { RunnerWorkspace.KEY_PATTERN.matches(it) },
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * The session role step [nodeId] runs under ([config]: its effective config; [nodes]: the workflow's nodes, whose
     * step configs give the roles of the steps it continues): OWN its own role ([ownRole], or its legacy `sessionRole`),
     * CONTINUE the role of the step it continues, NEW / SEPARATE / prepare steps none. Null also when the continued step
     * has no session to share (the validation reports that).
     */
    fun sessionRole(nodeId: String, config: Map<String, Any?>, nodes: List<WorkflowNode>): String? {
        val tasks = nodes.filterIsInstance<WorkflowNode.Task>().associateBy { it.id }
        return roleOf(nodeId, of(config, runnerStep = true), tasks, mutableSetOf())
    }

    private fun roleOf(nodeId: String, setting: Setting, tasks: Map<String, WorkflowNode.Task>, seen: MutableSet<String>): String? {
        if (!seen.add(nodeId) || setting.prepareOnly || !setting.runDirectory) return null
        return when (setting.session) {
            Session.NEW -> null
            Session.OWN -> setting.legacyRole ?: ownRole(nodeId)
            Session.CONTINUE -> {
                val target = setting.continueFrom?.takeIf { it != nodeId } ?: return null
                val node = tasks[target] ?: return null
                roleOf(target, of(node.task.overrides.config, runnerStep = true), tasks, seen)
            }
        }
    }

    /**
     * The own session role of step [nodeId]: `s-` and the node id, lower case, other characters as `-`; when that is
     * longer than [RunnerWorkspace.KEY_PATTERN] allows (20 characters) it is cut and a short hash of the node id keeps
     * it unique and deterministic.
     */
    fun ownRole(nodeId: String): String {
        val cleaned = nodeId.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-{2,}"), "-").trim('-')
        val candidate = "$ROLE_PREFIX$cleaned"
        if (cleaned.isNotEmpty() && candidate.length <= MAX_ROLE && RunnerWorkspace.KEY_PATTERN.matches(candidate)) return candidate
        val hash = shortHash(nodeId)
        val room = MAX_ROLE - ROLE_PREFIX.length - hash.length - 1
        val head = cleaned.take(room).trimEnd('-')
        return if (head.isEmpty()) "$ROLE_PREFIX$hash" else "$ROLE_PREFIX$head-$hash"
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

    private const val ROLE_PREFIX = "s-"
    private const val MAX_ROLE = 20
    private const val HASH_BYTES = 3
}
