package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.VerificationStatus

enum class SelectionStatus {
    OK, OVER_PRIVILEGED, NONE, PINNED_NOT_FOUND, PINNED_INSUFFICIENT,

    /** ADR 0035: `ACTING_USER` but nobody acts (a run started by the intake watcher, CI or the system). */
    NO_ACTING_USER,

    /** ADR 0035: `ACTING_USER` and the acting person has no personal connection that fits. */
    NO_PERSONAL_CONNECTION,
}

/** Which connection a step uses, and why (ADR 0030, ADR 0035). */
data class ConnectionChoice(
    val status: SelectionStatus,
    val required: Set<ConnectionCapability>,
    val connection: Connection?,
    val pinned: Boolean,
    val excess: Set<ConnectionCapability>,
    val message: String,
    val mode: ConnectionMode = if (pinned) ConnectionMode.SPECIFIC else ConnectionMode.SYSTEM,
    /** The system connection did not fit and another connection of the project's list was taken. */
    val fallback: Boolean = false,
) {
    /** A connection was chosen (possibly with more rights than needed). */
    val usable: Boolean get() = connection != null

    /** The chosen connection is a personal one (audit: "via personal connection of X"). */
    val personal: Boolean get() = connection?.personal == true

    /** "Jira reader (platform)", "personal connection of Anna Nowak (Anna's Jira)". */
    val label: String get() = connection?.let(::describe) ?: "none"

    companion object {
        fun describe(c: Connection): String = when {
            c.personal -> "personal connection of ${c.ownerName ?: "its owner"} (\"${c.name}\")"
            c.legacy -> "\"${c.name}\" (platform, legacy environment credential)"
            else -> "\"${c.name}\" (${c.scope.name.lowercase()})"
        }
    }
}

/**
 * Least-privilege connection selection (ADR 0030, ADR 0035), pure functions. Personal connections
 * ([ConnectionScope.USER]) are never candidates of [select], [selectSystem] or a pin: they are chosen only by
 * [selectPersonal], which the caller invokes for the person who approved the write or started the run.
 *
 * - [select] (ADR 0030, used when a project has no system connection): a pinned connection must be a candidate and
 *   cover the step; otherwise every project and platform connection (the legacy one included) qualifies when its
 *   capabilities include the required ones and its allow-list allows the Jira project; the FEWEST extra capabilities
 *   win, ties prefer the project, then a successful verification, then non-legacy, then the name.
 * - [selectSystem] (ADR 0035): the project's system connection when it covers the step; otherwise least privilege
 *   (same order) among the project's connection list (system + additional connections); NONE when none fits.
 * - [selectPersonal]: least privilege among one person's personal connections.
 *
 * A choice with extra capabilities is allowed but reported as [SelectionStatus.OVER_PRIVILEGED].
 */
class ConnectionSelector {

    fun select(
        required: Set<ConnectionCapability>,
        projectConnections: List<Connection>,
        platformConnections: List<Connection>,
        jiraProjectKey: String?,
        pinned: ConnectionId? = null,
    ): ConnectionChoice {
        val project = projectConnections.filterNot { it.personal }
        val platform = platformConnections.filterNot { it.personal }
        val needs = needs(required)
        if (pinned != null) return pinnedChoice(required, project + platform, jiraProjectKey, pinned)
        val projectIds = project.map { it.id }.toSet()
        val best = (project + platform.filterNot { it.id in projectIds })
            .filter { fits(it, required, jiraProjectKey) }
            .minWithOrNull(preference(required, projectIds))
            ?: return ConnectionChoice(
                SelectionStatus.NONE, required, null, false, emptySet(),
                "no Jira connection with capabilities $needs" + (jiraProjectKey?.let { " for Jira project $it" } ?: "") +
                    "; add one under Project → Connections or Administration → Connections (manual: $GUIDE)",
            )
        return chosen(best, required, pinned = false)
    }

    /**
     * ADR 0035 `SYSTEM` mode. Without a configured system connection (projects that never chose one) this is [select]
     * over every project and platform connection, exactly as before.
     */
    fun selectSystem(
        required: Set<ConnectionCapability>,
        settings: ProjectJiraConnections,
        projectConnections: List<Connection>,
        platformConnections: List<Connection>,
        jiraProjectKey: String?,
    ): ConnectionChoice {
        val systemId = settings.systemConnectionId ?: return select(required, projectConnections, platformConnections, jiraProjectKey)
        val pool = (projectConnections + platformConnections).filterNot { it.personal }.associateBy { it.id }
        val system = pool[systemId]
        if (system != null && fits(system, required, jiraProjectKey)) return chosen(system, required, pinned = false)
        val list = settings.list().mapNotNull { pool[it] }
        val projectIds = projectConnections.map { it.id }.toSet()
        val why = when {
            system == null -> "the project's system connection no longer exists"
            !system.covers(required) -> "the system connection \"${system.name}\" lacks ${(required - system.capabilities).sorted().joinToString()}"
            else -> "the system connection \"${system.name}\" is limited to the Jira projects ${system.allowedProjectKeys} and may not be used for $jiraProjectKey"
        }
        val best = list.filter { fits(it, required, jiraProjectKey) }.minWithOrNull(preference(required, projectIds))
            ?: return ConnectionChoice(
                SelectionStatus.NONE, required, null, false, emptySet(),
                "$why, and no connection of the project's list has capabilities ${needs(required)}" +
                    (jiraProjectKey?.let { " for Jira project $it" } ?: "") +
                    "; add one to the list under Project → Connections (manual: $GUIDE)",
            )
        val choice = chosen(best, required, pinned = false)
        return choice.copy(fallback = true, message = "$why; ${choice.message} from the project's connection list")
    }

