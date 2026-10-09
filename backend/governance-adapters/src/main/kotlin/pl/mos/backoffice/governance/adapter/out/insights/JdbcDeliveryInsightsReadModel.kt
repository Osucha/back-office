package pl.mos.backoffice.governance.adapter.out.insights

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.governance.application.port.out.DeliveryInsightsReadModel
import pl.mos.backoffice.governance.domain.model.AgentSuccess
import pl.mos.backoffice.governance.domain.model.Bottleneck
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.DistributionStat
import pl.mos.backoffice.governance.domain.model.GateDecisionCount
import pl.mos.backoffice.governance.domain.model.ModelMix
import pl.mos.backoffice.governance.domain.model.RunCounts
import pl.mos.backoffice.governance.domain.model.WeeklyThroughput
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.security.ProjectFilter
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Delivery insights computed with SQL over the tables of other contexts (a read model; nothing is written):
 * - `workflow_run` (+ its `nodes` JSONB array of node executions) of the Projects context,
 * - `approval_request` (gate decisions) of the Projects context,
 * - `usage_record` (the cost ledger) of the LLM gateway.
 * The scope is the runs **created** in `[from, to)` of the visible projects; every metric starts from the
 * `runs` CTE below. Metric definitions: docs/architecture/11-governance.md.
 */
@Transactional(readOnly = true)
class JdbcDeliveryInsightsReadModel(private val jdbc: JdbcClient) : DeliveryInsightsReadModel {

