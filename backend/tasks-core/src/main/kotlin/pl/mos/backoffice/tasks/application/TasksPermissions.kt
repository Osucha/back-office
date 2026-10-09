package pl.mos.backoffice.tasks.application

import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope

/**
 * Permissions of the Task Library context (docs/architecture/08-identity-and-access.md, ADR 0017).
 * Global entries need the platform permissions; project-scoped entries need `library.project.manage` on
 * that project to change them and are visible to holders of `project.view` or `library.project.manage`
 * of that project.
 */
object TasksPermissions {
    const val MODULE = "tasks"

    /** Read the global task library (templates, interfaces, skills, resolution previews). */
    const val LIBRARY_VIEW = "library.view"

    /** Create, edit and delete global templates, interfaces and skills. */
    const val LIBRARY_MANAGE = "library.manage"

    /** Create, edit and delete templates and skills scoped to one project. */
    const val LIBRARY_PROJECT_MANAGE = "library.project.manage"

    /** Declared by the Projects context; members of a project see the project's own library entries. */
    const val PROJECT_VIEW = "project.view"

    val definitions: List<PermissionDefinition> = listOf(
        PermissionDefinition(LIBRARY_VIEW, PermissionScope.PLATFORM, MODULE, "Read the global task library"),
        PermissionDefinition(LIBRARY_MANAGE, PermissionScope.PLATFORM, MODULE, "Create and edit global templates, interfaces and skills"),
        PermissionDefinition(LIBRARY_PROJECT_MANAGE, PermissionScope.PROJECT, MODULE, "Create and edit project-scoped templates and skills"),
    )
}
