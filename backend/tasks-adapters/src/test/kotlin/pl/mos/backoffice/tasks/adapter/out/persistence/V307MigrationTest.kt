package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR 0061: V307 gives task steps without an agent the agent their template lineage named (and the lineage's model when
 * the step and its agent have none) in stored workflows, library versions and the snapshots of unfinished runs, then
 * drops the two template columns. The tables of the Projects module are created here in the shape their migrations
 * give them (only the columns V307 reads), with data like an owner's database after ADR 0060.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V307MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val json = JsonMapper.builder().build()
    private val shop: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val other: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
    private val templateModel: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    private val reviewerModel: UUID = UUID.fromString("88888888-8888-8888-8888-888888888888")
    private val stepModel: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    private val workflow: UUID = UUID.randomUUID()
    private val otherWorkflow: UUID = UUID.randomUUID()
    private val library: UUID = UUID.randomUUID()
    private val running: UUID = UUID.randomUUID()
    private val finished: UUID = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("306").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    "create table workflow_definition (id uuid primary key, project_id uuid not null, nodes jsonb not null)",
                    "create table library_workflow_version (library_workflow_id uuid not null, version integer not null, nodes jsonb not null, source_project_id uuid)",
                    "create table workflow_run (id uuid primary key, project_id uuid not null, status text not null, definition_snapshot jsonb not null)",
                    agent("analyst", null, null),
                    agent("reviewer", reviewerModel, null),
                    agent("shop-coder", null, shop),
                    template("code-review", agentKey = "reviewer", model = templateModel),
                    template("analyse", agentKey = "analyst", model = templateModel),
                    template("implement", agentKey = "opencode"),
                    template("implement-task-list", extendsKey = "implement"),
                    template("plain"),
                    template("analyse", agentKey = "shop-coder", extendsKey = "analyse", projectId = shop),
                    template("own-review", extendsKey = "code-review", projectId = shop),
                    "insert into workflow_definition values ('$workflow', '$shop', '${nodes(shopNodes())}'::jsonb)",
                    "insert into workflow_definition values ('$otherWorkflow', '$other', '${nodes(listOf(task("a", "analyse"), task("r", "own-review")))}'::jsonb)",
                    "insert into library_workflow_version values ('$library', 1, '${nodes(listOf(task("a", "analyse"), task("i", "implement-task-list")))}'::jsonb, '$shop')",
                    "insert into library_workflow_version values ('$library', 2, '${nodes(listOf(task("a", "analyse")))}'::jsonb, null)",
                    "insert into workflow_run values ('$running', '$shop', 'WAITING', '${snapshot(listOf(task("r", "code-review")))}'::jsonb)",
                    "insert into workflow_run values ('$finished', '$shop', 'SUCCEEDED', '${snapshot(listOf(task("r", "code-review")))}'::jsonb)",
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    private fun shopNodes(): List<Map<String, Any?>> = listOf(
        mapOf("id" to "start", "kind" to "START", "name" to "Start"),
        task("review", "code-review"),
        task("implement", "implement", overrides = mapOf("agentKey" to "claude-code")),
        task("legacy", "implement", overrides = mapOf("config" to mapOf("agent" to "CLAUDE_CODE"))),
        task("tasks", "implement-task-list", overrides = null),
        task("plain", "plain"),
        task("analyse", "analyse"),
        task("analyse-own-model", "analyse", overrides = mapOf("modelProfileId" to stepModel.toString())),
        task("own-review", "own-review", overrides = mapOf("agentKey" to "analyst")),
    )

    @Test
    fun `steps without an agent get their lineage's agent, steps with one keep it`() {
        val steps = overrides("select nodes::text from workflow_definition where id = '$workflow'")
        assertThat(steps.getValue("review")["agentKey"]).isEqualTo("reviewer")
        assertThat(steps.getValue("implement")["agentKey"]).isEqualTo("claude-code")
        assertThat(steps.getValue("legacy")).describedAs("the legacy config key names the agent").doesNotContainKey("agentKey")
        assertThat(steps.getValue("tasks")["agentKey"]).describedAs("inherited from implement; overrides created").isEqualTo("opencode")
        assertThat(steps.getValue("plain")).describedAs("no agent anywhere: the fallback applies").doesNotContainKey("agentKey")
        assertThat(steps.getValue("analyse")["agentKey"]).describedAs("the project's template shadows the global one").isEqualTo("shop-coder")
        assertThat(steps.getValue("own-review")["agentKey"]).isEqualTo("analyst")
        assertThat(nodesOf("select nodes::text from workflow_definition where id = '$workflow'").first())
            .describedAs("other nodes untouched").isEqualTo(mapOf("id" to "start", "kind" to "START", "name" to "Start"))
    }

    @Test
    fun `the lineage's model moves to the step unless the step or its agent has one`() {
        val steps = overrides("select nodes::text from workflow_definition where id = '$workflow'")
        assertThat(steps.getValue("review")).describedAs("the reviewer's own model came first").doesNotContainKey("modelProfileId")
        assertThat(steps.getValue("analyse")["modelProfileId"]).describedAs("inherited from the global analyse").isEqualTo(templateModel.toString())
        assertThat(steps.getValue("analyse-own-model")["modelProfileId"]).isEqualTo(stepModel.toString())
        assertThat(steps.getValue("own-review")["modelProfileId"]).describedAs("the step's analyst has no model").isEqualTo(templateModel.toString())
        assertThat(steps.getValue("plain")).doesNotContainKey("modelProfileId")
    }

    @Test
    fun `another project sees the global templates`() {
        val steps = overrides("select nodes::text from workflow_definition where id = '$otherWorkflow'")
        assertThat(steps.getValue("a")["agentKey"]).isEqualTo("analyst")
        assertThat(steps.getValue("r")).describedAs("the shop's own-review is not visible there").doesNotContainKey("agentKey")
    }

    @Test
    fun `library versions are seen from their source project`() {
        val v1 = overrides("select nodes::text from library_workflow_version where version = 1")
        assertThat(v1.getValue("a")["agentKey"]).isEqualTo("shop-coder")
        assertThat(v1.getValue("i")["agentKey"]).isEqualTo("opencode")
        assertThat(overrides("select nodes::text from library_workflow_version where version = 2").getValue("a")["agentKey"]).isEqualTo("analyst")
    }

    @Test
    fun `unfinished runs continue with the same agent, finished ones keep their snapshot`() {
        val waiting = overrides("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$running'")
        assertThat(waiting.getValue("r")["agentKey"]).isEqualTo("reviewer")
        val done = overrides("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$finished'")
        assertThat(done.getValue("r")).doesNotContainKey("agentKey")
        assertThat(query("select definition_snapshot ->> 'name' from workflow_run where id = '$running'") { it.getString(1) }).containsExactly("Shop flow")
    }

    @Test
    fun `the template columns are gone and the helper function too`() {
        val columns = query("select column_name from information_schema.columns where table_name = 'task_template'") { it.getString(1) }
        assertThat(columns).doesNotContain("agent_key", "model_profile_id").contains("requires_skills")
        assertThat(query("select count(*) from pg_proc where proname = 'adr0061_step_agents'") { it.getInt(1) }).containsExactly(0)
    }

    private fun task(id: String, templateKey: String, overrides: Map<String, Any?>? = emptyMap()): Map<String, Any?> = mapOf(
        "id" to id, "kind" to "TASK", "name" to id,
        "task" to (if (overrides == null) mapOf("templateKey" to templateKey) else mapOf("templateKey" to templateKey, "overrides" to overrides)),
    )

    private fun nodes(list: List<Map<String, Any?>>): String = json.writeValueAsString(list).replace("'", "''")

    private fun snapshot(list: List<Map<String, Any?>>): String =
        json.writeValueAsString(mapOf("name" to "Shop flow", "nodes" to list)).replace("'", "''")

    @Suppress("UNCHECKED_CAST")
    private fun nodesOf(sql: String): List<Map<String, Any?>> =
        json.readValue(query(sql) { it.getString(1) }.single(), List::class.java) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun overrides(sql: String): Map<String, Map<String, Any?>> = nodesOf(sql)
        .filter { it["task"] != null }
        .associate { node -> node["id"] as String to (((node["task"] as Map<String, Any?>)["overrides"] as? Map<String, Any?>) ?: emptyMap()) }

    private fun agent(key: String, model: UUID?, projectId: UUID?): String =
        "insert into agent (id, key, name, scope_type, project_id, model_profile_id, created_at, updated_at) values (gen_random_uuid(), " +
            "${q(key)}, ${q(key)}, ${q(if (projectId == null) "GLOBAL" else "PROJECT")}, ${q(projectId?.toString())}::uuid, ${q(model?.toString())}::uuid, now(), now())"

    private fun template(key: String, agentKey: String? = null, model: UUID? = null, extendsKey: String? = null, projectId: UUID? = null): String =
        "insert into task_template (id, key, name, agent_key, model_profile_id, extends_key, scope_type, project_id, created_at, updated_at) " +
            "values (gen_random_uuid(), ${q(key)}, ${q(key)}, ${q(agentKey)}, ${q(model?.toString())}::uuid, ${q(extendsKey)}, " +
            "${q(if (projectId == null) "GLOBAL" else "PROJECT")}, ${q(projectId?.toString())}::uuid, now(), now())"

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
