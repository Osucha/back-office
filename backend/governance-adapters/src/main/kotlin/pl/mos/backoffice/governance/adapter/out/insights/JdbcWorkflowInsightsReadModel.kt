package pl.mos.backoffice.governance.adapter.out.insights

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.governance.application.port.out.WorkflowInsightsReadModel
import pl.mos.backoffice.governance.domain.model.DistributionStat
import pl.mos.backoffice.governance.domain.model.NodeMetrics
import pl.mos.backoffice.governance.domain.model.PortCount
import pl.mos.backoffice.governance.domain.model.ProjectWorkflowMetrics
import pl.mos.backoffice.governance.domain.model.VersionMetrics
import pl.mos.backoffice.governance.domain.model.WeeklyOutcome
import pl.mos.backoffice.governance.domain.model.WorkflowInsights
import pl.mos.backoffice.governance.domain.model.WorkflowMetrics
import pl.mos.backoffice.governance.domain.model.WorkflowRunCounts
import pl.mos.backoffice.governance.domain.model.WorkflowScope
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.security.ProjectFilter
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Workflow metrics (ADR 0037) computed with SQL over the tables of other contexts (a read model; nothing is written):
 * `workflow_run` (its library columns and the `nodes` JSONB array of node executions) and `usage_record` (cost and
 * tokens of the LLM gateway ledger). The scope is the runs **created** in `[from, to)` of the visible projects, of one
 * library workflow (`library_workflow_id`) or one project workflow (`workflow_id`); every query starts from the
 * `runs`, `execs` and `facts` CTEs below. Metric definitions: docs/architecture/11-governance.md.
 */
@Transactional(readOnly = true)
class JdbcWorkflowInsightsReadModel(private val jdbc: JdbcClient) : WorkflowInsightsReadModel {

    override fun projectOf(workflowId: UUID): UUID? =
        jdbc.sql("SELECT project_id FROM workflow_definition WHERE id = :id").param("id", workflowId).query(UUID::class.java).optional().orElse(null)
            ?: jdbc.sql("SELECT project_id FROM workflow_run WHERE workflow_id = :id LIMIT 1").param("id", workflowId).query(UUID::class.java).optional().orElse(null)

