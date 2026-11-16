package pl.mos.backoffice.shared.testing

import com.osucha.portpermissions.PermissionSet
import pl.mos.backoffice.shared.security.ModulePermissions
import pl.mos.backoffice.shared.security.PROJECT_SCOPE

/**
 * A made-up catalogue contribution declaring exactly [platform] and [project] keys (description = key), for
 * tests that need permissions no real module declares. Keys are split into one set per first segment.
 */
fun testModulePermissions(module: String, platform: Collection<String> = emptyList(), project: Collection<String> = emptyList()): ModulePermissions {
    val sets = linkedMapOf<String, TestPermissionSet>()
    fun setOf(key: String) = sets.getOrPut(key.substringBefore('.')) { TestPermissionSet(key.substringBefore('.')) }
    platform.forEach { setOf(it).platform(it.substringAfter('.'), it) }
    project.forEach { setOf(it).project(it.substringAfter('.'), it) }
    return ModulePermissions(module, sets.values.toList())
}

private class TestPermissionSet(module: String) : PermissionSet(module) {
    fun platform(name: String, description: String) = permission(name, description)
    fun project(name: String, description: String) = scoped(name, description, PROJECT_SCOPE)
}