    /** ADR 0030 `SPECIFIC` mode: the pinned connection must be a project or platform connection that covers the step. */
    fun selectPinned(
        required: Set<ConnectionCapability>,
        projectConnections: List<Connection>,
        platformConnections: List<Connection>,
        jiraProjectKey: String?,
        pinned: ConnectionId,
    ): ConnectionChoice = pinnedChoice(required, (projectConnections + platformConnections).filterNot { it.personal }, jiraProjectKey, pinned)

    /**
     * ADR 0035 `ACTING_USER`: least privilege among the personal connections of [ownerName]. Only call it for the person
     * who approved the write or started the run: that action is their consent to act as them in Jira.
     */
    fun selectPersonal(required: Set<ConnectionCapability>, personal: List<Connection>, ownerName: String, jiraProjectKey: String?): ConnectionChoice {
        val best = personal.filter { it.personal && fits(it, required, jiraProjectKey) }.minWithOrNull(preference(required, emptySet()))
            ?: return ConnectionChoice(
                SelectionStatus.NO_PERSONAL_CONNECTION, required, null, false, emptySet(),
                "$ownerName has no personal Jira connection with capabilities ${needs(required)}" +
                    (jiraProjectKey?.let { " for Jira project $it" } ?: "") + " (My account → Connections, manual: $PERSONAL_GUIDE)",
                mode = ConnectionMode.ACTING_USER,
            )
        return chosen(best, required, pinned = false).copy(mode = ConnectionMode.ACTING_USER)
    }

    /** Candidates for display: project connections first, then platform ones, each by name (legacy last). */
    fun order(projectConnections: List<Connection>, platformConnections: List<Connection>): List<Connection> {
        val byName = compareBy<Connection>({ it.legacy }, { it.name.lowercase() }, { it.id.toString() })
        return projectConnections.filterNot { it.personal }.sortedWith(byName) + platformConnections.filterNot { it.personal }.sortedWith(byName)
    }

    private fun pinnedChoice(required: Set<ConnectionCapability>, candidates: List<Connection>, jiraProjectKey: String?, pinned: ConnectionId): ConnectionChoice {
        val needs = needs(required)
        val connection = candidates.firstOrNull { it.id == pinned }
            ?: return ConnectionChoice(
                SelectionStatus.PINNED_NOT_FOUND, required, null, true, emptySet(),
                "the pinned Jira connection $pinned does not exist or is not available to this project (personal connections cannot be pinned: " +
                    "use \"Person who approves or starts\"); pick another one or use the system connection",
            )
        if (!connection.covers(required)) {
            val missing = (required - connection.capabilities).sorted().joinToString()
            return ConnectionChoice(
                SelectionStatus.PINNED_INSUFFICIENT, required, null, true, emptySet(),
                "the pinned Jira connection \"${connection.name}\" lacks the capabilities $missing needed by this step (needs $needs)",
            )
        }
        if (!connection.allowsProject(jiraProjectKey)) {
            return ConnectionChoice(
                SelectionStatus.PINNED_INSUFFICIENT, required, null, true, emptySet(),
                "the pinned Jira connection \"${connection.name}\" is limited to the Jira projects ${connection.allowedProjectKeys} and may not be used for $jiraProjectKey",
            )
        }
        return chosen(connection, required, pinned = true)
    }

    private fun chosen(connection: Connection, required: Set<ConnectionCapability>, pinned: Boolean): ConnectionChoice {
        val excess = connection.excess(required)
        val label = ConnectionChoice.describe(connection)
        return if (excess.isEmpty()) {
            ConnectionChoice(SelectionStatus.OK, required, connection, pinned, excess, "uses the Jira connection $label")
        } else {
            ConnectionChoice(
                SelectionStatus.OVER_PRIVILEGED, required, connection, pinned, excess,
                "uses a connection with more rights than needed ($label also has ${excess.sorted().joinToString()}); " +
                    "create a connection with only ${required.sorted().joinToString()} (manual: $LEAST_PRIVILEGE_GUIDE)",
            )
        }
    }

    companion object {
        const val GUIDE = "integrations-jira#jira-connections"
        const val LEAST_PRIVILEGE_GUIDE = "integrations-jira#jira-least-privilege"
        const val PERSONAL_GUIDE = "integrations-jira#jira-personal-connections"

        private fun needs(required: Set<ConnectionCapability>) = required.sorted().joinToString(prefix = "[", postfix = "]")

        fun fits(c: Connection, required: Set<ConnectionCapability>, jiraProjectKey: String?): Boolean = c.covers(required) && c.allowsProject(jiraProjectKey)

        private fun verificationRank(connection: Connection): Int = when (connection.verification?.status) {
            VerificationStatus.OK -> 0
            VerificationStatus.WARNING -> 1
            null -> 2
            VerificationStatus.FAILED -> 3
        }

        /** Fewest extra capabilities, then project before platform, then verification, then non-legacy, then name and id (deterministic). */
        fun preference(required: Set<ConnectionCapability>, projectIds: Set<ConnectionId>): Comparator<Connection> =
            compareBy<Connection>(
                { it.excess(required).size }, { if (it.id in projectIds || it.scope == ConnectionScope.PROJECT) 0 else 1 }, ::verificationRank,
                { it.legacy }, { it.name.lowercase() }, { it.id.toString() },
            )
    }
}
