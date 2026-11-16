package pl.mos.backoffice.shared.security

import com.osucha.portpermissions.EffectivePermissions
import com.osucha.portpermissions.PermissionCatalog
import com.osucha.portpermissions.PermissionSet
import com.osucha.portpermissions.ScopeFilter
import com.osucha.portpermissions.ScopeSet
import com.osucha.portpermissions.SystemOnlyOperationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InsufficientPermissionException
import java.util.UUID
import com.osucha.portpermissions.PrincipalKind as GuardKind

class CurrentUserAccessGuardTest {

    private object Platform : PermissionSet("platform") {
        val VIEW = permission("view", "view")
        val MANAGE = permission("manage", "manage")
    }

    private object Work : PermissionSet("work") {
        val VIEW = scoped("view", "view", PROJECT_SCOPE)
        val START = scoped("start", "start", PROJECT_SCOPE)
        val EXECUTE = scoped("execute", "execute", PROJECT_SCOPE)
    }

    private val catalog = PermissionCatalog(listOf(Platform, Work))
    private val alpha = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val beta = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val gamma = UUID.fromString("00000000-0000-0000-0000-0000000000c3")

    private fun user(
        kind: PrincipalKind = PrincipalKind.USER,
        platform: Set<String> = emptySet(),
        projects: Map<UUID, Set<String>> = emptyMap(),
        allProjects: Boolean = false,
    ) = CurrentUser(kind, UserId.new(), "u@example.com", "U", platform, projects, allProjects)

    private class Guard(var user: CurrentUser, catalog: PermissionCatalog) : CurrentUserAccessGuard(catalog) {
        override fun currentUser(): CurrentUser = user
        override fun <T> asSystem(reason: String, block: () -> T): T {
            val previous = user
            user = CurrentUser.SYSTEM
            try {
                return block()
            } finally {
                user = previous
            }
        }
    }

    // ---------------------------------------------------------------- CurrentUser -> EffectivePermissions

    @Test
    fun `a user holds its platform keys and each project key on exactly the projects holding it`() {
        val effective = user(
            platform = setOf("platform.view"),
            projects = mapOf(alpha to setOf("work.view", "work.start"), beta to setOf("work.view")),
        ).effectivePermissions(catalog)

        assertThat(effective).isEqualTo(
            EffectivePermissions(
                GuardKind.USER,
                global = setOf("platform.view"),
                scoped = mapOf(
                    "work.view" to ScopeSet.only(setOf(alpha.toString(), beta.toString())),
                    "work.start" to ScopeSet.only(setOf(alpha.toString())),
                ),
            ),
        )
    }

    @Test
    fun `an allProjects holder holds every project-scoped key of the catalogue on every project`() {
        val effective = user(platform = setOf("platform.view"), projects = mapOf(alpha to setOf("work.start")), allProjects = true)
            .effectivePermissions(catalog)

        assertThat(effective.global).containsExactly("platform.view")
        assertThat(effective.scoped).isEqualTo(
            mapOf("work.view" to ScopeSet.allExcept(), "work.start" to ScopeSet.allExcept(), "work.execute" to ScopeSet.allExcept()),
        )
        assertThat(effective.holds(Work.EXECUTE, gamma)).isTrue()
    }

    @Test
    fun `a CI token is a machine principal holding its two keys on its project only`() {
        val ci = user(kind = PrincipalKind.CI_TOKEN, projects = mapOf(alpha to setOf("work.execute", "work.start")))
        val effective = ci.effectivePermissions(catalog)

        assertThat(effective.principal).isEqualTo(GuardKind.MACHINE)
        assertThat(effective.global).isEmpty()
        assertThat(effective.holds(Work.EXECUTE, alpha)).isTrue()
        assertThat(effective.holds(Work.EXECUTE, beta)).isFalse()
    }

    @Test
    fun `a personal access token is a delegated principal holding what the token resolved to`() {
        val token = user(kind = PrincipalKind.PERSONAL_TOKEN, platform = setOf("platform.view"), projects = mapOf(beta to setOf("work.view")))
            .copy(tokenCapabilities = setOf(TokenCapabilities.MCP_GATE_DECISIONS))
        val effective = token.effectivePermissions(catalog)

        assertThat(effective.principal).isEqualTo(GuardKind.DELEGATED)
        assertThat(effective.global).containsExactly("platform.view")
        assertThat(effective.scoped).isEqualTo(mapOf("work.view" to ScopeSet.only(setOf(beta.toString()))))
    }

    @Test
    fun `the system principal holds nothing (ADR 0067)`() {
        val effective = CurrentUser.SYSTEM.effectivePermissions(catalog)

        assertThat(effective).isEqualTo(EffectivePermissions.SYSTEM)
        assertThat(effective.principal).isEqualTo(GuardKind.SYSTEM)
        assertThat(effective.global).isEmpty()
        assertThat(effective.scoped).isEmpty()
        assertThat(CurrentUser.SYSTEM.allProjects).isFalse()
    }

