package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IssueTracker
import pl.mos.backoffice.projects.application.port.out.IssueTrackerFactory
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.IssueSnapshot
import pl.mos.backoffice.projects.domain.model.IssueHeadline
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.shared.security.PrincipalKind
import java.util.UUID
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.SelectionStatus
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.tasks.TaskExecutor

/** A client of one connection, narrowed to a step ([ScopedIssueTracker]), with that connection. */
data class OpenedTracker(val connection: Connection, val client: IssueTracker)

/** The person on whose behalf a step acts (ADR 0035): who approved the write, or who started the run. */
data class ActingUser(val userId: UUID, val name: String, val via: Via) {
    enum class Via { APPROVAL, RUN_START }

    companion object {
        /** The person who started [run], or null when the run was started by CI, the intake watcher or the system. */
        fun startedBy(run: WorkflowRun): ActingUser? {
            val actor = run.startedBy ?: return null
            val id = actor.userId ?: return null
            return if (actor.kind == PrincipalKind.USER || actor.kind == PrincipalKind.PERSONAL_TOKEN) ActingUser(id, actor.displayName, Via.RUN_START) else null
        }
    }
}

/**
 * Picks the connection for a Jira step at run time and hands out a client limited to what the step needs
 * (ADR 0030, ADR 0035). Runs on the engine's threads (system context); it is not a use case.
 *
 * Personal connections are only ever chosen for an [ActingUser]: the approver of a write (their approval) or the
 * person who started the run (their start). The intake watcher ([openReader]) and steps of runs nobody started never
 * reach them.
 */
