package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** ADR 0056: V304 turns executors and the config key `agent` into agent keys on a database that holds V303 data. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V304MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("303").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    row("notify-jira", executor = "NOTIFY", builtIn = true),
                    row("fetch-issue", executor = "FETCH_ISSUE", builtIn = true),
                    row("transition-issue", executor = "TRANSITION_ISSUE", builtIn = true),
                    row("my-transition", extendsKey = "transition-issue"),
                    row("impl", executor = "IMPLEMENT", config = """{"agent":"CLAUDE_CODE","push":true}"""),
                    row("impl-default", executor = "SPEC_KIT", config = """{"phase":"PLAN"}"""),
                    row("child", executor = "IMPLEMENT", extendsKey = "impl"),
                    row("llm", executor = "LLM"),
                    row("llm-under-coder", executor = "LLM", extendsKey = "impl"),
                    row("legacy", config = """{"agent":"OPENCODE","x":1}"""),
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `templates get the agent of their executor and config, and the config key agent is gone`() {
        val rows = query("select key, agent_key, config::text from task_template") { it.getString(1) to (it.getString(2) to it.getString(3)) }.toMap()

        assertThat(rows.getValue("impl").first).isEqualTo("claude-code")
        assertThat(rows.getValue("impl").second).doesNotContain("agent").contains("push")
        assertThat(rows.getValue("impl-default").first).isEqualTo("opencode")
        assertThat(rows.getValue("child").first).describedAs("inherits the parent's harness").isNull()
        assertThat(rows.getValue("llm").first).isNull()
        assertThat(rows.getValue("llm-under-coder").first).isEqualTo("model-answer")
        assertThat(rows.getValue("legacy").first).isEqualTo("opencode")
        assertThat(rows.getValue("legacy").second).doesNotContain("agent").contains("\"x\"")
    }

    @Test
    fun `the built-in action templates leave unless extended, the executor column is dropped and the built-in agents exist`() {
        val keys = query("select key from task_template") { it.getString(1) }
        assertThat(keys).doesNotContain("notify-jira", "fetch-issue").contains("transition-issue", "my-transition")

        val columns = query("select column_name from information_schema.columns where table_name = 'task_template'") { it.getString(1) }
        assertThat(columns).contains("agent_key").doesNotContain("executor")

        val agents = query("select key, harness, built_in from agent") { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) }
        assertThat(agents).containsExactlyInAnyOrder(
            Triple("model-answer", null, true), Triple("opencode", "OPENCODE", true), Triple("claude-code", "CLAUDE_CODE", true),
        )
    }

    private fun row(key: String, executor: String? = null, extendsKey: String? = null, config: String = "{}", builtIn: Boolean = false): String =
        "insert into task_template (id, key, name, executor, extends_key, config, scope_type, built_in, created_at, updated_at) values (" +
            "gen_random_uuid(), ${q(key)}, ${q(key)}, ${q(executor)}, ${q(extendsKey)}, ${q(config)}::jsonb, ${q("GLOBAL")}, $builtIn, now(), now())"

    private fun q(value: String?): String = if (value == null) "null" else "'" + value.replace("'", "''") + "'"

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
