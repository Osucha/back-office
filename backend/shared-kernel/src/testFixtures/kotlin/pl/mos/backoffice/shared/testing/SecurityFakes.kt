package pl.mos.backoffice.shared.testing

import com.osucha.portpermissions.Permission
import com.osucha.portpermissions.PermissionCatalog
import com.osucha.portpermissions.PermissionDefinition
import com.osucha.portpermissions.ScopedPermission
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.CurrentUserAccessGuard
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import java.util.UUID

/**
 * Test double for the `AccessGuard` and `CurrentPrincipal` ports (ADR 0066), with the production check rules
 * of [CurrentUserAccessGuard] over a settable [user]. Use [permitting] with the exact permission a use case
 * needs, so a test fails when the use case checks the wrong one (a permit-all fake would hide that).
 */
class FakeAccessGuard(
    var user: CurrentUser,
    catalog: PermissionCatalog = PermissionCatalog(emptyList()),
) : CurrentUserAccessGuard(catalog) {

    /** Every permission checked, in order: `"run.approve"` or `"run.approve@<projectId>"`. */
    val checked = mutableListOf<String>()

    override fun require(permission: Permission) {
        checked += permission.key
        super.require(permission)
    }

    override fun <S : Any> require(permission: ScopedPermission<S>, scope: S) {
        checked += "${permission.key}@${permission.scopeType.key(scope)}"
        super.require(permission, scope)
    }

    /** `asSystem` is bound to the calling thread like the production guard, so a worker's system work does not leak to a test thread. */
    private val system = ThreadLocal<Boolean>()

    override fun currentUser(): CurrentUser = if (system.get() == true) CurrentUser.SYSTEM else user

    override fun <T> asSystem(reason: String, block: () -> T): T {
        val previous = system.get()
        system.set(true)
        try {
            return block()
        } finally {
            if (previous == null) system.remove() else system.set(previous)
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

        /** Grants exactly [permissions] (keys) at platform level and on every project in [projects]. */
        fun permitting(vararg permissions: String, projects: Set<UUID> = emptySet()) = FakeAccessGuard(
            user(platform = permissions.toSet(), projects = projects.associateWith { permissions.toSet() }),
        )

        /** Grants exactly [permissions] at platform level and on every project in [projects]. */
        fun permitting(vararg permissions: PermissionDefinition, projects: Set<UUID> = emptySet()) =
            permitting(*permissions.map { it.key }.toTypedArray(), projects = projects)

        /** Grants everything everywhere (platform admin with `allProjects`). Prefer [permitting]. */
        fun permitAll(vararg platform: PermissionDefinition) =
            FakeAccessGuard(user(platform = platform.map { it.key }.toSet(), allProjects = true))

        fun denyAll() = FakeAccessGuard(user())

        /**
         * The system principal (ADR 0067) for unit tests of system operations: `requireSystem()` passes, every
         * permission check fails.
         */
        fun system() = FakeAccessGuard(CurrentUser.SYSTEM)
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
