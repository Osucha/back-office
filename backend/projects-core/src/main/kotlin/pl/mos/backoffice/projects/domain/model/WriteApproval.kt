package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import java.security.MessageDigest
import java.util.UUID

/**
 * Whether a step that writes to an external system (a capability with the `write` flag, ADR 0030) needs a person to
 * confirm the exact write first (ADR 0033). Node configuration `approval`; a missing value means [REQUIRED], also for
 * workflows saved before the setting existed.
 */
enum class WriteApprovalMode { REQUIRED, NOT_REQUIRED }

/** What a confirmation is about: the external write the step will make once approved. */
enum class ExternalWriteAction(val capability: ConnectionCapability) {
    /** Post a comment on a Jira issue (`NOTIFY`). */
    JIRA_COMMENT(ConnectionCapability.ISSUE_COMMENT),

    /** Move a Jira issue to another status (`TRANSITION_ISSUE`). */
    JIRA_TRANSITION(ConnectionCapability.ISSUE_TRANSITION),
}

/**
 * The exact external write a step prepared and waits to have confirmed (ADR 0033). It is rendered once, before the
 * pause, stored on the approval as the preview and in the run state; after approval the step writes exactly this
 * ([comment] text or [toStatus]) without rendering again. [connectionName] is the connection chosen at preview time
 * (informational: the write re-selects with the same least-privilege rule). ADR 0035: [connectionMode],
 * [personalAllowed] and [systemAllowed] tell the confirmation which identities the approver may write as (the
 * system connection, or the approver's own personal connection); they do not change the digest.
 */
data class PendingWrite(
    val action: ExternalWriteAction,
    val issueKey: String,
    val comment: String? = null,
    val toStatus: String? = null,
    val connectionId: String? = null,
    val connectionName: String? = null,
    val connectionMode: ConnectionMode = ConnectionMode.SYSTEM,
    val personalAllowed: Boolean = false,
    val systemAllowed: Boolean = true,
) {
    init {
        if (issueKey.isBlank()) throw ValidationException("a write needs an issue key", "issueKey")
        when (action) {
            ExternalWriteAction.JIRA_COMMENT -> if (comment == null) throw ValidationException("a comment write needs the comment text", "comment")
            ExternalWriteAction.JIRA_TRANSITION -> if (toStatus.isNullOrBlank()) throw ValidationException("a transition needs the target status", "toStatus")
        }
    }

    /** One line for the inbox and the run log: `comment on SHOP-42` / `move SHOP-42 to In Review`. */
    fun describe(): String = when (action) {
        ExternalWriteAction.JIRA_COMMENT -> "comment on $issueKey"
        ExternalWriteAction.JIRA_TRANSITION -> "move $issueKey to $toStatus"
    }

    /** The question of the confirmation: `Agent wants to comment on SHOP-42`. */
    fun question(): String = "Agent wants to ${describe()}"

    /** SHA-256 of what will be written (audit: proves the written content equals the approved preview). */
    fun digest(): String {
        val canonical = listOf(action.name, issueKey, comment ?: "", toStatus ?: "").joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** The identity of a confirmation without an explicit choice: the approver's own for `ACTING_USER`, else the system connection. */
    val defaultIdentity: WriteIdentity get() = if (connectionMode == ConnectionMode.ACTING_USER) WriteIdentity.PERSONAL else WriteIdentity.SYSTEM

    fun allows(identity: WriteIdentity): Boolean = when (identity) {
        WriteIdentity.PERSONAL -> personalAllowed
        WriteIdentity.SYSTEM -> systemAllowed
    }

    fun toStateMap(): Map<String, Any?> = mapOf(
        "action" to action.name, "issueKey" to issueKey, "comment" to comment, "toStatus" to toStatus,
        "connectionId" to connectionId, "connectionName" to connectionName,
        "connectionMode" to connectionMode.name, "personalAllowed" to personalAllowed, "systemAllowed" to systemAllowed,
    )

    override fun toString(): String = "PendingWrite(${describe()}, digest=${digest().take(12)})"

    companion object {
        fun fromStateMap(map: Map<String, Any?>): PendingWrite = PendingWrite(
            action = ExternalWriteAction.valueOf(map["action"]?.toString() ?: throw ValidationException("missing action", "action")),
            issueKey = map["issueKey"]?.toString() ?: "",
            comment = map["comment"]?.toString(),
            toStatus = map["toStatus"]?.toString(),
            connectionId = map["connectionId"]?.toString(),
            connectionName = map["connectionName"]?.toString(),
            // previews stored before ADR 0035: the system connection writes, as before
            connectionMode = map["connectionMode"]?.toString()?.let { m -> ConnectionMode.entries.firstOrNull { it.name == m } } ?: ConnectionMode.SYSTEM,
            personalAllowed = map["personalAllowed"]?.toString()?.toBooleanStrictOrNull() ?: false,
            systemAllowed = map["systemAllowed"]?.toString()?.toBooleanStrictOrNull() ?: true,
        )
    }
}

/**
 * Node configuration of write steps (ADR 0033): `approval` (`REQUIRED` | `NOT_REQUIRED`, default `REQUIRED`) and
 * `approvers` (`{projectRoles, users, accountable, forbidSelfApproval}` like a human gate; default: project owners and
 * maintainers who hold `run.approve`, accountable `owner`).
 */
object WriteApprovalConfig {
    const val APPROVAL_KEY = "approval"
    const val APPROVERS_KEY = "approvers"
    const val OWNER = "owner"
    const val MAINTAINER = "maintainer"

    /** Default rule: holders of `run.approve` with the project role owner or maintainer. */
    val DEFAULT_APPROVERS = GateApprovers(projectRoles = listOf(OWNER, MAINTAINER), accountable = OWNER)

    /** The options of a write confirmation; `reject` leaves the step through its `failure` port. */
    val OPTIONS = listOf(GateSpec.APPROVE, GateSpec.REJECT)

    /** Missing or blank = [WriteApprovalMode.REQUIRED]; an unknown value is a validation error (never silently off). */
    fun mode(config: Map<String, Any?>): WriteApprovalMode {
        val raw = config[APPROVAL_KEY]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return WriteApprovalMode.REQUIRED
        return WriteApprovalMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw ValidationException("must be one of ${WriteApprovalMode.entries.joinToString()}", "task.config.$APPROVAL_KEY")
    }

    /** The approver rule of the confirmation; missing = [DEFAULT_APPROVERS]. */
    fun approvers(config: Map<String, Any?>): GateApprovers {
        val raw = config[APPROVERS_KEY] as? Map<*, *> ?: return DEFAULT_APPROVERS
        val roles = (raw["projectRoles"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) } ?: DEFAULT_APPROVERS.projectRoles
        val users = (raw["users"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }?.map { value ->
            try {
                UUID.fromString(value)
            } catch (e: IllegalArgumentException) {
                throw ValidationException("$value is not a user id", "task.config.$APPROVERS_KEY.users")
            }
        } ?: emptyList()
        // an explicit `accountable` (also null) wins; without the key the default applies when it fits the roles
        val accountable = if (raw.containsKey("accountable")) {
            raw["accountable"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            DEFAULT_APPROVERS.accountable?.takeIf { it in roles }
        }
        val forbidSelf = raw["forbidSelfApproval"]?.toString()?.toBooleanStrictOrNull() ?: false
        return GateApprovers(roles, users, accountable, forbidSelf)
    }
}
