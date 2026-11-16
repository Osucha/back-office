package pl.mos.backoffice.llm.usage

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.PermissionSet
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.CurrentPrincipal
import pl.mos.backoffice.shared.security.ModulePermissions
import pl.mos.backoffice.shared.security.PROJECT_SCOPE
import pl.mos.backoffice.shared.security.isNothing
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.util.UUID

/** Permissions of the LLM gateway module (docs/architecture/08-identity-and-access.md, ADR 0017, ADR 0066). */
object LlmPermissions {
    const val MODULE = "llm"

    object Usage : PermissionSet("usage") {
        val VIEW = permission("view", "Read the usage ledger for all projects and benchmarks")
    }

    object ProjectUsage : PermissionSet("project") {
        val VIEW = scoped("usage.view", "Usage and cost of this project", PROJECT_SCOPE)
    }

    /** Read the usage ledger of all projects and benchmarks. */
    val USAGE_VIEW = Usage.VIEW

    /** Read usage and cost of one project (and of its runs). */
    val PROJECT_USAGE_VIEW = ProjectUsage.VIEW

    /** What this module contributes to the catalogue. */
    val contribution = ModulePermissions(MODULE, listOf(Usage, ProjectUsage))
}

/**
 * Read side of the usage ledger behind `/api/v1/usage` endpoints (ADR 0017):
 *
 * - no project or run filter: `usage.view`;
 * - `projectId` filter: `project.usage.view` on that project, or `usage.view`;
 * - `runId` filter: `project.usage.view` on the project the run's ledger rows belong to, or `usage.view`.
 *   The query is then narrowed to that project so a run id cannot be used to read another project.
 */
class UsageQueryService(private val ledger: UsageLedger, private val guard: AccessGuard, private val principal: CurrentPrincipal) {

    fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary =
        when (val access = authorise(query)) {
            is Access.Allowed -> ledger.summary(access.query, groupBy)
            Access.Nothing -> UsageSummary(query.from, query.to, groupBy, Money.ZERO, TokenUsage.ZERO, emptyList())
        }

    fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> =
        when (val access = authorise(query)) {
            is Access.Allowed -> ledger.entries(access.query, page, size)
            Access.Nothing -> PageOf(emptyList(), page, size, 0)
        }

    private sealed interface Access {
        data class Allowed(val query: UsageQuery) : Access

        /** The filter matches no ledger row the caller could be refused; answer empty without querying. */
        data object Nothing : Access
    }

    private fun authorise(query: UsageQuery): Access {
        if (principal.currentUser().can(LlmPermissions.USAGE_VIEW)) {
            guard.require(LlmPermissions.USAGE_VIEW)
            return Access.Allowed(query)
        }
        query.projectId?.let { projectId ->
            guard.require(LlmPermissions.PROJECT_USAGE_VIEW, projectId)
            return Access.Allowed(query)
        }
        query.runId?.let { runId ->
            val projectId = projectOfRun(runId)
            if (projectId != null) {
                guard.require(LlmPermissions.PROJECT_USAGE_VIEW, projectId)
                return Access.Allowed(query.copy(projectId = projectId))
            }
            // No ledger row for the run: nothing to reveal to a caller who reads usage of some project.
            val projects = guard.scopesWith(LlmPermissions.PROJECT_USAGE_VIEW)
            if (!projects.isNothing) return Access.Nothing
        }
        guard.require(LlmPermissions.USAGE_VIEW)
        return Access.Allowed(query)
    }

    private fun projectOfRun(runId: UUID): UUID? =
        ledger.entries(UsageQuery(from = null, to = null, runId = runId), 0, 1).content.firstOrNull()?.context?.projectId
}
