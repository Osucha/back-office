package pl.mos.backoffice.governance.adapter.out.insights

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.governance.application.port.out.AgentInsightsReadModel
import pl.mos.backoffice.governance.domain.model.StepAgent
import pl.mos.backoffice.governance.domain.model.TaskStepExecution
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.security.ProjectFilter
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR 0059: the TASK step executions of the runs **created** in `[from, to)` of the visible projects, read from the
 * `nodes` JSONB array of `workflow_run` (a read model over the Projects tables; nothing is written). The aggregation is
 * the domain's ([pl.mos.backoffice.governance.domain.service.AgentInsightsCalculator]); executions before ADR 0059 have
 * no `agent` and come back with a null agent.
 */
@Transactional(readOnly = true)
class JdbcAgentInsightsReadModel(private val jdbc: JdbcClient) : AgentInsightsReadModel {

    override fun taskSteps(projects: ProjectFilter, from: Instant, to: Instant): List<TaskStepExecution> {
        if (projects is ProjectFilter.Only && projects.projectIds.isEmpty()) return emptyList()
        val projectClause = if (projects is ProjectFilter.Only) "AND r.project_id IN (:projectIds)" else ""
        val started = JdbcDeliveryInsightsReadModel.ts("n.doc->'startedAt'")
        val finished = JdbcDeliveryInsightsReadModel.ts("n.doc->'finishedAt'")
        var spec = jdbc.sql(
            """
            SELECT r.id AS run_id, (r.status = 'SUCCEEDED' AND r.outcome = 'SUCCESS') AS successful, n.idx,
                   n.doc->>'nodeId' AS node_id, n.doc->>'templateKey' AS template_key, coalesce((n.doc->>'attempt')::int, 1) AS attempt,
                   n.doc->>'status' AS status,
                   n.doc->'agent'->>'agentKey' AS agent_key, n.doc->'agent'->>'agentName' AS agent_name, n.doc->'agent'->>'harness' AS harness,
                   n.doc->'agent'->>'modelProfileId' AS model_profile_id, n.doc->'agent'->>'modelLabel' AS model_label,
                   coalesce((n.doc->'cost'->>'amount')::numeric, 0) AS cost,
                   coalesce((n.doc->'apiCost'->>'amount')::numeric, (n.doc->'cost'->>'amount')::numeric, 0) AS api_cost,
                   CASE WHEN $started IS NOT NULL AND $finished IS NOT NULL
                        THEN greatest(extract(epoch FROM $finished - $started), 0)::double precision END AS secs
              FROM workflow_run r CROSS JOIN LATERAL jsonb_array_elements(r.nodes) WITH ORDINALITY AS n(doc, idx)
             WHERE r.created_at >= :from AND r.created_at < :to $projectClause
               AND n.doc->>'kind' = 'TASK'
             ORDER BY r.created_at, r.id, n.idx
            """.trimIndent(),
        ).param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC)).param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
        if (projects is ProjectFilter.Only) spec = spec.param("projectIds", projects.projectIds.toList())
        return spec.query { rs, _ ->
            val agentKey = rs.getString("agent_key")
            TaskStepExecution(
                runId = rs.getObject("run_id", UUID::class.java),
                runSuccessful = rs.getBoolean("successful"),
                nodeId = rs.getString("node_id") ?: "",
                index = rs.getInt("idx"),
                templateKey = rs.getString("template_key"),
                attempt = rs.getInt("attempt"),
                status = rs.getString("status") ?: "",
                agent = agentKey?.let {
                    StepAgent(
                        agentKey = it,
                        agentName = rs.getString("agent_name") ?: it,
                        harness = rs.getString("harness"),
                        modelProfileId = rs.getString("model_profile_id")?.let(UUID::fromString),
                        modelLabel = rs.getString("model_label"),
                    )
                },
                cost = Money(rs.getBigDecimal("cost") ?: BigDecimal.ZERO),
                apiCost = Money(rs.getBigDecimal("api_cost") ?: BigDecimal.ZERO),
                durationSeconds = rs.getObject("secs")?.let { (it as Number).toDouble() },
            )
        }.list()
    }
}
