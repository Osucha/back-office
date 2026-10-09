package pl.mos.backoffice.app.perf

import java.sql.Connection
import kotlin.system.measureTimeMillis

/**
 * Seeds a realistic, large dataset with set-based SQL (`generate_series`) for query-plan reviews and load
 * tests (docs/architecture/performance.md). Idempotent: does nothing when project `PERF1` exists.
 *
 * Shape: [projects] projects (skewed: low-numbered projects get more runs), [runs] workflow runs over the
 * last 365 days (70 % succeeded, 20 % failed, 9 % cancelled, ~1 % active), one artefact per 4 runs, one
 * approval per 10 runs (OPEN for waiting runs), one CI execution per 2 runs, [usageRows] usage records over
 * 365 days across 8 models and 5 context kinds and [auditRows] audit rows over 12 months.
 *
 * JSON columns are copied from the newest existing run / CI execution when there is one (so the API can
 * render seeded rows after a real run was created, as the k6 setup does); otherwise minimal documents.
 */
class PerfDataGenerator(
    val projects: Int = 50,
    val runs: Int = 200_000,
    val usageRows: Int = 2_000_000,
    val auditRows: Int = 1_000_000,
) {
    data class Timing(val step: String, val rows: Long, val millis: Long)

    fun seed(connection: Connection, log: (String) -> Unit = {}): List<Timing> {
        val timings = mutableListOf<Timing>()
        if (count(connection, "SELECT count(*) FROM project WHERE key = 'PERF1'") > 0) {
            log("perf data already present, skipping")
            return timings
        }
        val runTemplate = template(connection, "SELECT definition_snapshot::text, input::text, nodes::text FROM workflow_run ORDER BY created_at DESC LIMIT 1", 3)
            ?: listOf(MINIMAL_SNAPSHOT, """{"parameters": {}}""", "[]")
        val ciTemplate = template(connection, "SELECT context::text, variables::text FROM ci_execution ORDER BY created_at DESC LIMIT 1", 2)
            ?: listOf("""{"provider": "GITLAB", "projectPath": "perf/app", "pipelineId": "1", "jobId": "1"}""", "{}")

        fun step(name: String, sql: String, vararg params: Any) {
            var rows = 0L
            val ms = measureTimeMillis {
                connection.prepareStatement(sql).use { ps ->
                    params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                    rows = ps.executeLargeUpdate()
                }
            }
            timings += Timing(name, rows, ms)
            log("seeded $name rows=$rows in ${ms}ms")
        }

        connection.autoCommit = false
        step(
            "project",
            """
            INSERT INTO project (id, key, name, description, repo_url, repo_default_branch, constitution, ci_entrypoints, created_at, updated_at)
            SELECT md5('perf-project-' || g)::uuid, 'PERF' || g, 'Perf project ' || g, '', 'https://git.example.com/perf/p' || g || '.git', 'main', '',
                   '[]'::jsonb, now() - interval '400 days', now()
            FROM generate_series(1, ?) g
            """.trimIndent(),
            projects,
        )
        step(
            "workflow_run",
            """
            WITH ps AS (SELECT array_agg(id ORDER BY created_at, key) ids, array_agg(key ORDER BY created_at, key) keys, count(*) n FROM project WHERE key LIKE 'PERF%'),
            src AS (
                SELECT g, random() r, 1 + floor(power(random(), 2) * (SELECT n FROM ps))::int p, now() - random() * interval '365 days' created FROM generate_series(1, ?) g
            ),
            s AS (
                SELECT src.*, CASE WHEN r < 0.70 THEN 'SUCCEEDED' WHEN r < 0.90 THEN 'FAILED' WHEN r < 0.99 THEN 'CANCELLED'
                                   WHEN r < 0.995 THEN 'WAITING' WHEN r < 0.998 THEN 'RUNNING' ELSE 'PENDING' END status
                FROM src
            )
            INSERT INTO workflow_run (id, project_id, project_key, workflow_id, workflow_version, workflow_name, definition_snapshot, trigger_type, input,
                                      status, outcome, current_node_id, waiting_kind, nodes, cost_amount, cost_currency, created_at, started_at, finished_at)
            SELECT md5('perf-run-' || s.g)::uuid, ps.ids[s.p], ps.keys[s.p], md5(ps.ids[s.p]::text || '-wf')::uuid, 1, 'Perf workflow', ?::jsonb,
                   CASE WHEN s.g % 3 = 0 THEN 'CI' ELSE 'MANUAL' END, ?::jsonb, s.status,
                   CASE s.status WHEN 'SUCCEEDED' THEN 'SUCCESS' WHEN 'FAILED' THEN 'FAILURE' END,
                   CASE WHEN s.status = 'WAITING' THEN 'gate' END,
                   CASE WHEN s.status = 'WAITING' THEN (CASE WHEN s.g % 2 = 0 THEN 'HUMAN_GATE' ELSE 'WAIT_FOR_CI' END) END,
                   ?::jsonb, round((random() * 3)::numeric, 6), 'USD', s.created, s.created + interval '5 seconds',
                   CASE WHEN s.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN s.created + random() * interval '30 minutes' END
            FROM s, ps
            """.trimIndent(),
            runs, runTemplate[0], runTemplate[1], runTemplate[2],
        )
        step(
            "artifact",
            """
            INSERT INTO artifact (id, run_id, node_id, name, kind, media_type, content, size_bytes, created_at)
            SELECT md5('perf-artifact-' || r.id)::uuid, r.id, 'specify', 'spec', 'SPEC', 'text/markdown', '# Spec ' || r.id, 42, r.created_at + interval '1 minute'
            FROM workflow_run r WHERE r.project_key LIKE 'PERF%' AND (('x' || substr(r.id::text, 1, 8))::bit(32)::int % 4) = 0
            """.trimIndent(),
        )
        step(
            "approval_request",
            """
            INSERT INTO approval_request (id, run_id, node_id, project_id, project_key, workflow_name, question, options, context, status, decision, created_at)
            SELECT md5('perf-approval-' || r.id)::uuid, r.id, 'gate', r.project_id, r.project_key, r.workflow_name, 'Approve the spec?',
                   '["approve", "reject"]'::jsonb, '{}'::jsonb, CASE WHEN r.status = 'WAITING' AND r.waiting_kind = 'HUMAN_GATE' THEN 'OPEN' ELSE 'DECIDED' END,
                   NULL, r.created_at + interval '2 minutes'
            FROM workflow_run r
            WHERE r.project_key LIKE 'PERF%' AND ((('x' || substr(r.id::text, 1, 8))::bit(32)::int % 10) = 0 OR (r.status = 'WAITING' AND r.waiting_kind = 'HUMAN_GATE'))
            """.trimIndent(),
        )
        step(
            "ci_execution",
            """
            INSERT INTO ci_execution (id, project_id, project_key, entrypoint, mode, status, gate_result, idempotency_key, context, variables, changed_files,
                                      run_id, outcome, cost_amount, cost_currency, created_at, finished_at)
            SELECT md5('perf-ci-' || r.id)::uuid, r.project_id, r.project_key, 'code-review', 'ADVISORY',
                   CASE r.status WHEN 'SUCCEEDED' THEN 'SUCCEEDED' WHEN 'FAILED' THEN 'FAILED' WHEN 'CANCELLED' THEN 'FAILED' ELSE 'RUNNING' END,
                   CASE r.status WHEN 'SUCCEEDED' THEN 'PASS' WHEN 'FAILED' THEN 'FAIL' ELSE 'PENDING' END,
                   'perf:' || r.id, ?::jsonb, ?::jsonb, '[]'::jsonb, r.id, r.outcome, r.cost_amount, 'USD', r.created_at, r.finished_at
            FROM workflow_run r WHERE r.project_key LIKE 'PERF%' AND r.trigger_type = 'CI' OR (r.project_key LIKE 'PERF%' AND (('x' || substr(r.id::text, 1, 8))::bit(32)::int % 6) = 1)
            """.trimIndent(),
            ciTemplate[0], ciTemplate[1],
        )
        step(
            "usage_record",
            """
            WITH ps AS (SELECT array_agg(id ORDER BY created_at, key) ids, count(*) n FROM project WHERE key LIKE 'PERF%'),
            models AS (SELECT ARRAY['anthropic', 'anthropic', 'anthropic', 'openai', 'openai', 'google', 'mistral', 'ollama'] providers,
                              ARRAY['claude-opus-5', 'claude-sonnet-5', 'claude-haiku-5', 'gpt-6', 'gpt-6-mini', 'gemini-3-pro', 'mistral-large-3', 'qwen3-coder'] ids,
                              ARRAY['PIPELINE_STEP', 'PIPELINE_STEP', 'CODE_REVIEW', 'BENCHMARK_ATTEMPT', 'MODEL_TEST'] kinds)
            INSERT INTO usage_record (id, occurred_at, provider, model_id, model_label, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                      cost, estimated_cost, currency, duration_ms, context_kind, project_id)
            SELECT md5('perf-usage-' || g)::uuid, now() - random() * interval '365 days', models.providers[m], models.ids[m], models.providers[m] || '/' || models.ids[m],
                   (random() * 20000)::bigint, (random() * 4000)::bigint, (random() * 10000)::bigint, 0,
                   round((random() * 0.5)::numeric, 6), round((random() * 0.5)::numeric, 6), 'USD', (random() * 60000)::bigint,
                   models.kinds[k], CASE WHEN k <= 3 THEN ps.ids[1 + floor(power(random(), 2) * ps.n)::int] END
            FROM (SELECT g, 1 + floor(power(random(), 1.5) * 8)::int m, 1 + floor(random() * 5)::int k FROM generate_series(1, ?) g) x, ps, models
            """.trimIndent(),
            usageRows,
        )
        step(
            "audit_log",
            """
            WITH ps AS (SELECT array_agg(id ORDER BY created_at, key) ids, count(*) n FROM project WHERE key LIKE 'PERF%'),
            ops AS (SELECT ARRAY['run.start', 'run.cancel', 'approval.decide', 'workflow.update', 'project.update', 'ci.execute', 'ci.signal',
                                 'auth.login', 'auth.logout', 'token.create', 'user.update', 'ACCESS_DENIED'] names,
                           ARRAY['workflow_run', 'workflow_run', 'approval_request', 'workflow_definition', 'project', 'ci_execution', 'ci_execution',
                                 'session', 'session', 'personal_token', 'user', 'workflow_run'] types)
            INSERT INTO audit_log (event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id, result, details, correlation_id)
            SELECT now() - random() * interval '365 days', md5('perf-audit-' || g)::uuid, 'USER', md5('perf-user-' || (g % 200))::uuid, 'Perf user ' || (g % 200),
                   ops.names[o], ops.types[o], md5('perf-entity-' || (g % 50000))::uuid::text,
                   CASE WHEN o <= 7 THEN ps.ids[1 + floor(power(random(), 2) * ps.n)::int] END,
                   CASE WHEN o = 12 THEN 'DENIED' ELSE 'SUCCESS' END, '{}'::jsonb, md5('perf-corr-' || g)
            FROM (SELECT g, 1 + floor(random() * 12)::int o FROM generate_series(1, ?) g) x, ps, ops
            """.trimIndent(),
            auditRows,
        )
        connection.commit()
        connection.autoCommit = true
        val analyzeMs = measureTimeMillis { connection.createStatement().use { it.execute("VACUUM ANALYZE") } }
        timings += Timing("VACUUM ANALYZE", 0, analyzeMs)
        return timings
    }

    /** Project ids `PERF1..PERF5`: the "member of 5 projects" filter used by the query plans. */
    fun memberProjects(connection: Connection): List<String> = connection.createStatement().use { st ->
        st.executeQuery("SELECT id::text FROM project WHERE key IN ('PERF1', 'PERF7', 'PERF13', 'PERF21', 'PERF40') ORDER BY key").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    private fun count(connection: Connection, sql: String): Long = connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    private fun template(connection: Connection, sql: String, columns: Int): List<String>? = connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) (1..columns).map { rs.getString(it) } else null }
    }

    companion object {
        private const val MINIMAL_SNAPSHOT = """{"id": "00000000-0000-0000-0000-000000000000", "name": "Perf workflow", "nodes": [], "edges": []}"""
    }
}
