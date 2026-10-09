package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.JiraScopes
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.ProjectPermissionCheck
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationMessage
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import java.time.Instant

/**
 * Maps what Jira answered ([JiraProbe]) to a [ConnectionVerification] for the declared capabilities (ADR 0030):
 *
 * - `myself` failed -> FAILED (authentication / site / HTTP error);
 * - per checked project: **missing** = permissions the declared capabilities need but the account lacks (ERROR,
 *   FAILED); **excess** = permissions the account holds beyond what the capabilities need (WARNING: the token can do
 *   more than Back Office should, least privilege is not met);
 * - no project could be checked -> WARNING `NO_PROJECT_TO_CHECK` (only the token was proven).
 *
 * Scoped tokens (ADR 0035, [tokenKind] `SCOPED`): the token's scopes, not the account's permissions, bound what it can
 * do. Account permissions beyond the capabilities are then only INFO; the declared scopes must cover every capability
 * (`MISSING_SCOPES`, ERROR), extra write scopes are `EXCESS_SCOPES` (WARNING), `write:jira-work` is `BROAD_SCOPE`
 * (WARNING), and an endpoint call answered `401` / `403` is `MISSING_SCOPES` (ERROR).
 */
class ConnectionVerificationPolicy {

    fun evaluate(
        capabilities: Set<ConnectionCapability>,
        probe: JiraProbe,
        now: Instant,
        tokenKind: JiraTokenKind = JiraTokenKind.CLASSIC,
        declaredScopes: List<String> = emptyList(),
    ): ConnectionVerification {
        val scoped = tokenKind == JiraTokenKind.SCOPED
        probe.failure?.let { failure ->
            return ConnectionVerification(
                VerificationStatus.FAILED, now, probe.account, emptyList(),
                listOf(VerificationMessage(MessageSeverity.ERROR, failure.code, failure.detail)),
            )
        }
        val needed = ConnectionCapability.jiraPermissionsFor(capabilities)
        val messages = mutableListOf<VerificationMessage>()
        val checks = probe.projects.mapNotNull { project ->
            val granted = project.granted
            if (granted == null) {
                val failure = project.failure
                // a scoped token may lack the scope of mypermissions: the endpoint checks still prove the capabilities
                val unreadable = scoped && failure?.code == VerificationCode.MISSING_SCOPES
                messages += VerificationMessage(
                    if (unreadable) MessageSeverity.WARNING else MessageSeverity.ERROR, failure?.code ?: VerificationCode.HTTP_ERROR,
                    failure?.detail ?: "permissions of Jira project ${project.projectKey} could not be read", project.projectKey,
                )
                return@mapNotNull null
            }
            val missing = needed - granted
            val excess = granted - needed
            if (missing.isNotEmpty()) {
                messages += VerificationMessage(
                    MessageSeverity.ERROR, VerificationCode.MISSING_PERMISSIONS,
                    "in ${project.projectKey} the account lacks ${names(missing)}, needed for ${capabilities.sorted().joinToString()}",
                    project.projectKey, missing.sorted(),
                )
            }
            if (excess.isNotEmpty()) {
                messages += if (scoped) {
                    // the scopes bound the token: the account's other permissions are not reachable unless a scope allows them
                    VerificationMessage(
                        MessageSeverity.INFO, VerificationCode.EXCESS_PERMISSIONS,
                        "in ${project.projectKey} the account can also ${actions(excess)}; the scoped token can only do what its scopes allow",
                        project.projectKey, excess.sorted(),
                    )
                } else {
                    VerificationMessage(
                        MessageSeverity.WARNING, VerificationCode.EXCESS_PERMISSIONS,
                        "over-privileged: in ${project.projectKey} this account can also ${actions(excess)}; " +
                            "use a dedicated account with only ${names(needed)}, or a scoped API token",
                        project.projectKey, excess.sorted(),
                    )
                }
            }
            ProjectPermissionCheck(project.projectKey, granted, missing, excess)
        }
        if (scoped) messages += scopeMessages(capabilities, probe, declaredScopes)
        if (probe.projects.isEmpty()) {
            messages += VerificationMessage(
                MessageSeverity.WARNING, VerificationCode.NO_PROJECT_TO_CHECK,
                "the token is valid, but no Jira project key was given, so permissions were not checked; set allowed projects or a sample project key",
            )
        }
        val status = when {
            messages.any { it.severity == MessageSeverity.ERROR } -> VerificationStatus.FAILED
            messages.any { it.severity == MessageSeverity.WARNING } -> VerificationStatus.WARNING
            else -> VerificationStatus.OK
        }
        if (status == VerificationStatus.OK) {
            messages += VerificationMessage(
                MessageSeverity.INFO, VerificationCode.VERIFIED,
                "authenticated as ${probe.account?.displayName ?: "unknown"}; exactly ${names(needed)} in ${checks.joinToString { it.projectKey }}",
            )
        }
        return ConnectionVerification(status, now, probe.account, checks, messages)
    }

