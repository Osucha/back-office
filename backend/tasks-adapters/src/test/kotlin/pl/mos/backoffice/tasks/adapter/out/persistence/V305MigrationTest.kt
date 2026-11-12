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
import java.util.UUID

/**
 * ADR 0057/0058: V305 gives a template that is not a pristine built-in and has rules or skills of its own an agent of
 * its own (harness of its current agent, ground rules plus its rules, its skills), then drops the template columns and
 * adds parameters, on a database that holds V304 data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V305MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val project: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val longKey = "a" + "b".repeat(62)

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("304").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    rule("agent-ground-rules"),
                    rule("ask-dont-guess"),
                    rule("house-style"),
                    "insert into agent (id, key, name, scope_type, created_at, updated_at) values (gen_random_uuid(), 'clash-agent', 'Taken', 'GLOBAL', now(), now())",
                    template("code-review", skills = """["security-review-checklist"]""", rules = """["agent-ground-rules"]""", builtIn = true, updatedBy = "SYSTEM"),
                    template("edited-review", skills = """["security-review-checklist"]""", builtIn = true, version = 2, updatedBy = "USER"),
                    template("coder", agentKey = "claude-code", rules = """["house-style","ask-dont-guess"]"""),
                    template("parent", agentKey = "opencode"),
                    template("child", extendsKey = "parent", skills = """["spec-writing"]"""),
                    template("plain"),
                    template("mine", skills = """["fmt"]""", projectId = project),
                    template("clash", skills = """["fmt"]"""),
                    template(longKey, skills = """["fmt"]"""),
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway("305").migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `templates with own rules or skills get an agent of their own and name it, pristine built-ins and plain ones keep theirs`() {
        val agentKeys = query("select key, agent_key from task_template") { it.getString(1) to it.getString(2) }.toMap()
        assertThat(agentKeys["code-review"]).describedAs("pristine built-in: the seed reshapes it").isNull()
        assertThat(agentKeys["plain"]).isNull()
        assertThat(agentKeys["parent"]).isEqualTo("opencode")
        assertThat(agentKeys["edited-review"]).isEqualTo("edited-review-agent")
        assertThat(agentKeys["coder"]).isEqualTo("coder-agent")
        assertThat(agentKeys["child"]).isEqualTo("child-agent")
        assertThat(agentKeys["mine"]).isEqualTo("mine-agent")
        assertThat(agentKeys["clash"]).describedAs("a taken key gets a short suffix").startsWith("clash-agent-").hasSize("clash-agent-".length + 4)
        assertThat(agentKeys[longKey]).isEqualTo(longKey.take(58) + "-agent")
    }

    @Test
    fun `the new agents carry the harness of the template's agent, the ground rules plus its rules and its skills`() {
        val agents = query("select key, name, harness, rules::text, skills::text, project_id, built_in, updated_by_kind, instructions, mcp_servers::text from agent") {
            it.getString(1) to listOf(it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getBoolean(7).toString(), it.getString(8), it.getString(9), it.getString(10))
        }.toMap()

        val coder = agents.getValue("coder-agent")
        assertThat(coder[0]).isEqualTo("coder (agent)")
        assertThat(coder[1]).isEqualTo("CLAUDE_CODE")
        assertThat(coder[2]).describedAs("ground rules first, no duplicates").isEqualTo("""["agent-ground-rules", "ask-dont-guess", "house-style"]""")
        assertThat(coder[3]).isEqualTo("[]")
        assertThat(coder.subList(5, 9)).containsExactly("false", "SYSTEM", "", "[]")

        assertThat(agents.getValue("child-agent")[1]).describedAs("the nearest ancestor's agent").isEqualTo("OPENCODE")
        assertThat(agents.getValue("child-agent")[3]).isEqualTo("""["spec-writing"]""")
        assertThat(agents.getValue("edited-review-agent")[1]).describedAs("no agent anywhere: a model answer").isNull()
        assertThat(agents.getValue("mine-agent")[4]).isEqualTo(project.toString())
        assertThat(agents.keys).doesNotContain("code-review-agent", "plain-agent")
    }

    @Test
    fun `the template columns of rules and skills are gone and parameters exist`() {
        val columns = query("select column_name from information_schema.columns where table_name = 'task_template'") { it.getString(1) }
        assertThat(columns).doesNotContain("skills", "remove_skills", "rules", "remove_rules").contains("parameters", "parameter_values")
        val agentColumns = query("select column_name from information_schema.columns where table_name = 'agent'") { it.getString(1) }
        assertThat(agentColumns).contains("instructions", "rules", "skills", "mcp_servers")
        assertThat(query("select parameters::text, parameter_values::text from task_template where key = 'plain'") { it.getString(1) + it.getString(2) })
            .containsExactly("[]{}")
    }

    private fun rule(key: String): String =
        "insert into rule (id, key, name, content, scope_type, created_at, updated_at) values (gen_random_uuid(), ${q(key)}, ${q(key)}, 'text', 'GLOBAL', now(), now())"

    @Suppress("LongParameterList")
    private fun template(
        key: String,
        agentKey: String? = null,
        extendsKey: String? = null,
        skills: String = "[]",
        rules: String = "[]",
        builtIn: Boolean = false,
        version: Int = 1,
        updatedBy: String? = null,
        projectId: UUID? = null,
    ): String =
        "insert into task_template (id, key, name, agent_key, extends_key, skills, rules, scope_type, project_id, built_in, version, " +
            "updated_by_kind, created_at, updated_at) values (gen_random_uuid(), ${q(key)}, ${q(key)}, ${q(agentKey)}, ${q(extendsKey)}, " +
            "${q(skills)}::jsonb, ${q(rules)}::jsonb, ${q(if (projectId == null) "GLOBAL" else "PROJECT")}, ${q(projectId?.toString())}::uuid, " +
            "$builtIn, $version, ${q(updatedBy)}, now(), now())"

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
