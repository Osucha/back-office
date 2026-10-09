package pl.mos.backoffice.projects.adapter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CiTokenVerifier
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectAccessDirectory
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.security.UserId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Test-only stand-ins for the identity context so projects-adapters tests stay independent of
 * identity-adapters: a thread-bound gate, a servlet filter that authenticates requests (CI tokens through
 * this context's CiTokenVerifier, everything else as a session user) and recording audit/provisioning ports.
 */

/** Thread-bound [AuthorizationGate]; threads without a principal act as [defaultUser] (the project owner). */
class TestAuthorizationGate : AuthorizationGate {
    private val current = ThreadLocal<CurrentUser?>()

    @Volatile var defaultUser: CurrentUser = OWNER

    override fun currentUser(): CurrentUser = current.get() ?: defaultUser

    override fun require(permission: String): CurrentUser {
        val user = currentUser()
        if (!user.can(permission)) throw InsufficientPermissionException(permission)
        return user
    }

    override fun require(permission: String, projectId: UUID): CurrentUser {
        val user = currentUser()
        if (!user.can(permission, projectId)) throw InsufficientPermissionException(permission, projectId)
        return user
    }

    override fun <T> asSystem(reason: String, block: () -> T): T = runAs(CurrentUser.SYSTEM, block)

    fun <T> runAs(user: CurrentUser, block: () -> T): T {
        val previous = current.get()
        current.set(user)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        val OWNER_ID: UserId = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))

        /** A session user with platform-wide project access (like `admin.projects.all`) plus the member platform permissions. */
        val OWNER = CurrentUser(
            kind = PrincipalKind.USER,
            userId = OWNER_ID,
            login = "owner@example.com",
            displayName = "Olivia Owner",
            platformPermissions = setOf(ProjectsPermissions.PROJECTS_CREATE, ProjectsPermissions.INTEGRATIONS_VIEW),
            projectPermissions = emptyMap(),
            allProjects = true,
        )

        fun ciToken(projectId: UUID, projectKey: String, tokenName: String) = CurrentUser(
            kind = PrincipalKind.CI_TOKEN,
            userId = null,
            login = "ci:$projectKey",
            displayName = "CI token $tokenName ($projectKey)",
            platformPermissions = emptySet(),
            projectPermissions = mapOf(projectId to setOf(ProjectsPermissions.CI_EXECUTE, ProjectsPermissions.CI_SIGNAL)),
        )

        /** `X-Test-Platform-Permissions: p1,p2` - a user with exactly those platform permissions and no project access. */
        fun platformUser(permissions: Set<String>, userId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c3")) = CurrentUser(
            kind = PrincipalKind.USER,
            userId = UserId(userId),
            login = "admin@example.com",
            displayName = "Ada Admin",
            platformPermissions = permissions,
            projectPermissions = emptyMap(),
        )

        /** `X-Test-Project-Permissions: <projectId>=perm1,perm2` - a user with exactly those permissions on one project. */
        fun member(projectId: UUID, permissions: Set<String>, roles: Set<String> = emptySet()) = CurrentUser(
            kind = PrincipalKind.USER,
            userId = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000b2")),
            login = "member@example.com",
            displayName = "Mia Member",
            platformPermissions = emptySet(),
            projectPermissions = mapOf(projectId to permissions),
            projectRoles = mapOf(projectId to roles),
        )
    }
}

/**
 * Authenticates test requests the way the identity filters do in production:
 * - `Authorization: Bearer bo_ci_...` -> [CiTokenVerifier]; unknown or revoked -> 401;
 * - any other `Authorization` header or `X-Test-Anonymous` -> 401 (no session);
 * - `X-Test-Project-Permissions: <projectId>=p1,p2` -> a member with exactly those permissions (and the
 *   project roles listed in `X-Test-Project-Roles`);
 * - otherwise -> the session owner ([TestAuthorizationGate.OWNER]).
 */
class TestAuthenticationFilter(private val gate: TestAuthorizationGate, private val verifier: CiTokenVerifier) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val user = resolve(request)
        if (user == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write("""{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing, unknown or revoked credentials"}""")
            return
        }
        gate.runAs(user) { chain.doFilter(request, response) }
    }

    private fun resolve(request: HttpServletRequest): CurrentUser? {
        if (request.getHeader("X-Test-Anonymous") != null) return null
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization != null) {
            val token = authorization.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim() ?: return null
            val identity = verifier.verify(token) ?: return null
            return TestAuthorizationGate.ciToken(identity.projectId, identity.projectKey, identity.tokenName)
        }
        request.getHeader("X-Test-Platform-Permissions")?.let { spec ->
            val permissions = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            // `X-Test-User-Id`: another person with the same permissions (personal connections, ADR 0035)
            val id = request.getHeader("X-Test-User-Id")?.let(UUID::fromString)
            return if (id == null) TestAuthorizationGate.platformUser(permissions) else TestAuthorizationGate.platformUser(permissions, id)
        }
        request.getHeader("X-Test-Project-Permissions")?.let { spec ->
            val (project, permissions) = spec.split("=", limit = 2)
            val roles = request.getHeader("X-Test-Project-Roles")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            return TestAuthorizationGate.member(UUID.fromString(project.trim()), permissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(), roles)
        }
        // `X-Test-Extra-Platform-Permissions: p1,p2`: the session owner plus those platform permissions (library.manage, ADR 0037)
        request.getHeader("X-Test-Extra-Platform-Permissions")?.let { spec ->
            val extra = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return gate.defaultUser.copy(platformPermissions = gate.defaultUser.platformPermissions + extra)
        }
        return gate.defaultUser
    }
}

/** Thread-safe audit recorder (requests and engine threads may record concurrently). */
class ConcurrentRecordingAuditPort : AuditPort {
    val entries = CopyOnWriteArrayList<AuditEntry>()
    override fun record(entry: AuditEntry) {
        entries += entry
    }
    fun operations(): List<String> = entries.map { it.operation }
}

class RecordingProjectAccessProvisioner : ProjectAccessProvisioner {
    val granted = CopyOnWriteArrayList<Pair<UUID, Actor>>()
    val deleted = CopyOnWriteArrayList<UUID>()
    override fun grantCreator(projectId: UUID, projectKey: String, creator: Actor) {
        granted += projectId to creator
    }
    override fun projectDeleted(projectId: UUID) {
        deleted += projectId
    }
}

/** The identity context's [ProjectAccessDirectory]: tests register the users (with their project access) by hand. */
class FakeProjectAccessDirectory : ProjectAccessDirectory {
    val users = java.util.concurrent.ConcurrentHashMap<UUID, CurrentUser>()

    fun register(user: CurrentUser): CurrentUser = user.also { users[it.userId!!.value] = it }

    override fun members(projectId: UUID): List<CurrentUser> =
        users.values.filter { it.projectPermissions.containsKey(projectId) || it.projectRoles.containsKey(projectId) }.sortedBy { it.displayName }

    override fun user(userId: UUID): CurrentUser? = users[userId]

    override fun displayNames(userIds: Collection<UUID>): Map<UUID, String> =
        userIds.mapNotNull { id -> users[id]?.let { id to it.displayName } }.toMap()
}
