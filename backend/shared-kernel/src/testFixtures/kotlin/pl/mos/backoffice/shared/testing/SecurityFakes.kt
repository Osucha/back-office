package pl.mos.backoffice.shared.testing

import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import java.util.UUID

/**
 * Test double for [AuthorizationGate]. Use [permitting] with the exact permission a use case needs, so a
 * test fails when the use case checks the wrong constant (a permit-all fake would hide that).
 */
class FakeAuthorizationGate(var user: CurrentUser) : AuthorizationGate {

    /** Every permission checked, in order: `"run.approve"` or `"run.approve@<projectId>"`. */
    val checked = mutableListOf<String>()

    override fun currentUser(): CurrentUser = user

    override fun require(permission: String): CurrentUser {
        checked += permission
        if (!user.can(permission)) throw InsufficientPermissionException(permission)
        return user
    }

    override fun require(permission: String, projectId: UUID): CurrentUser {
        checked += "$permission@$projectId"
        if (!user.can(permission, projectId)) throw InsufficientPermissionException(permission, projectId)
        return user
    }

    override fun <T> asSystem(reason: String, block: () -> T): T {
        val previous = user
        user = CurrentUser.SYSTEM
        try {
            return block()
        } finally {
            user = previous
        }
    }

    companion object {
        val TEST_USER_ID: UserId = UserId(UUID.fromString("00000000-0000-0000-0000-00000000a11c"))

        fun user(
            platform: Set<String> = emptySet(),
            projects: Map<UUID, Set<String>> = emptyMap(),
            allProjects: Boolean = false,
        ) = CurrentUser(
            kind = PrincipalKind.USER,
            userId = TEST_USER_ID,
            login = "alice@example.com",
            displayName = "Alice Test",
            platformPermissions = platform,
            projectPermissions = projects,
            allProjects = allProjects,
        )

        /** Grants exactly [permissions] at platform level and on every project in [projects]. */
        fun permitting(vararg permissions: String, projects: Set<UUID> = emptySet()) = FakeAuthorizationGate(
            user(platform = permissions.toSet(), projects = projects.associateWith { permissions.toSet() }),
        )

        /** Grants everything everywhere (platform admin with `allProjects`). Prefer [permitting]. */
        fun permitAll(vararg platform: String) = FakeAuthorizationGate(user(platform = platform.toSet(), allProjects = true))

        fun denyAll() = FakeAuthorizationGate(user())
    }
}

/** Collects audit entries for assertions. */
class RecordingAuditPort : AuditPort {
    val entries = mutableListOf<AuditEntry>()
    override fun record(entry: AuditEntry) {
        entries += entry
    }
    fun operations(): List<String> = entries.map { it.operation }
}
