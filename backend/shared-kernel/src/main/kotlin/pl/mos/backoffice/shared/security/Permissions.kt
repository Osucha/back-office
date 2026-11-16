package pl.mos.backoffice.shared.security

import com.osucha.portpermissions.PermissionCatalog
import com.osucha.portpermissions.PermissionDefinition
import com.osucha.portpermissions.PermissionSet
import com.osucha.portpermissions.ScopeType
import java.util.UUID

/*
 * Permission catalogue on port-permissions (ADR 0017, ADR 0066). Every module declares its permissions as
 * `PermissionSet`s next to its use cases; a key is `<set module>.<name>`, so one Back Office module usually
 * declares several small sets (`run.start` lives in `PermissionSet("run")`). Code is the source of truth, the
 * database stores only grants (roles, grants, token permission subsets) by key.
 */

/** Platform-wide or held per project. Roles carry it too; a catalogue entry has it through [permissionScope]. */
enum class PermissionScope { PLATFORM, PROJECT }

/** Projects are the only scope of Back Office permissions; the guard sees project ids as UUIDs. */
val PROJECT_SCOPE: ScopeType<UUID> = ScopeType("project", key = { it.toString() }, parse = UUID::fromString)

/** [PermissionScope.PLATFORM] for a global permission, [PermissionScope.PROJECT] for a project-scoped one. */
val PermissionDefinition.permissionScope: PermissionScope
    get() = if (scope == null) PermissionScope.PLATFORM else PermissionScope.PROJECT

/**
 * The permission sets one Back Office module contributes to the catalogue, as a bean. [module] is the label
 * the admin screens group them under (`projects`, `identity`, ...), not the first segment of the keys.
 */
class ModulePermissions(val module: String, val sets: List<PermissionSet>)

/**
 * All [ModulePermissions] of the application: the library [catalog] plus the Back Office module that
 * contributed each permission. A set contributed by two modules, or a key declared twice, fails startup.
 */
class PermissionModules(modules: List<ModulePermissions>) {

    val catalog: PermissionCatalog = PermissionCatalog(modules.flatMap { it.sets })

    private val moduleByKey: Map<String, String>

    init {
        val owners = modules.flatMap { m -> m.sets.map { it to m.module } }.groupBy({ it.first }, { it.second })
        val shared = owners.filterValues { it.size > 1 }.keys.map { it.module }
        check(shared.isEmpty()) { "permission sets contributed by more than one module: $shared" }
        moduleByKey = owners.flatMap { (set, module) -> set.permissions.map { it.key to module.single() } }.toMap()
    }

    /** The contributing Back Office module of [permission]. */
    fun moduleOf(permission: PermissionDefinition): String = moduleByKey.getValue(permission.key)

    /** Catalogue entries ordered by contributing module, then key (the order of the admin screens). */
    fun definitions(): List<PermissionDefinition> = catalog.permissions.sortedWith(compareBy({ moduleOf(it) }, { it.key }))
}

/*
 * Permissions checked by more than one bounded context. Core modules cannot see each other, so these sets
 * live here; each is contributed to the catalogue by the module that owns it (named in its KDoc).
 */

/** `project.view`, `project.manage`. Owned and contributed by Projects. */
object ProjectAccessPermissions : PermissionSet("project") {
    val VIEW = scoped("view", "see the project, its workflows, runs, CI executions", PROJECT_SCOPE)
    val MANAGE = scoped("manage", "edit project settings, CI entrypoints, delete the project", PROJECT_SCOPE)
}

/** `project.members.manage`. Owned and contributed by Identity. */
object ProjectMembershipPermissions : PermissionSet("project") {
    val MEMBERS_MANAGE = scoped("members.manage", "Add/remove members and change their project roles", PROJECT_SCOPE)
}

/** `library.view`, `library.manage`. Owned and contributed by the Task Library. */
object LibraryAccessPermissions : PermissionSet("library") {
    val VIEW = permission("view", "Read the global task library")
    val MANAGE = permission("manage", "Create and edit global templates, skills and rules")
}

/** `admin.settings.manage`. Owned and contributed by Identity. */
object PlatformSettingsPermissions : PermissionSet("admin") {
    val SETTINGS_MANAGE = permission("settings.manage", "Platform settings (registration mode, domains)")
}

/** `models.view`. Owned and contributed by Model Evaluation. */
object ModelAccessPermissions : PermissionSet("models") {
    val VIEW = permission("view", "Read model profiles")
}
