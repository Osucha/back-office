package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope

/**
 * Permission catalogue of the Projects & Workflows context (docs/architecture/08-identity-and-access.md,
 * ADR 0017). Code is the source of truth; the identity context collects [definitions] through a
 * `PermissionCatalogContribution` bean and stores only grants.
 */
object ProjectsPermissions {
    const val MODULE = "projects"

    const val PROJECTS_CREATE = "projects.create"
    const val INTEGRATIONS_VIEW = "integrations.view"
    const val CONNECTIONS_MANAGE = "admin.connections.manage"
    const val CONNECTIONS_PERSONAL = "connections.personal"
    const val PROJECT_VIEW = "project.view"
    const val PROJECT_MANAGE = "project.manage"
    const val PROJECT_CI_MANAGE = "project.ci.manage"
    const val PROJECT_CONNECTIONS_MANAGE = "project.connections.manage"
    const val WORKFLOW_EDIT = "workflow.edit"
    const val RUN_START = "run.start"
    const val RUN_CANCEL = "run.cancel"
    const val RUN_APPROVE = "run.approve"
    const val RUN_ASSIGN = "run.assign"
    const val RUN_SIGNAL = "run.signal"
    const val CI_EXECUTE = "ci.execute"
    const val CI_SIGNAL = "ci.signal"

    /** Defined and contributed by the identity context; checked here only for project deletion (owners). */
    const val PROJECT_MEMBERS_MANAGE = "project.members.manage"

    /** Defined by the Task Library context; reading the workflow library (ADR 0037). */
    const val LIBRARY_VIEW = "library.view"

    /** Defined by the Task Library context; publishing, curating and deleting library workflows (ADR 0037). */
    const val LIBRARY_MANAGE = "library.manage"

    val definitions: List<PermissionDefinition> = listOf(
        platform(PROJECTS_CREATE, "create projects (creator becomes OWNER)"),
        platform(INTEGRATIONS_VIEW, "see integration status, platform connections and run connection tests"),
        platform(CONNECTIONS_MANAGE, "create, edit, verify and delete platform connections (Jira credentials, ADR 0030)"),
        platform(CONNECTIONS_PERSONAL, "create and use own personal connections (Jira as yourself, ADR 0035)"),
        project(PROJECT_VIEW, "see the project, its workflows, runs, CI executions"),
        project(PROJECT_MANAGE, "edit project settings, CI entrypoints, delete the project"),
        project(PROJECT_CI_MANAGE, "create/revoke CI tokens"),
        project(PROJECT_CONNECTIONS_MANAGE, "create, edit, verify and delete the project connections (owners only, ADR 0030)"),
        project(WORKFLOW_EDIT, "create/edit/delete/duplicate workflows"),
        project(RUN_START, "start and retry runs"),
        project(RUN_CANCEL, "cancel runs"),
        project(RUN_APPROVE, "decide human gates (subject to the gate's own approver rule)"),
        project(RUN_ASSIGN, "assign or reassign who decides an open human gate"),
        project(RUN_SIGNAL, "send a manual CI signal to a waiting run"),
        project(CI_EXECUTE, "granted only to CI tokens: start CI executions"),
        project(CI_SIGNAL, "granted only to CI tokens: send signals"),
    )

    private fun platform(key: String, description: String) = PermissionDefinition(key, PermissionScope.PLATFORM, MODULE, description)
    private fun project(key: String, description: String) = PermissionDefinition(key, PermissionScope.PROJECT, MODULE, description)
}
