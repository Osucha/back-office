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
 * ADR 0062: V308 turns template prompts into one instruction text and template run inputs into parameters that every step
 * of such a template binds `START` (start forms stay the same), rewrites the legacy step keys (prompt appends, the
 * replacement, step parameter values, session and directory) in stored workflows, library versions and the snapshots of
 * unfinished runs, and gives agents a session. The data is like the owner's database after ADR 0061: templates
 * `llm-task` and `agent-task` with the run input `input`, and workflows using them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V308MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val json = JsonMapper.builder().build()
    private val shop: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val workflow: UUID = UUID.randomUUID()
    private val library: UUID = UUID.randomUUID()
    private val running: UUID = UUID.randomUUID()
    private val finished: UUID = UUID.randomUUID()

    private val input = """{"name": "input", "label": "Input material", "type": "MULTILINE", "required": false, "help": "Text the agent steps should read"}"""

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("307").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    "create table workflow_definition (id uuid primary key, project_id uuid not null, nodes jsonb not null)",
                    "create table library_workflow_version (library_workflow_id uuid not null, version integer not null, nodes jsonb not null, source_project_id uuid)",
                    "create table workflow_run (id uuid primary key, project_id uuid not null, status text not null, definition_snapshot jsonb not null)",
                    // the built-ins of the owner's database (seeded before ADR 0062)
                    template(
                        "llm-task", system = "You are a careful assistant.\n{{#runInputText}}\n## Run input\n{{runInputText}}\n{{/runInputText}}",
                        user = "{{params.task}}", runInputs = "[$input]",
                        parameters = """[{"name": "task", "label": "Task", "type": "MULTILINE", "required": false, "default": "Summarise"}]""",
                    ),
                    template("agent-task", system = "  You are a coding agent.  ", user = "{{params.task}}", runInputs = "[$input]"),
                    template("base-agent", system = "{{#runInputText}}{{runInputText}}{{/runInputText}}", runInputs = "[$input]"),
                    // a user template: inherits the input, declares its own ISSUE_KEY input with a dash and one clashing with a parameter
                    template(
                        "story", extendsKey = "base-agent", user = "Write the story.", mode = "REPLACE",
                        runInputs = """[{"name": "jira-key", "label": "Jira key", "type": "ISSUE_KEY", "required": true},
                            {"name": "tone", "label": "Tone", "type": "TEXT"}, {"name": "", "label": "No name"}]""",
                        parameters = """[{"name": "tone", "label": "Voice", "type": "CHOICE", "options": ["dry", "warm"], "required": false}]""",
                    ),
                    template("blank", system = "   ", user = null),
                    "insert into workflow_definition values ('$workflow', '$shop', '${nodes(shopNodes())}'::jsonb)",
                    "insert into library_workflow_version values ('$library', 1, '${nodes(listOf(task("l", "llm-task")))}'::jsonb, '$shop')",
                    "insert into workflow_run values ('$running', '$shop', 'WAITING', '${snapshot(listOf(task("a", "agent-task", mapOf("promptAppend" to "go"))))}'::jsonb)",
                    "insert into workflow_run values ('$finished', '$shop', 'SUCCEEDED', '${snapshot(listOf(task("a", "agent-task", mapOf("promptAppend" to "go"))))}'::jsonb)",
                    "insert into agent (id, key, name, scope_type, created_at, updated_at) values (gen_random_uuid(), 'old-agent', 'Old', 'GLOBAL', now(), now())",
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    private fun shopNodes(): List<Map<String, Any?>> = listOf(
        mapOf("id" to "start", "kind" to "START", "name" to "Start"),
        task("summary", "llm-task", mapOf("agentKey" to "model-answer", "parameters" to mapOf("task" to "Summarise the input", "blank" to " ", "none" to null))),
        task(
            "code", "agent-task",
            mapOf(
                "agentKey" to "claude-code", "systemPromptAppend" to "Be careful.", "promptAppend" to "Then report.", "promptReplace" to "Fix the bug.",
                "config" to mapOf("push" to true, "session" to "CONTINUE", "continueSession" to "summary", "directory" to "SEPARATE", "sessionRole" to "dev"),
            ),
        ),
        task("bound", "llm-task", mapOf("parameters" to mapOf("input" to "fixed material"))),
        task("story", "story", null),
        task("current", "agent-task", mapOf("instructionsAppend" to "Already new.", "parameterBindings" to mapOf("input" to mapOf("source" to "VALUE", "value" to "x")))),
        mapOf("id" to "fetch", "kind" to "ACTION", "name" to "Fetch", "action" to mapOf("type" to "FETCH_ISSUE", "config" to emptyMap<String, Any?>())),
    )

    @Test
    fun `template prompts become one instruction text with the mode, blank parts skipped`() {
        assertThat(instructions("llm-task")).isEqualTo("You are a careful assistant.\n{{#runInputText}}\n## Run input\n{{runInputText}}\n{{/runInputText}}\n\n{{params.task}}")
        assertThat(instructions("agent-task")).describedAs("each part trimmed").isEqualTo("You are a coding agent.\n\n{{params.task}}")
        assertThat(instructions("story")).isEqualTo("Write the story.")
        assertThat(query("select instructions_mode from task_template where key = 'story'") { it.getString(1) }).containsExactly("REPLACE")
        assertThat(query("select instructions_mode from task_template where key = 'llm-task'") { it.getString(1) }).containsExactly("APPEND")
        assertThat(instructions("blank")).isNull()
    }

    @Test
    fun `template run inputs become parameters without a default, after the declared ones, names with dashes as underscores`() {
        val llm = parameters("llm-task")
        assertThat(llm.map { it["name"] }).containsExactly("task", "input")
        assertThat(llm[1]).isEqualTo(
            mapOf("name" to "input", "label" to "Input material", "type" to "MULTILINE", "required" to false, "help" to "Text the agent steps should read"),
        )
        val story = parameters("story")
        assertThat(story.map { it["name"] }).describedAs("the declared tone parameter wins; a nameless input is dropped").containsExactly("tone", "jira_key")
        assertThat(story[0]["label"]).isEqualTo("Voice")
        assertThat(story[1]).isEqualTo(mapOf("name" to "jira_key", "label" to "Jira key", "type" to "ISSUE_KEY", "required" to true))
        assertThat(parameters("blank")).isEmpty()
    }

    @Test
    fun `steps bind the run inputs of their lineage START, legacy values become VALUE bindings`() {
        val steps = overrides("select nodes::text from workflow_definition where id = '$workflow'")
        assertThat(steps.getValue("summary")["parameterBindings"]).isEqualTo(
            mapOf("task" to mapOf("source" to "VALUE", "value" to "Summarise the input"), "input" to mapOf("source" to "START")),
        )
        assertThat(steps.getValue("summary")).doesNotContainKey("parameters")
        assertThat(steps.getValue("bound")["parameterBindings"]).describedAs("a step value wins over the START binding")
            .isEqualTo(mapOf("input" to mapOf("source" to "VALUE", "value" to "fixed material")))
        assertThat(steps.getValue("story")["parameterBindings"]).describedAs("own and inherited run inputs, overrides created")
            .isEqualTo(mapOf("jira_key" to mapOf("source" to "START"), "tone" to mapOf("source" to "START"), "input" to mapOf("source" to "START")))
        assertThat(steps.getValue("current")["parameterBindings"]).describedAs("an existing binding stays")
            .isEqualTo(mapOf("input" to mapOf("source" to "VALUE", "value" to "x")))
    }

    @Test
    fun `prompt appends and the replacement become the instruction notes and replacement, step session keys go`() {
        val code = overrides("select nodes::text from workflow_definition where id = '$workflow'").getValue("code")
        assertThat(code["instructionsAppend"]).isEqualTo("Be careful.\n\nThen report.")
        assertThat(code["instructionsReplace"]).isEqualTo("Fix the bug.")
        assertThat(code).doesNotContainKeys("systemPromptAppend", "promptAppend", "promptReplace")
        assertThat(code["config"]).describedAs("sessionRole stays (legacy shared session)").isEqualTo(mapOf("push" to true, "sessionRole" to "dev"))
        assertThat(code["agentKey"]).isEqualTo("claude-code")
        val current = overrides("select nodes::text from workflow_definition where id = '$workflow'").getValue("current")
        assertThat(current["instructionsAppend"]).isEqualTo("Already new.")
        val nodes = nodesOf("select nodes::text from workflow_definition where id = '$workflow'")
        assertThat(nodes.first()).describedAs("other nodes untouched").isEqualTo(mapOf("id" to "start", "kind" to "START", "name" to "Start"))
        assertThat(nodes.last()["action"]).isEqualTo(mapOf("type" to "FETCH_ISSUE", "config" to emptyMap<String, Any?>()))
    }

    @Test
    fun `library versions and unfinished runs are rewritten, finished runs keep their snapshot`() {
        val lib = overrides("select nodes::text from library_workflow_version where version = 1")
        assertThat(lib.getValue("l")["parameterBindings"]).isEqualTo(mapOf("input" to mapOf("source" to "START")))
        val waiting = overrides("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$running'")
        assertThat(waiting.getValue("a")["instructionsAppend"]).isEqualTo("go")
        assertThat(waiting.getValue("a")["parameterBindings"]).isEqualTo(mapOf("input" to mapOf("source" to "START")))
        val done = overrides("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$finished'")
        assertThat(done.getValue("a")).isEqualTo(mapOf("promptAppend" to "go"))
        assertThat(query("select definition_snapshot ->> 'name' from workflow_run where id = '$running'") { it.getString(1) }).containsExactly("Shop flow")
    }

    @Test
    fun `the prompt and run input columns and the helper functions are gone, agents have a session`() {
        val columns = query("select column_name from information_schema.columns where table_name = 'task_template'") { it.getString(1) }
        assertThat(columns).doesNotContain("prompt_system", "prompt_user", "prompt_mode", "run_inputs").contains("instructions", "instructions_mode", "parameters")
        assertThat(query("select count(*) from pg_proc where proname like 'adr0062%'") { it.getInt(1) }).containsExactly(0)
        assertThat(query("select session from agent where key = 'old-agent'") { it.getString(1) }).containsExactly("PER_STEP")
    }

    private fun instructions(key: String): String? = query("select instructions from task_template where key = '$key'") { it.getString(1) }.single()

    @Suppress("UNCHECKED_CAST")
    private fun parameters(key: String): List<Map<String, Any?>> =
        json.readValue(query("select parameters::text from task_template where key = '$key'") { it.getString(1) }.single(), List::class.java) as List<Map<String, Any?>>

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

    private fun template(
        key: String,
        system: String? = null,
        user: String? = null,
        mode: String = "APPEND",
        runInputs: String = "[]",
        parameters: String = "[]",
        extendsKey: String? = null,
    ): String =
        "insert into task_template (id, key, name, prompt_system, prompt_user, prompt_mode, run_inputs, parameters, extends_key, scope_type, built_in, created_at, updated_at) " +
            "values (gen_random_uuid(), ${q(key)}, ${q(key)}, ${q(system)}, ${q(user)}, ${q(mode)}, ${q(runInputs)}::jsonb, ${q(parameters)}::jsonb, " +
            "${q(extendsKey)}, 'GLOBAL', true, now(), now())"

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
