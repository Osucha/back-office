package pl.mos.backoffice.shared.security

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.Permission
import com.osucha.portpermissions.ScopeFilter
import com.osucha.portpermissions.ScopedPermission
import java.util.UUID

/** Who is calling (ADR 0017). */
enum class PrincipalKind { USER, PERSONAL_TOKEN, CI_TOKEN, SYSTEM }

/**
 * Capabilities a personal access token must carry explicitly, on top of its permissions (ADR 0029). They
 * guard actions that an AI agent holding the token (IDE agent through MCP) should not take on its own.
 */
object TokenCapabilities {
    /** `decide_gate` through the MCP server; off by default. */
    const val MCP_GATE_DECISIONS = "mcp.gate-decisions"

    /** `create_workflow` / `update_workflow` through the MCP server (ADR 0049); off by default: an agent could drop a gate. */
    const val MCP_WORKFLOW_EDITS = "mcp.workflow-edits"
}

@JvmInline
value class UserId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): UserId = UserId(UUID.randomUUID())
        fun of(value: String): UserId = UserId(UUID.fromString(value))
    }
}

/**
 * Who did something, for `createdBy` / `decidedBy` stamps. Stored denormalised (display name at the time
 * of the action) so history stays readable after a user is renamed or deactivated.
 */
data class Actor(val kind: PrincipalKind, val userId: UUID?, val displayName: String) {
    companion object {
        val SYSTEM = Actor(PrincipalKind.SYSTEM, null, "system")
    }
}

/**
 * The authenticated principal with its **effective** permissions, resolved per request (never frozen in a
 * token). Platform-scoped permissions are plain keys (`admin.users.manage`); project-scoped permissions
 * are held per project (`run.approve` on project X). Use cases check them through the [AccessGuard]
 * (port-permissions, ADR 0066); [effectivePermissions] is the same access as the library snapshot.
 */
data class CurrentUser(
    val kind: PrincipalKind,
    val userId: UserId?,
    /** e-mail for users, `ci:<projectKey>` for CI tokens, `system` for background work. */
    val login: String,
    val displayName: String,
    val platformPermissions: Set<String>,
    val projectPermissions: Map<UUID, Set<String>>,
    /** Holders of `admin.projects.all` get every project-scoped permission on every project. */
    val allProjects: Boolean = false,
    /** Project role keys per project (direct and via groups); used by gate approver rules. */
    val projectRoles: Map<UUID, Set<String>> = emptyMap(),
    /**
     * Opt-in capabilities of the personal access token that authenticated the request ([TokenCapabilities]);
     * empty for sessions, CI tokens and the system.
     */
    val tokenCapabilities: Set<String> = emptySet(),
) {
    fun hasProjectRole(projectId: UUID, roles: Collection<String>): Boolean =
        projectRoles[projectId]?.any { it in roles } == true

    fun can(permission: String): Boolean = permission in platformPermissions

    fun can(permission: String, projectId: UUID): Boolean =
        allProjects || projectPermissions[projectId]?.contains(permission) == true

    fun can(permission: Permission): Boolean = can(permission.key)

    fun can(permission: ScopedPermission<UUID>, projectId: UUID): Boolean = can(permission.key, projectId)

    /** The projects on which this principal holds [permission], for list queries. */
    fun projectsWith(permission: ScopedPermission<UUID>): ScopeFilter<UUID> =
        if (allProjects) ScopeFilter.All
        else ScopeFilter.Only(projectPermissions.filterValues { permission.key in it }.keys)

    val actor: Actor get() = Actor(kind, userId?.value, displayName)

    companion object {
        val SYSTEM = CurrentUser(
            kind = PrincipalKind.SYSTEM,
            userId = null,
            login = "system",
            displayName = "system",
            platformPermissions = emptySet(),
            projectPermissions = emptyMap(),
            allProjects = true,
        )
    }
}

/** Port implemented by the identity context so that other contexts can provision access. */
interface ProjectAccessProvisioner {
    /** The creator of a project becomes its OWNER. */
    fun grantCreator(projectId: UUID, projectKey: String, creator: Actor)

    /** Removes memberships of a deleted project. */
    fun projectDeleted(projectId: UUID)

    /** The project got a new key: memberships show it from now on. */
    fun projectRenamed(projectId: UUID, projectKey: String) = Unit
}

/**
 * Port implemented by the identity context: who can act on a project, resolved the same way as for a
 * request (grants, roles, direct and group memberships, `admin.projects.all`). The Projects context uses it
 * to offer and validate the assignees of a human gate (ADR 0028). Not a use case: callers check permissions.
 */
interface ProjectAccessDirectory {
    /** Active users who are members of [projectId] (directly or through a group), with their resolved access. */
    fun members(projectId: UUID): List<CurrentUser>

    /** Resolved access of an active user, or null when the user does not exist or is not active. */
    fun user(userId: UUID): CurrentUser?

    /** Display names of users; unknown ids are left out. */
    fun displayNames(userIds: Collection<UUID>): Map<UUID, String>
}

/** Result of verifying a project CI token (`bo_ci_...`). */
data class CiTokenIdentity(val tokenId: UUID, val projectId: UUID, val projectKey: String, val tokenName: String)

/**
 * Port implemented by the Projects context (which owns CI tokens) and used by the identity context's
 * bearer-token filter to turn a CI token into a [CurrentUser] of kind [PrincipalKind.CI_TOKEN] holding
 * `ci.execute` and `ci.signal` on that project. Implementations update `lastUsedAt`.
 */
fun interface CiTokenVerifier {
    fun verify(plaintext: String): CiTokenIdentity?
}
