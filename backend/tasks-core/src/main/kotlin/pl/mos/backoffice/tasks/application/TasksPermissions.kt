package pl.mos.backoffice.tasks.application

import com.osucha.portpermissions.PermissionSet
import pl.mos.backoffice.shared.security.LibraryAccessPermissions
import pl.mos.backoffice.shared.security.ModulePermissions
import pl.mos.backoffice.shared.security.PROJECT_SCOPE
import pl.mos.backoffice.shared.security.ProjectAccessPermissions

/**
 * Permissions of the Task Library context (docs/architecture/08-identity-and-access.md, ADR 0017, ADR 0066).
 * Global entries need the platform permissions; project-scoped entries need `library.project.manage` on
 * that project to change them and are visible to holders of `project.view` or `library.project.manage`
 * of that project.
 */
object TasksPermissions {
    const val MODULE = "tasks"

    object LibraryProject : PermissionSet("library") {
        val MANAGE = scoped("project.manage", "Create and edit project-scoped templates, skills and rules", PROJECT_SCOPE)
    }

    /** Read the global task library (templates, skills, rules, resolution previews). */
    val LIBRARY_VIEW = LibraryAccessPermissions.VIEW

    /** Create, edit and delete global templates, skills and rules. */
    val LIBRARY_MANAGE = LibraryAccessPermissions.MANAGE

    /** Create, edit and delete templates, skills and rules scoped to one project. */
    val LIBRARY_PROJECT_MANAGE = LibraryProject.MANAGE

    /** Contributed by the Projects context; members of a project see the project's own library entries. */
    val PROJECT_VIEW = ProjectAccessPermissions.VIEW

    /** What this context contributes to the catalogue. */
    val contribution = ModulePermissions(MODULE, listOf(LibraryAccessPermissions, LibraryProject))
}
