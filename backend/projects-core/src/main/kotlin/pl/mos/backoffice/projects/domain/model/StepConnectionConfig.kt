package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException

/**
 * Node configuration of a Jira step about its connection (ADR 0035):
 *
 * - `jiraConnection`: `SYSTEM` (also when missing or blank: the project's system connection, checked for the step's
 *   capabilities, else least privilege over the project's list), a connection id (`SPECIFIC`), or `ACTING_USER` (the
 *   personal connection of the person who approves the write, or who started the run);
 * - `fallbackToSystem` (`ACTING_USER` only, default false): when that person has no fitting personal connection, the
 *   system connection may be used instead (the approver chooses it in the confirmation; read steps fall back on their own);
 * - `allowActingUser` (`SYSTEM` / `SPECIFIC` write steps, default false): the approver may switch the write to their own
 *   personal connection.
 */
data class StepConnectionConfig(
    val mode: ConnectionMode = ConnectionMode.SYSTEM,
    val pinned: ConnectionId? = null,
    val fallbackToSystem: Boolean = false,
    val allowActingUser: Boolean = false,
) {
    /** The approver may write as themselves. */
    val personalAllowed: Boolean get() = mode == ConnectionMode.ACTING_USER || allowActingUser

    /** The system (or pinned) connection may write. */
    val systemAllowed: Boolean get() = mode != ConnectionMode.ACTING_USER || fallbackToSystem

    companion object {
        const val CONNECTION_KEY = "jiraConnection"
        const val FALLBACK_KEY = "fallbackToSystem"
        const val ALLOW_ACTING_USER_KEY = "allowActingUser"
        const val SYSTEM = "SYSTEM"
        const val ACTING_USER = "ACTING_USER"

        /** Parses the node configuration; an invalid id or flag is a [ValidationException] on its field. */
        fun of(config: Map<String, Any?>): StepConnectionConfig {
            val raw = config[CONNECTION_KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val fallback = flag(config, FALLBACK_KEY)
            val allow = flag(config, ALLOW_ACTING_USER_KEY)
            return when {
                raw == null || raw.equals(SYSTEM, ignoreCase = true) -> StepConnectionConfig(ConnectionMode.SYSTEM, null, fallback, allow)
                raw.equals(ACTING_USER, ignoreCase = true) -> StepConnectionConfig(ConnectionMode.ACTING_USER, null, fallback, allow)
                else -> {
                    val id = try {
                        ConnectionId.of(raw)
                    } catch (e: IllegalArgumentException) {
                        throw ValidationException("$raw is not a connection id, $SYSTEM or $ACTING_USER", "task.config.$CONNECTION_KEY")
                    }
                    StepConnectionConfig(ConnectionMode.SPECIFIC, id, fallback, allow)
                }
            }
        }

        private fun flag(config: Map<String, Any?>, key: String): Boolean {
            val value = config[key] ?: return false
            if (value is Boolean) return value
            return value.toString().trim().lowercase().toBooleanStrictOrNull()
                ?: throw ValidationException("must be true or false", "task.config.$key")
        }
    }
}

/** How a Jira step picks its connection (node configuration `jiraConnection`, ADR 0035). */
enum class ConnectionMode {
    /** The project's system connection (default), checked for the step's capabilities; least privilege over the project's list otherwise. */
    SYSTEM,

    /** One named connection (`jiraConnection` = its id); never a personal one. */
    SPECIFIC,

    /** The personal connection of the person who approves the write, or who started the run. */
    ACTING_USER,
}

/** Which identity writes to Jira after a confirmation (ADR 0035): the step's system connection or the approver's own. */
enum class WriteIdentity { SYSTEM, PERSONAL }
