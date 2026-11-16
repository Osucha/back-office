package pl.mos.backoffice.projects.domain.model

import com.osucha.portpermissions.PermissionSet
import pl.mos.backoffice.shared.security.LibraryAccessPermissions
import pl.mos.backoffice.shared.security.ModulePermissions
import pl.mos.backoffice.shared.security.PROJECT_SCOPE
import pl.mos.backoffice.shared.security.ProjectAccessPermissions
import pl.mos.backoffice.shared.security.ProjectMembershipPermissions

/**
 * Permission catalogue of the Projects & Workflows context (docs/architecture/08-identity-and-access.md,
 * ADR 0017, ADR 0066). Code is the source of truth; the identity context collects [contribution] (a
 * `ModulePermissions` bean) and stores only grants.
 */
object ProjectsPermissions {
    const val MODULE = "projects"

    object Projects : PermissionSet("projects") {
        val CREATE = permission("create", "create projects (creator becomes OWNER)")
        val CREATE_PERSONAL = permission("create.personal", "create private projects of your own (ADR 0038)")
    }

    object Integrations : PermissionSet("integrations") {
        val VIEW = permission("view", "see integration status, platform connections and run connection tests")
    }

    object AdminConnections : PermissionSet("admin") {
        val MANAGE = permission("connections.manage", "create, edit, verify and delete platform connections (Jira credentials, ADR 0030)")
    }

    object Connections : PermissionSet("connections") {
        val PERSONAL = permission("personal", "create and use own personal connections (Jira as yourself, ADR 0035)")
    }

    object Project : PermissionSet("project") {
        val CI_MANAGE = scoped("ci.manage", "create/revoke CI tokens", PROJECT_SCOPE)
        val CONNECTIONS_MANAGE = scoped(
            "connections.manage", "create, edit, verify and delete the project connections (owners only, ADR 0030)", PROJECT_SCOPE,
        )
    }

    object Workflow : PermissionSet("workflow") {
        val EDIT = scoped("edit", "create/edit/delete/duplicate workflows", PROJECT_SCOPE)
    }

    object Run : PermissionSet("run") {
        val START = scoped("start", "start and retry runs", PROJECT_SCOPE)
        val CANCEL = scoped("cancel", "cancel runs", PROJECT_SCOPE)
        val APPROVE = scoped("approve", "decide human gates (subject to the gate's own approver rule)", PROJECT_SCOPE)
        val ASSIGN = scoped("assign", "assign or reassign who decides an open human gate", PROJECT_SCOPE)
        val SIGNAL = scoped("signal", "send a manual CI signal to a waiting run", PROJECT_SCOPE)
    }

    object Ci : PermissionSet("ci") {
        val EXECUTE = scoped("execute", "granted only to CI tokens: start CI executions", PROJECT_SCOPE)
        val SIGNAL = scoped("signal", "granted only to CI tokens: send signals", PROJECT_SCOPE)
    }

    val PROJECTS_CREATE = Projects.CREATE

    /** ADR 0038: create a private project (the creator is its only member); every member holds it. */
    val PROJECTS_CREATE_PERSONAL = Projects.CREATE_PERSONAL
    val INTEGRATIONS_VIEW = Integrations.VIEW
    val CONNECTIONS_MANAGE = AdminConnections.MANAGE
    val CONNECTIONS_PERSONAL = Connections.PERSONAL
    val PROJECT_VIEW = ProjectAccessPermissions.VIEW
    val PROJECT_MANAGE = ProjectAccessPermissions.MANAGE
    val PROJECT_CI_MANAGE = Project.CI_MANAGE
    val PROJECT_CONNECTIONS_MANAGE = Project.CONNECTIONS_MANAGE
    val WORKFLOW_EDIT = Workflow.EDIT
    val RUN_START = Run.START
    val RUN_CANCEL = Run.CANCEL
    val RUN_APPROVE = Run.APPROVE
    val RUN_ASSIGN = Run.ASSIGN
    val RUN_SIGNAL = Run.SIGNAL
    val CI_EXECUTE = Ci.EXECUTE
    val CI_SIGNAL = Ci.SIGNAL

    /** Contributed by the identity context; checked here only for project deletion (owners). */
    val PROJECT_MEMBERS_MANAGE = ProjectMembershipPermissions.MEMBERS_MANAGE

    /** Contributed by the Task Library context; reading the workflow library (ADR 0037). */
    val LIBRARY_VIEW = LibraryAccessPermissions.VIEW

    /** Contributed by the Task Library context; publishing, curating and deleting library workflows (ADR 0037). */
    val LIBRARY_MANAGE = LibraryAccessPermissions.MANAGE

    /** What this context contributes to the catalogue. */
    val contribution = ModulePermissions(
        MODULE,
        listOf(Projects, Integrations, AdminConnections, Connections, ProjectAccessPermissions, Project, Workflow, Run, Ci),
    )
}
