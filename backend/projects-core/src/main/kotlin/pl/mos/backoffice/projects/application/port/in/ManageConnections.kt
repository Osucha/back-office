package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import java.time.LocalDate

/**
 * Use case: the least-privilege connection registry (ADR 0030). Platform connections need `admin.connections.manage`
 * to change (`integrations.view` to list); project connections need `project.connections.manage` (`project.view` to
 * list). Secrets go in, never out. Every change and every verification is audited.
 */
interface ManageConnections {
    fun listPlatform(): List<Connection>
    fun listProject(projectId: ProjectId): List<Connection>

    /** Every connection a run of [projectId] may use, in selection order (project, platform, legacy). */
    fun usable(projectId: ProjectId): List<Connection>

    /** [projectId] null = a platform connection. */
    fun get(projectId: ProjectId?, id: ConnectionId): Connection
    fun create(projectId: ProjectId?, command: ConnectionCommand): Connection
    fun update(projectId: ProjectId?, id: ConnectionId, command: ConnectionCommand): Connection
    fun rotateSecret(projectId: ProjectId?, id: ConnectionId, secret: String, expiresAt: LocalDate?): Connection
    fun delete(projectId: ProjectId?, id: ConnectionId)

    /** Checks the connection against Jira and stores the result (the legacy connection's result is only returned). */
    fun verify(projectId: ProjectId?, id: ConnectionId, sampleProjectKey: String?): Connection

    /**
     * "Test before saving": verifies a draft against Jira without storing anything. The secret is sealed in memory only;
     * omitted, the stored secret of [existingId] is used (editing). Needs the manage permission of the scope; audited
     * `CONNECTION_TEST` (never with the secret).
     */
    fun test(projectId: ProjectId?, command: ConnectionCommand, existingId: ConnectionId?, sampleProjectKey: String?): ConnectionVerification

    /**
     * Connections whose token expires within [Connection.EXPIRY_REMINDER_DAYS] days or has expired, that the caller may
     * manage (platform: `admin.connections.manage`; project: `project.connections.manage`; personal: its owner). For the
     * "Your turn" card.
     */
    fun expiring(): List<Connection>

    /** Which connection a step of [projectId] needing [capabilities] would use (designer; `project.view`). */
    fun resolve(projectId: ProjectId, capabilities: Set<ConnectionCapability>, pinned: ConnectionId?, jiraProjectKey: String?): ConnectionChoice

    data class ConnectionCommand(
        val type: ConnectionType,
        val name: String,
        val siteUrl: String,
        val accountEmail: String,
        /** Required on create; null on update keeps the stored secret. */
        val secret: String?,
        val capabilities: Set<ConnectionCapability>,
        val allowedProjectKeys: List<String> = emptyList(),
        val secretExpiresAt: LocalDate? = null,
        val verifyProjectKey: String? = null,
        /** ADR 0035: classic or scoped token. */
        val tokenKind: JiraTokenKind = JiraTokenKind.CLASSIC,
        /** Scoped tokens: the site's cloud id; null = discovered from `<site>/_edge/tenant_info` on save. */
        val cloudId: String? = null,
        /** Scoped tokens: the scopes chosen when the token was created. */
        val tokenScopes: List<String> = emptyList(),
        /** Classic tokens: the person confirmed that the token can do everything its account can. */
        val acknowledgeClassicToken: Boolean = false,
    ) {
        override fun toString(): String =
            "ConnectionCommand(type=$type, name=$name, siteUrl=$siteUrl, capabilities=$capabilities, tokenKind=$tokenKind, secret=${if (secret == null) "unchanged" else "***"})"
    }
}

/**
 * Use case (ADR 0035): personal connections, created by a user for themselves under My account → Connections
 * (`connections.personal`). They are only ever used with their owner's action (approving a write, starting a run) and
 * never readable by anybody else; administrators (`admin.connections.manage`) see their metadata in [listAllPersonal].
 */
interface ManagePersonalConnections {
    fun listMine(): List<Connection>
    fun getMine(id: ConnectionId): Connection
    fun createMine(command: ManageConnections.ConnectionCommand): Connection
    fun updateMine(id: ConnectionId, command: ManageConnections.ConnectionCommand): Connection
    fun rotateMine(id: ConnectionId, secret: String, expiresAt: LocalDate?): Connection
    fun deleteMine(id: ConnectionId)
    fun verifyMine(id: ConnectionId, sampleProjectKey: String?): Connection
    fun testMine(command: ManageConnections.ConnectionCommand, existingId: ConnectionId?, sampleProjectKey: String?): ConnectionVerification

    /** Metadata (name, site, capabilities, owner, verification) of every personal connection; `admin.connections.manage`. */
    fun listAllPersonal(): List<Connection>
}

/**
 * Use case (ADR 0035): the project's Jira connections: the system connection (default of every Jira step) and the
 * ordered list of further connections. Reading needs `project.view`, changing `project.connections.manage`. Personal
 * connections can never be part of it.
 */
interface ManageProjectJiraConnections {
    fun jiraConnections(projectId: ProjectId): ProjectJiraConnectionsView
    fun updateJiraConnections(projectId: ProjectId, settings: ProjectJiraConnections): ProjectJiraConnectionsView

    /** The settings with the connections they name (unknown ids are dropped) and the project and platform connections to choose from. */
    data class ProjectJiraConnectionsView(
        val settings: ProjectJiraConnections,
        val system: Connection?,
        val additional: List<Connection>,
        val available: List<Connection>,
    )
}

/**
 * System use case (ADR 0030): the daily job publishes `ConnectionExpiring` for every connection whose token expires
 * within [Connection.EXPIRY_REMINDER_DAYS] days (once per expiry date). Returns the number of reminders.
 */
fun interface RemindExpiringConnections {
    fun remindExpiring(): Int
}

/** System use case: re-wraps every stored secret to the active key-encryption key after a key rotation (ADR 0030). */
fun interface RewrapConnectionSecrets {
    /** Returns the number of re-wrapped secrets. */
    fun rewrapSecrets(): Int
}