    /** ADR 0035: declared scopes vs. the capabilities, and the endpoint calls of a scoped token. */
    private fun scopeMessages(capabilities: Set<ConnectionCapability>, probe: JiraProbe, declared: List<String>): List<VerificationMessage> {
        val messages = mutableListOf<VerificationMessage>()
        JiraScopes.missing(capabilities, declared).forEach { capability ->
            val options = JiraScopes.options(capability).joinToString(" or ") { it.required.sorted().joinToString(" + ") }
            messages += VerificationMessage(
                MessageSeverity.ERROR, VerificationCode.MISSING_SCOPES,
                "the declared scopes do not cover $capability; the token needs $options",
            )
        }
        JiraScopes.excessWrites(capabilities, declared).takeIf { it.isNotEmpty() }?.let { extra ->
            messages += VerificationMessage(
                MessageSeverity.WARNING, VerificationCode.EXCESS_SCOPES,
                "the token has write scopes no capability needs: ${extra.joinToString()}; create a token with only " +
                    JiraScopes.recommendedGranular(capabilities).joinToString(),
            )
        }
        if (JiraScopes.WRITE_JIRA_WORK in declared && capabilities.any { it.write }) {
            messages += VerificationMessage(
                MessageSeverity.WARNING, VerificationCode.BROAD_SCOPE,
                "write:jira-work also lets the token create, edit and delete issues; prefer the granular " +
                    capabilities.filter { it.write }.flatMap { c -> JiraScopes.options(c).filter { it.kind == JiraScopes.Kind.GRANULAR }.flatMap { it.required } }
                        .filter { JiraScopes.isWrite(it) }.distinct().sorted().joinToString(),
            )
        }
        probe.scopeProbes.filterNot { it.ok }.forEach { p ->
            val denied = p.status == 401 || p.status == 403
            messages += VerificationMessage(
                if (denied) MessageSeverity.ERROR else MessageSeverity.WARNING,
                if (denied) VerificationCode.MISSING_SCOPES else VerificationCode.HTTP_ERROR,
                if (denied) {
                    "Jira refused ${p.endpoint} (HTTP ${p.status}): the token lacks the scopes ${p.capability} needs " +
                        "(${JiraScopes.options(p.capability).joinToString(" or ") { it.required.sorted().joinToString(" + ") }})"
                } else {
                    p.detail ?: "${p.endpoint} could not be checked (HTTP ${p.status ?: "error"})"
                },
            )
        }
        return messages
    }

    companion object {
        /** Jira UI names of the permissions. */
        val DISPLAY_NAMES: Map<JiraPermission, String> = mapOf(
            JiraPermission.BROWSE_PROJECTS to "Browse Projects",
            JiraPermission.ADD_COMMENTS to "Add Comments",
            JiraPermission.TRANSITION_ISSUES to "Transition Issues",
            JiraPermission.EDIT_ISSUES to "Edit Issues",
            JiraPermission.DELETE_ISSUES to "Delete Issues",
            JiraPermission.ASSIGN_ISSUES to "Assign Issues",
            JiraPermission.CREATE_ISSUES to "Create Issues",
            JiraPermission.ADMINISTER_PROJECTS to "Administer Projects",
            JiraPermission.MANAGE_WATCHERS to "Manage Watchers",
        )

        private val ACTIONS: Map<JiraPermission, String> = mapOf(
            JiraPermission.BROWSE_PROJECTS to "read issues",
            JiraPermission.ADD_COMMENTS to "comment",
            JiraPermission.TRANSITION_ISSUES to "transition issues",
            JiraPermission.EDIT_ISSUES to "edit issues",
            JiraPermission.DELETE_ISSUES to "delete issues",
            JiraPermission.ASSIGN_ISSUES to "assign issues",
            JiraPermission.CREATE_ISSUES to "create issues",
            JiraPermission.ADMINISTER_PROJECTS to "administer the project",
            JiraPermission.MANAGE_WATCHERS to "manage watchers",
        )

        fun names(permissions: Collection<JiraPermission>): String = permissions.sorted().joinToString { DISPLAY_NAMES.getValue(it) }

        private fun actions(permissions: Collection<JiraPermission>): String = permissions.sorted().joinToString { ACTIONS.getValue(it) }
    }
}