    // ---------------------------------------------------------------- checks

    @Test
    fun `checks agree with the snapshot for every permission, project and kind of principal`() {
        val principals = listOf(
            user(platform = setOf("platform.view"), projects = mapOf(alpha to setOf("work.view"), beta to setOf("work.start"))),
            user(allProjects = true, platform = setOf("platform.manage")),
            user(kind = PrincipalKind.CI_TOKEN, projects = mapOf(alpha to setOf("work.execute"))),
            user(kind = PrincipalKind.PERSONAL_TOKEN, projects = mapOf(gamma to setOf("work.view"))),
            CurrentUser.SYSTEM,
            user(),
        )
        principals.forEach { principal ->
            val guard = Guard(principal, catalog)
            val effective = guard.effective()
            listOf(Platform.VIEW, Platform.MANAGE).forEach { assertThat(guard.isGranted(it)).`as`("$principal $it").isEqualTo(effective.holds(it)) }
            listOf(Work.VIEW, Work.START, Work.EXECUTE).forEach { permission ->
                listOf(alpha, beta, gamma).forEach { project ->
                    assertThat(guard.isGranted(permission, project)).`as`("$principal $permission $project").isEqualTo(effective.holds(permission, project))
                }
                assertThat(guard.scopesWith(permission)).`as`("$principal $permission").isEqualTo(effective.scopesOf(permission))
            }
        }
    }

    @Test
    fun `a denied check throws InsufficientPermissionException with key and project`() {
        val guard = Guard(user(platform = setOf("platform.view"), projects = mapOf(alpha to setOf("work.view"))), catalog)

        guard.require(Platform.VIEW)
        guard.require(Work.VIEW, alpha)
        assertThatThrownBy { guard.require(Platform.MANAGE) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) {
                assertThat(it.permission).isEqualTo("platform.manage")
                assertThat(it.projectId).isNull()
            }
        assertThatThrownBy { guard.require(Work.VIEW, beta) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) {
                assertThat(it.permission).isEqualTo("work.view")
                assertThat(it.projectId).isEqualTo(beta)
            }
    }

    @Test
    fun `inside asSystem every permission check fails and only requireSystem passes`() {
        val guard = Guard(user(allProjects = true, platform = setOf("platform.manage")), catalog)

        assertThatThrownBy { guard.requireSystem() }.isInstanceOf(SystemOnlyOperationException::class.java)
        guard.asSystem("test") {
            guard.requireSystem()
            assertThatThrownBy { guard.require(Platform.MANAGE) }
                .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("platform.manage") }
            assertThatThrownBy { guard.require(Work.START, gamma) }.isInstanceOf(InsufficientPermissionException::class.java)
            assertThat(guard.isGranted(Platform.VIEW)).isFalse()
            assertThat(guard.isGranted(Work.VIEW, alpha)).isFalse()
            assertThat(guard.scopesWith(Work.VIEW).isNothing).isTrue()
            assertThat(guard.effective()).isEqualTo(EffectivePermissions.SYSTEM)
        }
        // the caller's own access is back after the block
        guard.require(Platform.MANAGE)
        guard.require(Work.START, gamma)
    }

    // ---------------------------------------------------------------- scopesWith

    @Test
    fun `scopesWith lists the projects holding the permission, everything for allProjects, nothing when not held`() {
        val member = Guard(user(projects = mapOf(alpha to setOf("work.view"), beta to setOf("work.view", "work.start"))), catalog)

        assertThat(member.scopesWith(Work.VIEW)).isEqualTo(ScopeFilter.Only(setOf(alpha, beta)))
        assertThat(member.scopesWith(Work.START)).isEqualTo(ScopeFilter.Only(setOf(beta)))
        assertThat(member.scopesWith(Work.EXECUTE).isNothing).isTrue()
        assertThat(Guard(user(allProjects = true), catalog).scopesWith(Work.EXECUTE)).isEqualTo(ScopeFilter.All)
        assertThat(user(projects = mapOf(alpha to setOf("work.view"))).projectsWith(Work.VIEW)).isEqualTo(ScopeFilter.Only(setOf(alpha)))
    }

    // ---------------------------------------------------------------- catalogue

    @Test
    fun `modules label their permissions and a set may be contributed only once`() {
        val modules = PermissionModules(listOf(ModulePermissions("one", listOf(Platform)), ModulePermissions("two", listOf(Work))))

        assertThat(modules.definitions().map { it.key to modules.moduleOf(it) }).containsExactly(
            "platform.manage" to "one", "platform.view" to "one", "work.execute" to "two", "work.start" to "two", "work.view" to "two",
        )
        assertThat(Work.VIEW.permissionScope).isEqualTo(PermissionScope.PROJECT)
        assertThat(Platform.VIEW.permissionScope).isEqualTo(PermissionScope.PLATFORM)
        assertThatThrownBy { PermissionModules(listOf(ModulePermissions("one", listOf(Platform)), ModulePermissions("two", listOf(Platform)))) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
