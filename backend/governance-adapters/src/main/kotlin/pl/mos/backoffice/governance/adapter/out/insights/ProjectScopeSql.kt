package pl.mos.backoffice.governance.adapter.out.insights

import com.osucha.portpermissions.ScopeFilter
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/*
 * The caller's visible projects (`AccessGuard.scopesWith(project.view)`, ADR 0066) as SQL on `<alias>.project_id`:
 * every project -> no condition, some -> `IN`, every project but some -> `NOT IN`. An empty `Only` means nothing is
 * visible; callers return early before querying.
 */

internal fun ScopeFilter<UUID>.projectClause(alias: String): String = when (this) {
    ScopeFilter.All -> ""
    is ScopeFilter.Only -> "AND $alias.project_id IN (:projectIds)"
    is ScopeFilter.AllExcept -> if (excluded.isEmpty()) "" else "AND $alias.project_id NOT IN (:excludedProjectIds)"
}

internal fun JdbcClient.StatementSpec.bindProjects(filter: ScopeFilter<UUID>): JdbcClient.StatementSpec = when (filter) {
    ScopeFilter.All -> this
    is ScopeFilter.Only -> param("projectIds", filter.ids.toList())
    is ScopeFilter.AllExcept -> if (filter.excluded.isEmpty()) this else param("excludedProjectIds", filter.excluded.toList())
}