class JiraConnectionAccess(
    private val connections: ConnectionRepository,
    private val legacy: LegacyConnectionSource,
    private val selector: ConnectionSelector,
    private val trackers: IssueTrackerFactory,
) {

    private fun platform(): List<Connection> = connections.findPlatform() + listOfNotNull(legacy.legacy())

    /** ADR 0030 compatible: [pinned] null = the project's system connection (ADR 0035), else that connection. */
    fun choose(project: Project, required: Set<ConnectionCapability>, pinned: ConnectionId?, jiraProjectKey: String? = null): ConnectionChoice =
        chooseShared(project, required, if (pinned == null) StepConnectionConfig() else StepConnectionConfig(ConnectionMode.SPECIFIC, pinned), jiraProjectKey)

    /** The system or pinned connection of [step] (never a personal one). */
    fun chooseShared(project: Project, required: Set<ConnectionCapability>, step: StepConnectionConfig, jiraProjectKey: String? = null): ConnectionChoice {
        val key = jiraProjectKey ?: project.jiraProjectKey
        val pinned = step.pinned
        return if (step.mode == ConnectionMode.SPECIFIC && pinned != null) {
            selector.selectPinned(required, connections.findByProject(project.id), platform(), key, pinned)
        } else {
            selector.selectSystem(required, project.jiraConnections, connections.findByProject(project.id), platform(), key)
        }
    }

    /** The personal connection of [user] for [required] (only for the approver or the person who started the run). */
    fun choosePersonal(user: ActingUser, required: Set<ConnectionCapability>, jiraProjectKey: String?): ConnectionChoice =
        selector.selectPersonal(required, connections.findByOwner(user.userId), user.name, jiraProjectKey)

    /**
     * The connection of a step for [identity] (null = the step's default: personal for `ACTING_USER`, else system):
     *
     * - personal: [acting] must be set and own a fitting personal connection; otherwise, for an `ACTING_USER` step with
     *   `fallbackToSystem` and no explicit [identity], the system connection is used (with a note); else the choice has
     *   no connection ([SelectionStatus.NO_ACTING_USER] / [SelectionStatus.NO_PERSONAL_CONNECTION]);
     * - system: refused for an `ACTING_USER` step without `fallbackToSystem`.
     */
    fun resolve(
        project: Project,
        required: Set<ConnectionCapability>,
        step: StepConnectionConfig,
        jiraProjectKey: String?,
        acting: ActingUser?,
        identity: WriteIdentity? = null,
    ): ConnectionChoice {
        val wantsPersonal = when (identity) {
            null -> step.mode == ConnectionMode.ACTING_USER
            WriteIdentity.PERSONAL -> true
            WriteIdentity.SYSTEM -> false
        }
        if (wantsPersonal) {
            if (!step.personalAllowed) {
                return ConnectionChoice(
                    SelectionStatus.NO_PERSONAL_CONNECTION, required, null, false, emptySet(),
                    "this step does not allow writing through a personal connection (node setting allowActingUser)", ConnectionMode.ACTING_USER,
                )
            }
            val personal = if (acting == null) {
                ConnectionChoice(
                    SelectionStatus.NO_ACTING_USER, required, null, false, emptySet(),
                    "the step acts as the person who approves or starts it, but this run was not started by a person " +
                        "(CI, the Jira intake watcher or the system); set fallbackToSystem or start the run yourself (manual: ${ConnectionSelector.PERSONAL_GUIDE})",
                    ConnectionMode.ACTING_USER,
                )
            } else {
                choosePersonal(acting, required, jiraProjectKey)
            }
            if (personal.usable || identity == WriteIdentity.PERSONAL || !step.fallbackToSystem) return personal
            val system = chooseShared(project, required, step, jiraProjectKey)
            return system.copy(message = "${personal.message}; falls back to the system connection: ${system.message}", fallback = true)
        }
        if (!step.systemAllowed) {
            return ConnectionChoice(
                SelectionStatus.NO_PERSONAL_CONNECTION, required, null, false, emptySet(),
                "this step writes only as the approving person (fallbackToSystem is off); the system connection may not be used",
                ConnectionMode.ACTING_USER,
            )
        }
        return chooseShared(project, required, step, jiraProjectKey)
    }

    /**
     * Opens the client for a step of executor [executor] working on [issueKey]: logs the connection used, warns when
     * it has more rights than needed, and fails (not retryable) when none fits.
     */
    fun open(
        context: NodeContext,
        executor: TaskExecutor,
        config: Map<String, Any?>,
        issueKey: String?,
        acting: ActingUser? = ActingUser.startedBy(context.run),
        identity: WriteIdentity? = null,
    ): IssueTracker {
        val required = ConnectionCapability.requiredBy(executor)
        val connection = require(context, executor, config, issueKey, acting, identity)
        return ScopedIssueTracker(trackers.open(connection), connection, required)
    }

    /** Like [open], also returning the connection (audit of external writes, ADR 0035). */
    fun openWith(
        context: NodeContext,
        executor: TaskExecutor,
        config: Map<String, Any?>,
        issueKey: String?,
        acting: ActingUser?,
        identity: WriteIdentity?,
    ): OpenedTracker {
        val required = ConnectionCapability.requiredBy(executor)
        val connection = require(context, executor, config, issueKey, acting, identity)
        return OpenedTracker(connection, ScopedIssueTracker(trackers.open(connection), connection, required))
    }

    /**
     * The connection a step of [executor] would use for [issueKey], logged (with the over-privileged warning). Fails
     * (not retryable) when none fits; a write step calls it before asking for confirmation so that nobody approves a
     * write that cannot happen.
     */
    fun require(
        context: NodeContext,
        executor: TaskExecutor,
        config: Map<String, Any?>,
        issueKey: String?,
        acting: ActingUser? = ActingUser.startedBy(context.run),
        identity: WriteIdentity? = null,
    ): Connection {
        val choice = choice(context, executor, config, issueKey, acting, identity)
        val connection = choice.connection ?: throw IntegrationException("jira", choice.message, retryable = false)
        val required = ConnectionCapability.requiredBy(executor)
        val how = when {
            choice.personal -> if (acting?.via == ActingUser.Via.APPROVAL) "they approved the write" else "they started the run"
            choice.pinned -> "pinned"
            choice.fallback -> "fallback"
            else -> null
        }
        val where = if (connection.personal) "personal connection of ${connection.ownerName ?: acting?.name ?: "its owner"}" else connection.scope.name.lowercase()
        context.log("Jira connection: ${connection.name} ($where${how?.let { ", $it" } ?: ""}) for ${required.sorted().joinToString()}")
        if (choice.fallback) context.log(choice.message)
        if (choice.status == SelectionStatus.OVER_PRIVILEGED) context.warn(choice.message)
        return connection
    }

    /** The choice of a step without failing (null connection = none fits; the message says why). */
    fun choice(
        context: NodeContext,
        executor: TaskExecutor,
        config: Map<String, Any?>,
        issueKey: String?,
        acting: ActingUser? = ActingUser.startedBy(context.run),
        identity: WriteIdentity? = null,
    ): ConnectionChoice {
        val required = ConnectionCapability.requiredBy(executor)
        return resolve(context.project, required, StepConnectionConfig.of(config), Connection.jiraProjectOf(issueKey), acting, identity)
    }

    /**
     * The intake watcher's client (ADR 0033): the least-privilege connection with ISSUE_READ for the project's Jira key,
     * narrowed to reading. Never a personal connection (ADR 0035). Null connection in the choice = none qualifies (the
     * watcher refuses to run).
     */
    fun openReader(project: Project): Pair<ConnectionChoice, IssueTracker?> {
        val required = setOf(ConnectionCapability.ISSUE_READ)
        val choice = chooseShared(project, required, StepConnectionConfig())
        val connection = choice.connection?.takeUnless { it.personal } ?: return choice to null
        return choice to ScopedIssueTracker(trackers.open(connection), connection, required)
    }

    /**
     * The client of a personal intake rule (ADR 0038, amending ADR 0035): the rule owner's own personal connection,
     * narrowed to reading. Refuses a connection that is not personal, belongs to someone else or cannot read issues.
     */
    fun openOwnReader(ownerUserId: UUID, connectionId: ConnectionId): OpenedTracker {
        val required = setOf(ConnectionCapability.ISSUE_READ)
        val connection = connections.findById(connectionId)
            ?.takeIf { it.personal && it.ownerUserId == ownerUserId }
            ?: throw IntegrationException("jira", "the connection of this rule is not one of your personal connections (deleted?); choose another one", retryable = false)
        if (ConnectionCapability.ISSUE_READ !in connection.capabilities) {
            throw IntegrationException("jira", "connection \"${connection.name}\" cannot read issues (ISSUE_READ)", retryable = false)
        }
        return OpenedTracker(connection, ScopedIssueTracker(trackers.open(connection), connection, required))
    }

    companion object {
        /** Node config key that selects the connection (`overrides.config.jiraConnection`). */
        const val CONFIG_KEY = StepConnectionConfig.CONNECTION_KEY

        /** The pinned connection id of [config], or null for `SYSTEM` / `ACTING_USER` / missing. */
        fun pinnedConnection(config: Map<String, Any?>): ConnectionId? = StepConnectionConfig.of(config).pinned
    }
}

