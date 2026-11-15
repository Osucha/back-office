package pl.mos.backoffice.runner.adapter.out.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled

/**
 * `backoffice_runner_jobs{pool,status}`: agent jobs not yet finished (QUEUED, LEASED, STARTING, RUNNING) per runner
 * pool, re-read from the database every `backoffice.runner.metrics-interval` (default 30 s). Every api/worker replica
 * reports the same numbers, so dashboards and alerts aggregate with `max by (pool, status)`. A job not routed to a
 * pool yet is reported under `pool="none"`. Read by the "Runner fleet" dashboard and the
 * `BackOfficeRunnerPoolWithoutRunners` alert (deploy/helm/back-office/templates/observability.yaml).
 */
class AgentJobMetrics(registry: MeterRegistry, private val db: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(AgentJobMetrics::class.java)
    private val jobs = MultiGauge.builder(NAME).description("Agent jobs not yet finished, by runner pool and status").register(registry)

    @Scheduled(fixedDelayString = "\${backoffice.runner.metrics-interval:PT30S}", initialDelayString = "\${backoffice.runner.metrics-interval:PT30S}")
    fun refresh() {
        try {
            val rows = db.query(QUERY) { rs, _ -> Triple(rs.getString("pool_id") ?: NO_POOL, rs.getString("status"), rs.getLong("n")) }
            jobs.register(rows.map { (pool, status, n) -> MultiGauge.Row.of(Tags.of("pool", pool, "status", status), n) }, true)
        } catch (e: RuntimeException) {
            log.debug("agent job metrics not refreshed: {}", e.toString())
        }
    }

    companion object {
        const val NAME = "backoffice.runner.jobs"
        const val NO_POOL = "none"
        private const val QUERY =
            "SELECT pool_id, status, count(*) AS n FROM agent_job WHERE status IN ('QUEUED', 'LEASED', 'STARTING', 'RUNNING') GROUP BY pool_id, status"
    }
}
