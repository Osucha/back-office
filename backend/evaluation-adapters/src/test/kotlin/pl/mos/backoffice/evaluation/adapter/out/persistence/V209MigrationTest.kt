package pl.mos.backoffice.evaluation.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR 0065: V209 adds `api_cost` / `judge_api_cost` and fills them for the attempts stored before: the runner jobs'
 * reported cost (`agent_job.cost_usd`: the coding candidate's job, a login candidate's first prompt job, a login judge's
 * other jobs), else the spent cost. `agent_job` belongs to the agent-runner module; this test creates the columns the
 * migration reads (the other migration tests of this module run V209 without it).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V209MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val suite = UUID.randomUUID()
    private val apiModel = UUID.randomUUID()
    private val loginModel = UUID.randomUUID()
    private val loginJudge = UUID.randomUUID()
    private val apiJudgeBenchmark = UUID.randomUUID()
    private val loginJudgeBenchmark = UUID.randomUUID()

    private val apiCoding = UUID.randomUUID()
    private val apiPrompt = UUID.randomUUID()
    private val loginPrompt = UUID.randomUUID()
    private val loginCoding = UUID.randomUUID()
    private val apiPromptLoginJudge = UUID.randomUUID()
    private val noJobs = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("208").migrate()
        val codingJob = UUID.randomUUID()
        val loginCodingJob = UUID.randomUUID()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    "create table agent_job (id uuid primary key, attempt_id uuid, queued_at timestamptz not null, started_at timestamptz, finished_at timestamptz, cost_usd numeric(18, 6))",
                    profile(apiModel, "claude-sonnet-5", "API"),
                    profile(loginModel, "claude-sonnet-5", "CLAUDE_CODE_LOGIN"),
                    profile(loginJudge, "claude-opus-5", "CLAUDE_CODE_LOGIN"),
                    "insert into task_suite (id, name, created_at, updated_at) values ('$suite', 'S', now(), now())",
                    benchmark(apiJudgeBenchmark, apiModel),
                    benchmark(loginJudgeBenchmark, loginJudge),
                    // API judge: a coding job with its reported cost; a prompt answer through the gateway
                    attempt(apiCoding, apiJudgeBenchmark, "CODING_JOB", loginModel, cost = "0", judgeCost = "0.03", runnerJob = codingJob),
                    job(codingJob, apiCoding, 1, "0.42"),
                    attempt(apiPrompt, apiJudgeBenchmark, "PROMPT", apiModel, cost = "0.01", judgeCost = "0.03"),
                    // login judge: the login candidate's answer is the first job, the judge's calls come after it
                    attempt(loginPrompt, loginJudgeBenchmark, "PROMPT", loginModel, cost = "0", judgeCost = "0"),
                    job(UUID.randomUUID(), loginPrompt, 1, "0.05"),
                    job(UUID.randomUUID(), loginPrompt, 2, "0.02"),
                    job(UUID.randomUUID(), loginPrompt, 3, "0.01"),
                    // login judge of a coding attempt: every job but the candidate's runner job (even an earlier one)
                    attempt(loginCoding, loginJudgeBenchmark, "CODING_JOB", loginModel, cost = "0", judgeCost = "0", runnerJob = loginCodingJob),
                    job(loginCodingJob, loginCoding, 5, "1.25"),
                    job(UUID.randomUUID(), loginCoding, 9, "0.04"),
                    job(UUID.randomUUID(), loginCoding, 10, null),
                    // login judge, API candidate: all jobs are the judge's
                    attempt(apiPromptLoginJudge, loginJudgeBenchmark, "PROMPT", apiModel, cost = "0.01", judgeCost = "0"),
                    job(UUID.randomUUID(), apiPromptLoginJudge, 1, "0.02"),
                    job(UUID.randomUUID(), apiPromptLoginJudge, 2, "0.03"),
                    // no jobs at all: the spent cost
                    attempt(noJobs, loginJudgeBenchmark, "PROMPT", loginModel, cost = "0", judgeCost = "0"),
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `existing attempts get their reported cost at API prices, else their spent cost`() {
        val costs = query("select id, api_cost, judge_api_cost from benchmark_attempt") {
            UUID.fromString(it.getString(1)) to (it.getBigDecimal(2) to it.getBigDecimal(3))
        }.toMap()

        assertThat(costs.getValue(apiCoding)).isEqualTo(money("0.42") to money("0.03"))
        assertThat(costs.getValue(apiPrompt)).isEqualTo(money("0.01") to money("0.03"))
        assertThat(costs.getValue(loginPrompt)).isEqualTo(money("0.05") to money("0.03"))
        assertThat(costs.getValue(loginCoding)).isEqualTo(money("1.25") to money("0.04"))
        assertThat(costs.getValue(apiPromptLoginJudge)).isEqualTo(money("0.01") to money("0.05"))
        assertThat(costs.getValue(noJobs)).isEqualTo(money("0") to money("0"))
    }

    @Test
    fun `V210 recomputes the candidate's time from its job, without queueing`() {
        val latency = query("select id, candidate_latency_ms from benchmark_attempt") { UUID.fromString(it.getString(1)) to it.getLong(2) }.toMap()

        // coding: the runner job (5 min queued, ran 5 s); a login candidate's prompt: its first job (ran 1 s)
        assertThat(latency.getValue(loginCoding)).isEqualTo(5_000L)
        assertThat(latency.getValue(apiCoding)).isEqualTo(1_000L)
        assertThat(latency.getValue(loginPrompt)).isEqualTo(1_000L)
        // an API candidate's answer did not run on a runner: unchanged
        assertThat(latency.getValue(apiPromptLoginJudge)).isEqualTo(3_600_000L)
        assertThat(latency.getValue(noJobs)).isEqualTo(3_600_000L)
    }

    private fun money(value: String) = BigDecimal(value).setScale(6)

    private fun profile(id: UUID, modelId: String, runtime: String) =
        "insert into model_profile (id, display_name, provider, model_id, options, input_per_mtok, output_per_mtok, cache_read_per_mtok, " +
            "cache_write_per_mtok, currency, capabilities, enabled, created_at, updated_at, runtime) values ('$id', 'M', 'ANTHROPIC', '$modelId', '{}', 1, 1, 0, 0, " +
            "'USD', '[]', true, now(), now(), '$runtime')"

    private fun benchmark(id: UUID, judge: UUID) =
        "insert into benchmark (id, name, suite_id, task_ids, model_profile_ids, judge_model_profile_id, repetitions, coding_harness, status, created_at) " +
            "values ('$id', 'B', '$suite', '[]', '[\"$apiModel\"]', '$judge', 1, 'CLAUDE_CODE', 'COMPLETED', now())"

    private fun attempt(id: UUID, benchmark: UUID, kind: String, model: UUID, cost: String, judgeCost: String, runnerJob: UUID? = null) =
        "insert into benchmark_attempt (id, benchmark_id, task_id, task_name, category, difficulty, kind, model_profile_id, model_display_name, provider, " +
            "repetition, status, judge_criteria, created_at, cost, judge_cost, runner_job_id, candidate_latency_ms) values ('$id', '$benchmark', '${UUID.randomUUID()}', 't', 'REVIEW', " +
            "'MEDIUM', '$kind', '$model', 'M', 'ANTHROPIC', 1, 'SUCCEEDED', '[]', now(), $cost, $judgeCost, ${runnerJob?.let { "'$it'" } ?: "null"}, 3600000)"

    private fun job(id: UUID, attempt: UUID, minute: Int, cost: String?) =
        "insert into agent_job (id, attempt_id, queued_at, started_at, finished_at, cost_usd) values ('$id', '$attempt', " +
            "timestamptz '2026-11-01 10:00:00+00' + interval '$minute minutes', timestamptz '2026-11-01 11:00:00+00' + interval '$minute minutes', " +
            "timestamptz '2026-11-01 11:00:00+00' + interval '$minute minutes' + interval '$minute seconds', ${cost ?: "null"})"

    private fun flyway(target: String?): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> = connection().use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }
    }
}
