package pl.mos.backoffice.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.llm.usage.LlmPermissions
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.shared.security.PermissionModules
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.permissionScope
import pl.mos.backoffice.tasks.application.TasksPermissions

/**
 * Permission keys are stored in the database (roles, grants, token permission subsets) and used by the
 * frontend, so they must never change by accident (ADR 0066). [EXPECTED] is the catalogue as it was before
 * the move to port-permissions: key, scope, contributing module and description.
 */
class PermissionCatalogKeysTest {

    @Test
    fun `the catalogue keeps every key, scope, module and description`() {
        assertThat(rows(PermissionModules(ALL_MODULES))).containsExactlyInAnyOrderElementsOf(EXPECTED)
    }

    @Test
    fun `the catalogue is ordered by module, then key`() {
        val keys = PermissionModules(ALL_MODULES).definitions().map { it.key }
        assertThat(keys).isEqualTo(EXPECTED.sortedWith(compareBy({ it.module }, { it.key })).map { it.key })
    }

    data class Row(val key: String, val scope: PermissionScope, val module: String, val description: String)

    companion object {
        val ALL_MODULES = listOf(
            ProjectsPermissions.contribution, TasksPermissions.contribution, EvaluationPermissions.contribution,
            IdentityPermissions.contribution, RunnerPermissions.contribution, LlmPermissions.contribution,
        )

        fun rows(modules: PermissionModules): List<Row> =
            modules.definitions().map { Row(it.key, it.permissionScope, modules.moduleOf(it), it.description) }

        val EXPECTED = listOf(
            Row("admin.audit.view", PermissionScope.PLATFORM, "identity", "Read the audit log"),
            Row("admin.connections.manage", PermissionScope.PLATFORM, "projects", "create, edit, verify and delete platform connections (Jira credentials, ADR 0030)"),
            Row("admin.projects.all", PermissionScope.PLATFORM, "identity", "Every project permission on every project"),
            Row("admin.roles.manage", PermissionScope.PLATFORM, "identity", "Edit roles and platform grants"),
            Row("admin.settings.manage", PermissionScope.PLATFORM, "identity", "Platform settings (registration mode, domains)"),
            Row("admin.users.manage", PermissionScope.PLATFORM, "identity", "Invite, edit and deactivate users; manage groups"),
            Row("admin.users.view", PermissionScope.PLATFORM, "identity", "List users, groups and memberships"),
            Row("ci.execute", PermissionScope.PROJECT, "projects", "granted only to CI tokens: start CI executions"),
            Row("ci.signal", PermissionScope.PROJECT, "projects", "granted only to CI tokens: send signals"),
            Row("connections.personal", PermissionScope.PLATFORM, "projects", "create and use own personal connections (Jira as yourself, ADR 0035)"),
            Row("evaluation.run", PermissionScope.PLATFORM, "evaluation", "Create suites and tasks; create, start and cancel benchmarks"),
            Row("evaluation.view", PermissionScope.PLATFORM, "evaluation", "Read suites, benchmarks and leaderboards"),
            Row("fleet.manage", PermissionScope.PLATFORM, "runner", "Manage runner pools, registration tokens, runners and agent jobs"),
            Row("fleet.personal", PermissionScope.PLATFORM, "runner", "Connect your own computer as a personal runner for your private projects"),
            Row("fleet.view", PermissionScope.PLATFORM, "runner", "See runner pools, runners and agent jobs"),
            Row("integrations.view", PermissionScope.PLATFORM, "projects", "see integration status, platform connections and run connection tests"),
            Row("library.manage", PermissionScope.PLATFORM, "tasks", "Create and edit global templates, skills and rules"),
            Row("library.project.manage", PermissionScope.PROJECT, "tasks", "Create and edit project-scoped templates, skills and rules"),
            Row("library.view", PermissionScope.PLATFORM, "tasks", "Read the global task library"),
            Row("models.manage", PermissionScope.PLATFORM, "evaluation", "Create and edit model profiles and pricing, test a profile"),
            Row("models.view", PermissionScope.PLATFORM, "evaluation", "Read model profiles"),
            Row("project.ci.manage", PermissionScope.PROJECT, "projects", "create/revoke CI tokens"),
            Row("project.connections.manage", PermissionScope.PROJECT, "projects", "create, edit, verify and delete the project connections (owners only, ADR 0030)"),
            Row("project.manage", PermissionScope.PROJECT, "projects", "edit project settings, CI entrypoints, delete the project"),
            Row("project.members.manage", PermissionScope.PROJECT, "identity", "Add/remove members and change their project roles"),
            Row("project.usage.view", PermissionScope.PROJECT, "llm", "Usage and cost of this project"),
            Row("project.view", PermissionScope.PROJECT, "projects", "see the project, its workflows, runs, CI executions"),
            Row("projects.create", PermissionScope.PLATFORM, "projects", "create projects (creator becomes OWNER)"),
            Row("projects.create.personal", PermissionScope.PLATFORM, "projects", "create private projects of your own (ADR 0038)"),
            Row("run.approve", PermissionScope.PROJECT, "projects", "decide human gates (subject to the gate's own approver rule)"),
            Row("run.assign", PermissionScope.PROJECT, "projects", "assign or reassign who decides an open human gate"),
            Row("run.cancel", PermissionScope.PROJECT, "projects", "cancel runs"),
            Row("run.signal", PermissionScope.PROJECT, "projects", "send a manual CI signal to a waiting run"),
            Row("run.start", PermissionScope.PROJECT, "projects", "start and retry runs"),
            Row("tokens.personal", PermissionScope.PLATFORM, "identity", "Create own personal access tokens"),
            Row("usage.view", PermissionScope.PLATFORM, "llm", "Read the usage ledger for all projects and benchmarks"),
            Row("workflow.edit", PermissionScope.PROJECT, "projects", "create/edit/delete/duplicate workflows"),
        )
    }
}
