package pl.mos.backoffice.tasks.application.service

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.allows
import pl.mos.backoffice.shared.security.CurrentPrincipal
import pl.mos.backoffice.tasks.application.TasksPermissions
import pl.mos.backoffice.tasks.domain.model.Scope
import java.util.UUID

/*
 * Scope-aware guard checks shared by the Task Library use cases (ADR 0017, ADR 0066). They are `inline` on
 * purpose: the `AccessGuard.require(...)` calls end up in the bytecode of every calling use-case method, which
 * is what the deny-by-default ArchUnit rule (port-permissions `operationsAreGuarded`) looks for.
 */

/** True when the caller may see entries scoped to [projectId] (project members and project library editors). */
internal inline fun AccessGuard.canSeeProject(projectId: UUID): Boolean =
    scopesWith(TasksPermissions.PROJECT_VIEW).allows(projectId) ||
        scopesWith(TasksPermissions.LIBRARY_PROJECT_MANAGE).allows(projectId)

/** Read check for one entry: `library.view` for global entries, project visibility for project entries. */
internal inline fun AccessGuard.requireRead(scope: Scope) {
    when (scope) {
        Scope.Global -> require(TasksPermissions.LIBRARY_VIEW)
        is Scope.Project -> if (!canSeeProject(scope.projectId)) require(TasksPermissions.PROJECT_VIEW, scope.projectId)
    }
}

/** Write check for one scope: `library.manage` for global entries, `library.project.manage` on the project otherwise. */
internal inline fun AccessGuard.requireWrite(scope: Scope) {
    when (scope) {
        Scope.Global -> require(TasksPermissions.LIBRARY_MANAGE)
        is Scope.Project -> require(TasksPermissions.LIBRARY_PROJECT_MANAGE, scope.projectId)
    }
}

/**
 * Which parts of a listing the caller sees: global entries with `library.view`, the project's own entries
 * with project visibility. Denies (`library.view`) when neither applies.
 */
internal data class ListingAccess(val global: Boolean, val projectId: UUID?)

internal inline fun AccessGuard.requireListing(projectId: UUID?, principal: CurrentPrincipal): ListingAccess {
    val project = projectId?.takeIf { canSeeProject(it) }
    if (project == null) {
        require(TasksPermissions.LIBRARY_VIEW)
        return ListingAccess(global = true, projectId = null)
    }
    return ListingAccess(global = principal.currentUser().can(TasksPermissions.LIBRARY_VIEW), projectId = project)
}

internal fun ListingAccess.allows(scope: Scope): Boolean = when (scope) {
    Scope.Global -> global
    is Scope.Project -> scope.projectId == projectId
}

internal fun Scope.describe(): String = projectId?.let { "PROJECT($it)" } ?: "GLOBAL"