    override fun compute(scope: WorkflowScope, projects: ProjectFilter, from: Instant, to: Instant): WorkflowInsights {
        val libraryId = (scope as? WorkflowScope.Library)?.libraryWorkflowId
        val workflowId = (scope as? WorkflowScope.Workflow)?.workflowId
        if (projects is ProjectFilter.Only && projects.projectIds.isEmpty()) {
            return WorkflowInsights(from, to, libraryId, workflowId, WorkflowMetrics.EMPTY, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val q = Scope(projects, from, to, scope)
        val totals = metrics(q, "'all'").values.firstOrNull()?.first ?: WorkflowMetrics.EMPTY
        val versionColumn = if (libraryId != null) "library_version" else "workflow_version"
        val byVersion = metrics(q, "$versionColumn::text")
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { VersionMetrics(it, v.first) } }
            .sortedByDescending { it.version }
        val byProject = if (libraryId == null) {
            emptyList()
        } else {
            metrics(q, "project_id::text").map { (k, v) -> ProjectWorkflowMetrics(UUID.fromString(k), v.second.orEmpty(), v.first) }
                .sortedWith(compareByDescending<ProjectWorkflowMetrics> { it.metrics.runs.total }.thenBy { it.projectKey })
        }
        return WorkflowInsights(from, to, libraryId, workflowId, totals, byVersion, byProject, nodes(q), weekly(q))
    }

    override fun libraryHeadlines(projects: ProjectFilter, from: Instant, to: Instant): Map<UUID, WorkflowMetrics> {
        if (projects is ProjectFilter.Only && projects.projectIds.isEmpty()) return emptyMap()
        return metrics(Scope(projects, from, to, null), "library_workflow_id::text").map { (k, v) -> UUID.fromString(k) to v.first }.toMap()
    }

    /** Metrics per value of [group] (an expression over `facts`); the pair's second value is a project key. */
    private fun metrics(q: Scope, group: String): Map<String, Pair<WorkflowMetrics, String?>> = q.query(
        """
        SELECT $group AS grp, max(project_key) AS project_key,
               count(*) AS total,
               count(*) FILTER (WHERE successful) AS successful,
               count(*) FILTER (WHERE failed) AS failed,
               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
               count(*) FILTER (WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) AS active,
               count(*) FILTER (WHERE library_modified) AS modified,
               count(*) FILTER (WHERE successful AND rework = 0) AS first_pass,
               coalesce(sum(rework), 0) AS rework,
               ${distribution("secs", "d")},
               ${distribution("CASE WHEN secs IS NOT NULL THEN greatest(secs - wait_secs, 0) END", "a")},
               ${distribution("CASE WHEN gates > 0 THEN human_secs END", "h")},
               coalesce(sum(cost), 0) AS cost,
               coalesce(sum(cost) FILTER (WHERE successful), 0) AS success_cost,
               coalesce(sum(input_tokens), 0) AS input_tokens,
               coalesce(sum(output_tokens), 0) AS output_tokens,
               count(*) FILTER (WHERE has_mr) AS merge_requests
          FROM facts
         GROUP BY 1
        """,
    ) { rs, _ ->
        val counts = WorkflowRunCounts(rs.getInt("total"), rs.getInt("successful"), rs.getInt("failed"), rs.getInt("cancelled"), rs.getInt("active"), rs.getInt("modified"))
        val cost = rs.getBigDecimal("cost")
        val metrics = WorkflowMetrics(
            runs = counts,
            successRate = rate(counts.successful, counts.finished),
            firstPassRate = rate(rs.getInt("first_pass"), counts.finished),
            reworkLoopsPerRun = rate(rs.getInt("rework"), counts.total),
            duration = distribution(rs, "d"),
            activeTime = distribution(rs, "a"),
            humanWait = distribution(rs, "h"),
            totalCost = money(cost),
            costPerRun = perUnit(cost, counts.total),
            costPerSuccessfulRun = perUnit(rs.getBigDecimal("success_cost"), counts.successful),
            inputTokens = rs.getLong("input_tokens"),
            outputTokens = rs.getLong("output_tokens"),
            mergeRequests = rs.getInt("merge_requests"),
        )
        (rs.getString("grp") ?: "") to (metrics to rs.getString("project_key"))
    }.filter { it.first.isNotEmpty() }.toMap()

    /** Per node: executions, results, durations of finished executions, cost and the ports taken; in path order. */
    private fun nodes(q: Scope): List<NodeMetrics> {
        val ports = q.query(
            """
            SELECT node_id, lower(port) AS port, count(*) AS n FROM execs WHERE port IS NOT NULL GROUP BY 1, 2 ORDER BY 1, n DESC, 2
            """,
        ) { rs, _ -> rs.getString("node_id") to PortCount(rs.getString("port"), rs.getInt("n")) }
            .groupBy({ it.first }, { it.second })
        return q.query(
            """
            SELECT node_id, max(node_name) AS node_name, max(kind) AS kind, max(template_key) AS template_key,
                   count(*) AS executions,
                   count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   ${distribution("CASE WHEN started_at IS NOT NULL AND finished_at IS NOT NULL THEN extract(epoch FROM finished_at - started_at)::double precision END", "d")},
                   coalesce(sum(cost), 0) AS cost, min(idx) AS first_idx
              FROM execs
             WHERE kind <> 'NOTE'
             GROUP BY node_id
             ORDER BY avg(idx), node_id
            """,
        ) { rs, _ ->
            val id = rs.getString("node_id")
            NodeMetrics(
                nodeId = id,
                nodeName = rs.getString("node_name") ?: id,
                kind = rs.getString("kind") ?: "TASK",
                templateKey = rs.getString("template_key"),
                executions = rs.getInt("executions"),
                succeeded = rs.getInt("succeeded"),
                failed = rs.getInt("failed"),
                duration = distribution(rs, "d"),
                cost = money(rs.getBigDecimal("cost")),
                ports = ports[id].orEmpty(),
            )
        }
    }

    /** Successful and failed runs and their cost by ISO week (UTC) of the end of the run. */
    private fun weekly(q: Scope): List<WeeklyOutcome> = q.query(
        """
        SELECT date_trunc('week', finished_at AT TIME ZONE 'UTC')::date AS wk,
               count(*) FILTER (WHERE successful) AS successful, count(*) FILTER (WHERE failed) AS failed, coalesce(sum(cost), 0) AS cost
          FROM facts WHERE finished_at IS NOT NULL
         GROUP BY 1 ORDER BY 1
        """,
    ) { rs, _ -> WeeklyOutcome(rs.getObject("wk", LocalDate::class.java), rs.getInt("successful"), rs.getInt("failed"), money(rs.getBigDecimal("cost"))) }

    /**
     * Binds the window, the project filter and the scope; prepends the CTEs to every query:
     * - `runs`: the runs in scope;
     * - `execs`: one row per node execution (with its position `idx` in the run);
     * - `facts`: one row per run with its result, duration, waits, rework, ledger cost and tokens.
     */
    private inner class Scope(private val projects: ProjectFilter, from: Instant, to: Instant, private val scope: WorkflowScope?) {
        private val fromParam = OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
        private val toParam = OffsetDateTime.ofInstant(to, ZoneOffset.UTC)

        private val scopeClause: String = when (scope) {
            is WorkflowScope.Library -> "AND r.library_workflow_id = :scopeId"
            is WorkflowScope.Workflow -> "AND r.workflow_id = :scopeId"
            null -> "AND r.library_workflow_id IS NOT NULL"
        }

        private val projectClause: String = if (projects is ProjectFilter.Only) "AND r.project_id IN (:projectIds)" else ""

        fun <T : Any> query(sql: String, mapper: (ResultSet, Int) -> T): List<T> {
            val text = """
                WITH runs AS (
                    SELECT r.* FROM workflow_run r
                     WHERE r.created_at >= :from AND r.created_at < :to $scopeClause $projectClause
                ), execs AS (
                    SELECT r.id AS run_id, n.idx, n.doc->>'nodeId' AS node_id, n.doc->>'nodeName' AS node_name, n.doc->>'kind' AS kind,
                           n.doc->>'templateKey' AS template_key, coalesce((n.doc->>'attempt')::int, 1) AS attempt, n.doc->>'status' AS status,
                           n.doc->>'port' AS port, ${ts("n.doc->'startedAt'")} AS started_at, ${ts("n.doc->'finishedAt'")} AS finished_at,
                           coalesce((n.doc->'cost'->>'amount')::numeric, 0) AS cost
                      FROM runs r CROSS JOIN LATERAL jsonb_array_elements(r.nodes) WITH ORDINALITY AS n(doc, idx)
                ), waits AS (
                    SELECT run_id,
                           coalesce(sum(extract(epoch FROM finished_at - started_at)) FILTER (WHERE kind IN ('HUMAN_GATE', 'WAIT_FOR_CI')), 0)::double precision AS wait_secs,
                           coalesce(sum(extract(epoch FROM finished_at - started_at)) FILTER (WHERE kind = 'HUMAN_GATE'), 0)::double precision AS human_secs,
                           count(*) FILTER (WHERE kind = 'HUMAN_GATE') AS gates
                      FROM execs WHERE started_at IS NOT NULL AND finished_at IS NOT NULL GROUP BY run_id
                ), rework AS (
                    SELECT e.run_id,
                           count(*) FILTER (WHERE e.kind = 'HUMAN_GATE' AND lower(e.port) = 'revise') + count(*) FILTER (WHERE p.status = 'FAILED') AS loops
                      FROM execs e
                      LEFT JOIN execs p ON p.run_id = e.run_id AND p.node_id = e.node_id AND p.attempt = e.attempt - 1
                     GROUP BY e.run_id
                ), ledger AS (
                    SELECT u.run_id, sum(u.cost) AS cost, sum(u.input_tokens) AS input_tokens, sum(u.output_tokens) AS output_tokens
                      FROM usage_record u JOIN runs r ON r.id = u.run_id GROUP BY u.run_id
                ), facts AS (
                    SELECT r.id, r.project_id, r.project_key, r.workflow_version, r.library_workflow_id, r.library_version, r.library_modified,
                           r.status, r.finished_at,
                           (r.status = 'SUCCEEDED' AND r.outcome = 'SUCCESS') AS successful,
                           (r.status = 'FAILED' OR (r.status = 'SUCCEEDED' AND coalesce(r.outcome, '') <> 'SUCCESS')) AS failed,
                           CASE WHEN r.finished_at IS NOT NULL AND r.status <> 'CANCELLED'
                                THEN greatest(extract(epoch FROM r.finished_at - coalesce(r.started_at, r.created_at)), 0)::double precision END AS secs,
                           coalesce(w.wait_secs, 0) AS wait_secs, coalesce(w.human_secs, 0) AS human_secs, coalesce(w.gates, 0) AS gates,
                           coalesce(rw.loops, 0) AS rework,
                           coalesce(l.cost, 0) AS cost, coalesce(l.input_tokens, 0) AS input_tokens, coalesce(l.output_tokens, 0) AS output_tokens,
                           coalesce(r.merge_request->>'url', '') <> '' AS has_mr
                      FROM runs r
                      LEFT JOIN waits w ON w.run_id = r.id
                      LEFT JOIN rework rw ON rw.run_id = r.id
                      LEFT JOIN ledger l ON l.run_id = r.id
                )
            """.trimIndent() + "\n" + sql.trimIndent().trim()
            var spec = jdbc.sql(text).param("from", fromParam).param("to", toParam)
            when (scope) {
                is WorkflowScope.Library -> spec = spec.param("scopeId", scope.libraryWorkflowId)
                is WorkflowScope.Workflow -> spec = spec.param("scopeId", scope.workflowId)
                null -> Unit
            }
            if (projects is ProjectFilter.Only) spec = spec.param("projectIds", projects.projectIds.toList())
            return spec.query(mapper).list()
        }
    }

    private fun distribution(rs: ResultSet, prefix: String): DistributionStat {
        val count = rs.getInt("${prefix}_n")
        if (count == 0) return DistributionStat.EMPTY
        return DistributionStat(round(rs.getDouble("${prefix}_p50")), round(rs.getDouble("${prefix}_p90")), round(rs.getDouble("${prefix}_total")), count)
    }

    private fun rate(part: Int, whole: Int): Double? =
        if (whole == 0) null else BigDecimal(part).divide(BigDecimal(whole), 4, RoundingMode.HALF_UP).toDouble()

    private fun perUnit(amount: BigDecimal, units: Int): Money = if (units == 0) Money.ZERO else Money(amount).rounded() / units

    private fun money(amount: BigDecimal?): Money = Money(amount ?: BigDecimal.ZERO).rounded()

    private fun round(value: Double): Double = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).toDouble()

    companion object {
        /** p50 / p90 / total / count of [expr] (nulls ignored) as `<prefix>_p50`, `_p90`, `_total`, `_n`. */
        private fun distribution(expr: String, prefix: String): String =
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY $expr) AS ${prefix}_p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY $expr) AS ${prefix}_p90, " +
                "coalesce(sum($expr), 0) AS ${prefix}_total, count($expr) AS ${prefix}_n"

        private fun ts(expr: String): String = JdbcDeliveryInsightsReadModel.ts(expr)
    }
}
