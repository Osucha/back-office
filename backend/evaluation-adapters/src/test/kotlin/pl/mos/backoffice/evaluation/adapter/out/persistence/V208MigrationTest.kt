package pl.mos.backoffice.evaluation.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR 0059: V208 adds the candidates of a benchmark (empty for the benchmarks of models stored before) and gives the
 * attempts stored before it the agent a benchmark of models implies: model-answer for prompt tasks, the agent of the
 * benchmark's coding harness for coding tasks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V208MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val suite = UUID.randomUUID()
    private val model = UUID.randomUUID()
    private val openCodeBenchmark = UUID.randomUUID()
    private val claudeBenchmark = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("207").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    "insert into model_profile (id, display_name, provider, model_id, options, input_per_mtok, output_per_mtok, cache_read_per_mtok, " +
                        "cache_write_per_mtok, currency, capabilities, enabled, created_at, updated_at) values ('$model', 'M', 'ANTHROPIC', 'm', '{}', 1, 1, 0, 0, " +
                        "'USD', '[]', true, now(), now())",
                    "insert into task_suite (id, name, created_at, updated_at) values ('$suite', 'S', now(), now())",
                    benchmark(openCodeBenchmark, "OPENCODE"),
                    benchmark(claudeBenchmark, "CLAUDE_CODE"),
                    attempt(openCodeBenchmark, "PROMPT"),
                    attempt(openCodeBenchmark, "CODING_JOB"),
                    attempt(claudeBenchmark, "CODING_JOB"),
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `older attempts get the agent of a benchmark of models, older benchmarks no candidates`() {
        val agents = query("select b.coding_harness, a.kind, a.agent_key, a.agent_name, a.harness from benchmark_attempt a join benchmark b on b.id = a.benchmark_id") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
        assertThat(agents).containsExactlyInAnyOrder(
            listOf("OPENCODE", "PROMPT", "model-answer", "Model answer", null),
            listOf("OPENCODE", "CODING_JOB", "opencode", "OpenCode", "OPENCODE"),
            listOf("CLAUDE_CODE", "CODING_JOB", "claude-code", "Claude Code", "CLAUDE_CODE"),
        )
        assertThat(query("select candidates::text from benchmark") { it.getString(1) }).containsOnly("[]")
    }

    private fun benchmark(id: UUID, harness: String) =
        "insert into benchmark (id, name, suite_id, task_ids, model_profile_ids, judge_model_profile_id, repetitions, coding_harness, status, created_at) " +
            "values ('$id', 'B', '$suite', '[]', '[\"$model\"]', '$model', 1, '$harness', 'COMPLETED', now())"

    private fun attempt(benchmark: UUID, kind: String) =
        "insert into benchmark_attempt (id, benchmark_id, task_id, task_name, category, difficulty, kind, model_profile_id, model_display_name, provider, " +
            "repetition, status, judge_criteria, created_at) values ('${UUID.randomUUID()}', '$benchmark', '${UUID.randomUUID()}', 't', 'REVIEW', 'MEDIUM', " +
            "'$kind', '$model', 'M', 'ANTHROPIC', 1, 'SUCCEEDED', '[]', now())"

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