/**
 * An [IssueTracker] that only allows what the step declared ([allowed] capabilities) and only on the Jira projects the
 * connection's allow-list names. A Fetch step cannot comment even when its connection could.
 */
class ScopedIssueTracker(
    private val delegate: IssueTracker,
    private val connection: Connection,
    private val allowed: Set<ConnectionCapability>,
) : IssueTracker {

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun fetch(issueKey: String): IssueSnapshot {
        guard(ConnectionCapability.ISSUE_READ, issueKey)
        return delegate.fetch(issueKey)
    }

    override fun search(jql: String, limit: Int): List<IssueSnapshot> {
        need(ConnectionCapability.ISSUE_READ)
        return delegate.search(restrictJql(jql, connection.allowedProjectKeys), limit)
    }

    override fun watch(jql: String, limit: Int): List<WatchedIssue> {
        need(ConnectionCapability.ISSUE_READ)
        return delegate.watch(restrictJql(jql, connection.allowedProjectKeys), limit)
    }

    override fun headlines(jql: String, limit: Int): List<IssueHeadline> {
        need(ConnectionCapability.ISSUE_READ)
        return delegate.headlines(restrictJql(jql, connection.allowedProjectKeys), limit)
    }

    override fun comment(issueKey: String, body: String) {
        guard(ConnectionCapability.ISSUE_COMMENT, issueKey)
        delegate.comment(issueKey, body)
    }

    override fun transition(issueKey: String, toStatus: String) {
        guard(ConnectionCapability.ISSUE_TRANSITION, issueKey)
        delegate.transition(issueKey, toStatus)
    }

    private fun guard(capability: ConnectionCapability, issueKey: String) {
        need(capability)
        if (connection.allowedProjectKeys.isNotEmpty()) {
            val project = Connection.jiraProjectOf(issueKey)
            if (project == null || project !in connection.allowedProjectKeys) {
                throw IntegrationException(
                    "jira",
                    "connection \"${connection.name}\" is limited to the Jira projects ${connection.allowedProjectKeys.joinToString()}; issue $issueKey is outside them",
                )
            }
        }
    }

    private fun need(capability: ConnectionCapability) {
        if (capability !in allowed) {
            throw IntegrationException("jira", "this step may not use $capability (it declared ${allowed.sorted().joinToString()})")
        }
        if (capability !in connection.capabilities) {
            throw IntegrationException("jira", "connection \"${connection.name}\" does not have the capability $capability")
        }
    }

    companion object {
        private val ORDER_BY = Regex("(?i)\\border\\s+by\\b")

        /** `status = Open ORDER BY created` + [A, B] -> `project in (A, B) AND (status = Open) ORDER BY created`. */
        fun restrictJql(jql: String, projectKeys: List<String>): String {
            if (projectKeys.isEmpty()) return jql
            val match = ORDER_BY.findAll(jql).lastOrNull()
            val where = (if (match != null) jql.substring(0, match.range.first) else jql).trim()
            val order = match?.let { jql.substring(it.range.first).trim() }
            val restriction = "project in (${projectKeys.joinToString()})"
            return listOfNotNull(if (where.isEmpty()) restriction else "$restriction AND ($where)", order).joinToString(" ")
        }
    }
}