    override fun compute(projects: ProjectFilter, from: Instant, to: Instant): DeliveryInsights {
        if (projects is ProjectFilter.Only && projects.projectIds.isEmpty()) return empty(from, to)
        val scope = Scope(projects, from, to)

        val runs = scope.query(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
              FROM runs
            """,
        ) { rs, _ -> RunCounts(rs.getInt("total"), rs.getInt("succeeded"), rs.getInt("failed"), rs.getInt("cancelled")) }.single()

        val waits = scope.query(
            """
            SELECT kind, $DISTRIBUTION
              FROM (SELECT kind, extract(epoch FROM finished_at - started_at)::double precision AS secs
                      FROM execs WHERE kind IN ('HUMAN_GATE', 'WAIT_FOR_CI') AND started_at IS NOT NULL AND finished_at IS NOT NULL) w
             GROUP BY kind
            """,
        ) { rs, _ -> rs.getString("kind") to distribution(rs) }.toMap()

        val leadTime = scope.query(
            """
            , issue_first AS (
                SELECT r.project_id, r.issue_key, min(coalesce(r.started_at, r.created_at)) AS first_start
                  FROM workflow_run r WHERE r.issue_key IS NOT NULL ${scope.projectClause("r")}
                 GROUP BY r.project_id, r.issue_key)
            , mr AS (
                SELECT r.project_id, r.issue_key,
                       min(coalesce((SELECT min(${ts("n->'finishedAt'")}) FROM jsonb_array_elements(r.nodes) n
                                      WHERE n->>'kind' = 'TASK' AND n->>'status' = 'SUCCEEDED' AND n->>'runnerJobId' IS NOT NULL),
                                    r.finished_at, r.created_at)) AS mr_at
                  FROM runs r
                 WHERE r.issue_key IS NOT NULL AND coalesce(r.merge_request->>'url', '') <> ''
                 GROUP BY r.project_id, r.issue_key)
            SELECT $DISTRIBUTION
              FROM (SELECT greatest(extract(epoch FROM mr.mr_at - f.first_start), 0)::double precision AS secs
                      FROM mr JOIN issue_first f ON f.project_id = mr.project_id AND f.issue_key = mr.issue_key) l
            """,
        ) { rs, _ -> distribution(rs) }.single()

        val rework = scope.query(
            """
            SELECT (SELECT count(*) FROM approval_request a JOIN runs r ON r.id = a.run_id
                     WHERE a.status = 'DECIDED' AND lower(a.decision->>'option') = 'revise') AS revisions,
                   (SELECT count(*) FROM execs e
                     WHERE e.attempt > 1 AND EXISTS (SELECT 1 FROM execs p WHERE p.run_id = e.run_id AND p.node_id = e.node_id
                                                        AND p.attempt = e.attempt - 1 AND p.status = 'FAILED')) AS retries
            """,
        ) { rs, _ -> rs.getLong("revisions") + rs.getLong("retries") }.single()

        val costs = scope.query(
            """
            , run_cost AS (SELECT u.run_id, sum(u.cost) AS cost FROM usage_record u JOIN runs r ON r.id = u.run_id GROUP BY u.run_id)
            SELECT coalesce(sum(rc.cost), 0) AS total,
                   coalesce(sum(rc.cost) FILTER (WHERE r.status = 'SUCCEEDED' AND r.outcome = 'SUCCESS'), 0) AS success_cost,
                   count(*) FILTER (WHERE r.status = 'SUCCEEDED' AND r.outcome = 'SUCCESS') AS success_runs,
                   coalesce(sum(rc.cost) FILTER (WHERE coalesce(r.merge_request->>'url', '') <> ''), 0) AS mr_cost,
                   count(*) FILTER (WHERE coalesce(r.merge_request->>'url', '') <> '') AS mr_runs
              FROM runs r LEFT JOIN run_cost rc ON rc.run_id = r.id
            """,
        ) { rs, _ ->
            Costs(rs.getBigDecimal("total"), rs.getBigDecimal("success_cost"), rs.getInt("success_runs"), rs.getBigDecimal("mr_cost"), rs.getInt("mr_runs"))
        }.single()

        val byTemplate = scope.query(
            """
            SELECT coalesce(template_key, node_id) AS k, count(*) AS jobs, count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded, coalesce(sum(cost), 0) AS cost
              FROM execs WHERE kind = 'TASK' AND status IN ('SUCCEEDED', 'FAILED')
             GROUP BY 1 ORDER BY jobs DESC, k
            """,
            ::agentSuccess,
        )

        val byModel = scope.query(
            """
            , step_model AS (
                SELECT DISTINCT ON (u.run_id, u.step_id) u.run_id, u.step_id, u.model_label
                  FROM usage_record u JOIN runs r ON r.id = u.run_id
                 WHERE u.step_id IS NOT NULL
                 GROUP BY u.run_id, u.step_id, u.model_label
                 ORDER BY u.run_id, u.step_id, sum(u.cost) DESC, count(*) DESC, u.model_label)
            SELECT coalesce(sm.model_label, 'unknown') AS k, count(*) AS jobs, count(*) FILTER (WHERE e.status = 'SUCCEEDED') AS succeeded,
                   coalesce(sum(e.cost), 0) AS cost
              FROM execs e LEFT JOIN step_model sm ON sm.run_id = e.run_id AND sm.step_id = e.node_id
             WHERE e.kind = 'TASK' AND e.status IN ('SUCCEEDED', 'FAILED')
             GROUP BY 1 ORDER BY jobs DESC, k
            """,
            ::agentSuccess,
        )

        val decisions = scope.query(
            """
            SELECT lower(a.decision->>'option') AS opt, count(*) AS n
              FROM approval_request a JOIN runs r ON r.id = a.run_id
             WHERE a.status = 'DECIDED' AND a.decision IS NOT NULL
             GROUP BY 1 ORDER BY n DESC, opt
            """,
        ) { rs, _ -> GateDecisionCount(rs.getString("opt"), rs.getInt("n")) }

        val weekly = weekly(scope)

        val bottleneck = scope.query(
            """
            SELECT node_id, max(node_name) AS node_name, workflow_name,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY secs) AS p90
              FROM (SELECT node_id, node_name, workflow_name, extract(epoch FROM finished_at - started_at)::double precision AS secs
                      FROM execs WHERE kind = 'HUMAN_GATE' AND started_at IS NOT NULL AND finished_at IS NOT NULL) g
             GROUP BY workflow_name, node_id
             ORDER BY p90 DESC, workflow_name, node_id
             LIMIT 1
            """,
        ) { rs, _ -> Bottleneck(rs.getString("node_id"), rs.getString("node_name"), rs.getString("workflow_name"), rs.getDouble("p90")) }.firstOrNull()

        val modelMix = scope.query(
            """
            SELECT u.model_label, count(*) AS calls, coalesce(sum(u.input_tokens), 0) AS input_tokens,
                   coalesce(sum(u.output_tokens), 0) AS output_tokens, coalesce(sum(u.cost), 0) AS cost
              FROM usage_record u JOIN runs r ON r.id = u.run_id
             GROUP BY u.model_label ORDER BY cost DESC, u.model_label
            """,
        ) { rs, _ -> ModelMix(rs.getString("model_label"), rs.getLong("calls"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), money(rs.getBigDecimal("cost"))) }

        return DeliveryInsights(
            from = from,
            to = to,
            projectId = null,
            leadTime = leadTime,
            humanWait = waits["HUMAN_GATE"] ?: DistributionStat.EMPTY,
            ciWait = waits["WAIT_FOR_CI"] ?: DistributionStat.EMPTY,
            runs = runs,
            reworkLoopsPerRun = if (runs.total == 0) null else BigDecimal(rework).divide(BigDecimal(runs.total), 4, RoundingMode.HALF_UP).toDouble(),
            costPerSuccessfulRun = perUnit(costs.successCost, costs.successRuns),
            costPerMergeRequest = perUnit(costs.mrCost, costs.mrRuns),
            totalCost = money(costs.total),
            agentSuccessByTemplate = byTemplate,
            agentSuccessByModel = byModel,
            gateDecisions = decisions,
            weekly = weekly,
            bottleneck = bottleneck,
            modelMix = modelMix,
        )
    }

    private fun weekly(scope: Scope): List<WeeklyThroughput> {
        data class Week(var succeeded: Int = 0, var failed: Int = 0, var waitSeconds: Double = 0.0, var cost: BigDecimal = BigDecimal.ZERO)
        val weeks = sortedMapOf<LocalDate, Week>()
        scope.query(
            """
            SELECT date_trunc('week', finished_at AT TIME ZONE 'UTC')::date AS wk,
                   count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded, count(*) FILTER (WHERE status = 'FAILED') AS failed
              FROM runs WHERE finished_at IS NOT NULL GROUP BY 1
            """,
        ) { rs, _ -> weeks.getOrPut(rs.getObject("wk", LocalDate::class.java)) { Week() }.apply { succeeded = rs.getInt("succeeded"); failed = rs.getInt("failed") } }
        scope.query(
            """
            SELECT date_trunc('week', finished_at AT TIME ZONE 'UTC')::date AS wk, sum(extract(epoch FROM finished_at - started_at))::double precision AS secs
              FROM execs WHERE kind = 'HUMAN_GATE' AND started_at IS NOT NULL AND finished_at IS NOT NULL GROUP BY 1
            """,
        ) { rs, _ -> weeks.getOrPut(rs.getObject("wk", LocalDate::class.java)) { Week() }.apply { waitSeconds = rs.getDouble("secs") } }
        scope.query(
            """
            SELECT date_trunc('week', u.occurred_at AT TIME ZONE 'UTC')::date AS wk, sum(u.cost) AS cost
              FROM usage_record u JOIN runs r ON r.id = u.run_id GROUP BY 1
            """,
        ) { rs, _ -> weeks.getOrPut(rs.getObject("wk", LocalDate::class.java)) { Week() }.apply { cost = rs.getBigDecimal("cost") } }
        return weeks.map { (week, w) ->
            WeeklyThroughput(week, w.succeeded, w.failed, BigDecimal(w.waitSeconds / 3600.0).setScale(2, RoundingMode.HALF_UP).toDouble(), money(w.cost))
        }
    }

    private data class Costs(val total: BigDecimal, val successCost: BigDecimal, val successRuns: Int, val mrCost: BigDecimal, val mrRuns: Int)

    /** Binds the window and the project filter; prepends the `runs` and `execs` CTEs to every query. */
    private inner class Scope(private val projects: ProjectFilter, from: Instant, to: Instant) {
        private val fromParam = OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
        private val toParam = OffsetDateTime.ofInstant(to, ZoneOffset.UTC)

        fun projectClause(alias: String): String = if (projects is ProjectFilter.Only) "AND $alias.project_id IN (:projectIds)" else ""

        /** [sql] either starts with `SELECT` or with `, name AS (...)` to add CTEs after `runs` and `execs`. */
        fun <T : Any> query(sql: String, mapper: (ResultSet, Int) -> T): List<T> {
            val body = sql.trimIndent().trim()
            val text = """
                WITH runs AS (
                    SELECT r.* FROM workflow_run r
                     WHERE r.created_at >= :from AND r.created_at < :to ${projectClause("r")}
                ), execs AS (
                    SELECT r.id AS run_id, r.project_id, r.workflow_name, r.status AS run_status,
                           n->>'nodeId' AS node_id, n->>'nodeName' AS node_name, n->>'kind' AS kind, n->>'templateKey' AS template_key,
                           coalesce((n->>'attempt')::int, 1) AS attempt, n->>'status' AS status,
                           ${ts("n->'startedAt'")} AS started_at, ${ts("n->'finishedAt'")} AS finished_at,
                           coalesce((n->'cost'->>'amount')::numeric, 0) AS cost, n->>'runnerJobId' AS runner_job_id
                      FROM runs r CROSS JOIN LATERAL jsonb_array_elements(r.nodes) n
                )
            """.trimIndent() + (if (body.startsWith(",")) "\n" else "\n") + body
            var spec = jdbc.sql(text).param("from", fromParam).param("to", toParam)
            if (projects is ProjectFilter.Only) spec = spec.param("projectIds", projects.projectIds.toList())
            return spec.query(mapper).list()
        }
    }

    private fun distribution(rs: ResultSet): DistributionStat {
        val count = rs.getInt("n")
        if (count == 0) return DistributionStat.EMPTY
        return DistributionStat(round(rs.getDouble("p50")), round(rs.getDouble("p90")), round(rs.getDouble("total")), count)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun agentSuccess(rs: ResultSet, row: Int) = AgentSuccess(rs.getString("k"), rs.getInt("jobs"), rs.getInt("succeeded"), money(rs.getBigDecimal("cost")))

    private fun perUnit(amount: BigDecimal, units: Int): Money = if (units == 0) Money.ZERO else Money(amount).rounded() / units

    private fun money(amount: BigDecimal?): Money = Money(amount ?: BigDecimal.ZERO).rounded()

    private fun round(value: Double): Double = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).toDouble()

    private fun empty(from: Instant, to: Instant) = DeliveryInsights(
        from, to, null, DistributionStat.EMPTY, DistributionStat.EMPTY, DistributionStat.EMPTY, RunCounts(0, 0, 0, 0), null,
        Money.ZERO, Money.ZERO, Money.ZERO, emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(),
    )

    companion object {
        /** p50 / p90 / total / count over a `secs` column. */
        private const val DISTRIBUTION =
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY secs) AS p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY secs) AS p90, " +
                "coalesce(sum(secs), 0) AS total, count(secs) AS n"

        /** A JSONB timestamp written by Jackson: ISO-8601 string (default) or epoch seconds (numeric). */
        fun ts(expr: String): String =
            "(CASE WHEN jsonb_typeof($expr) = 'number' THEN to_timestamp(($expr)::text::double precision) ELSE ($expr #>> '{}')::timestamptz END)"
    }
}
