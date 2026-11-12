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
 * ADR 0063: V309 turns every step that pushed into the step plus a `DELIVER_CHANGES` node carrying its git settings (with
 * the edges rewired), moves step repositories into the workflow's sources and step setup commands into the workflow's,
 * names the replacements of the Spec Kit templates, and gives templates output files instead of `collectArtifacts`. The
 * data is like the owner's database: "Back Office: change from idea to reviewed MR" (an agent-task step with push,
 * merge request, branch and commit templates and a legacy sessionRole) and the seeded issue workflow with Spec Kit steps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V309MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val json = JsonMapper.builder().build()
    private val shop: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val idea: UUID = UUID.randomUUID()
    private val issue: UUID = UUID.randomUUID()
    private val multi: UUID = UUID.randomUUID()
    private val library: UUID = UUID.randomUUID()
    private val running: UUID = UUID.randomUUID()
    private val finished: UUID = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("308").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    // the tables of the projects module after V122 (Flyway runs the modules out of order)
                    "create table project (id uuid primary key, repositories jsonb not null)",
                    "create table workflow_definition (id uuid primary key, project_id uuid not null, nodes jsonb not null, edges jsonb not null, " +
                        "sources jsonb not null default '[]'::jsonb, setup_command text)",
                    "create table library_workflow_version (library_workflow_id uuid not null, version integer not null, nodes jsonb not null, " +
                        "edges jsonb not null, source_project_id uuid, sources jsonb not null default '[]'::jsonb, setup_command text)",
                    "create table workflow_run (id uuid primary key, project_id uuid not null, status text not null, definition_snapshot jsonb not null)",
                    "insert into project values ('$shop', '${write(listOf(mapOf("key" to "app", "primary" to true), mapOf("key" to "docs", "primary" to false)))}'::jsonb)",
                    // the built-ins as the owner's database holds them (seeded before ADR 0063)
                    template(
                        "implement-opencode",
                        mapOf(
                            "verifyCommand" to null, "branchTemplate" to "feature/{{issue.key | lower}}", "push" to true, "mergeRequest" to true,
                            "mergeRequestTitleTemplate" to "{{issue.key}}: {{issue.summary}}", "mergeRequestDraft" to false,
                        ),
                    ),
                    template("implement-task-list", mapOf("collectArtifacts" to listOf("work-log.md"), "verifyCommand" to "tail work-log.md"), extendsKey = "implement-opencode"),
                    template(
                        "agent-task",
                        mapOf(
                            "branchTemplate" to "agent/{{issue.key | lower}}", "push" to false, "mergeRequest" to true, "mergeRequestDraft" to false,
                            "mergeRequestTitleTemplate" to "{{issue.key}}: {{issue.summary}}", "codingAgent" to true, "repositoryOptional" to true,
                        ),
                    ),
                    template("spec-kit-specify", mapOf("phase" to "SPECIFY")),
                    template("spec-kit-plan", mapOf("phase" to "PLAN")),
                    template("code-review", emptyMap()),
                    // a user template that only pushed, and one that lists its artefacts as text
                    template("pusher", mapOf("push" to "true", "setupCommand" to "make")),
                    template("reporter", mapOf("collectArtifacts" to "a.md, b.md ,", "codingAgent" to true)),
                    "insert into workflow_definition values ('$idea', '$shop', '${write(ideaNodes())}'::jsonb, '${write(ideaEdges())}'::jsonb, '[]'::jsonb, null)",
                    "insert into workflow_definition values ('$issue', '$shop', '${write(issueNodes())}'::jsonb, '${write(issueEdges())}'::jsonb, '[]'::jsonb, 'npm ci')",
                    "insert into workflow_definition values ('$multi', '$shop', '${write(multiNodes())}'::jsonb, '${write(multiEdges())}'::jsonb, '[]'::jsonb, 'make deps')",
                    "insert into library_workflow_version values ('$library', 1, '${write(multiNodes())}'::jsonb, '${write(multiEdges())}'::jsonb, '$shop', '[]'::jsonb, null)",
                    "insert into workflow_run values ('$running', '$shop', 'WAITING', '${snapshot(ideaNodes(), ideaEdges())}'::jsonb)",
                    "insert into workflow_run values ('$finished', '$shop', 'SUCCEEDED', '${snapshot(ideaNodes(), ideaEdges())}'::jsonb)",
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    /** "Back Office: change from idea to reviewed MR" of the owner's database. */
    private fun ideaNodes(): List<Map<String, Any?>> = listOf(
        mapOf("id" to "start", "kind" to "START", "name" to "Start", "position" to mapOf("x" to 40, "y" to 40)),
        task(
            "implement", "agent-task", x = 316,
            overrides = mapOf(
                "agentKey" to "claude-code",
                "config" to mapOf(
                    "push" to true, "mergeRequest" to true, "branchTemplate" to "bo/{{run.short}}", "commitSubjectTemplate" to "{{issue.key}}: {{issue.summary}}",
                    "mergeRequestTitleTemplate" to "BO: {{issue.summary}}", "sessionRole" to "implementer",
                ),
            ),
        ),
        task("review", "code-review", x = 652, overrides = mapOf("agentKey" to "reviewer")),
        mapOf("id" to "failed", "kind" to "END", "name" to "Failed", "end" to mapOf("outcome" to "FAILURE")),
        mapOf("id" to "done", "kind" to "END", "name" to "Done", "end" to mapOf("outcome" to "SUCCESS")),
    )

    private fun ideaEdges(): List<Map<String, Any?>> = listOf(
        edge("e1", "start", "out", "implement"),
        edge("e2", "implement", "success", "review"),
        edge("e3", "implement", "failure", "failed"),
        edge("e4", "review", "success", "done"),
        edge("e5", "review", "failure", "implement"),
    )

    /** The seeded "Issue to reviewed MR" before ADR 0063: Spec Kit steps (no push key, they pushed by default) and implement-opencode. */
    private fun issueNodes(): List<Map<String, Any?>> = listOf(
        mapOf("id" to "start", "kind" to "START", "name" to "Start"),
        task("specify", "spec-kit-specify", overrides = mapOf("agentKey" to "opencode")),
        task("plan", "spec-kit-plan", overrides = mapOf("agentKey" to "opencode", "config" to mapOf("phase" to "PLAN"))),
        task("implement", "implement-opencode", x = 2668, overrides = mapOf("agentKey" to "opencode")),
        task("implement-deliver", "code-review", overrides = mapOf("agentKey" to "reviewer")),
        mapOf("id" to "done", "kind" to "END", "name" to "Done", "end" to mapOf("outcome" to "SUCCESS")),
    )

    private fun issueEdges(): List<Map<String, Any?>> = listOf(
        edge("e1", "start", "out", "specify"),
        edge("e2", "specify", "success", "plan"),
        edge("e3", "plan", "success", "implement"),
        edge("e4", "implement", "success", "implement-deliver"),
        edge("e5", "implement-deliver", "success", "done"),
    )

    /** Steps with their own repositories and setup commands, a template that pushes, a step that prepares only. */
    private fun multiNodes(): List<Map<String, Any?>> = listOf(
        mapOf("id" to "start", "kind" to "START", "name" to "Start"),
        task(
            "docs", "pusher",
            overrides = mapOf(
                "config" to mapOf(
                    "repositories" to listOf(mapOf("key" to "docs", "access" to "PUSH_MR"), mapOf("key" to "standards", "library" to true, "access" to "READ")),
                    "setupCommand" to "npm ci", "collectArtifacts" to listOf("notes.md"),
                ),
            ),
        ),
        task("code", "implement-task-list"),
        task("prep", "pusher", overrides = mapOf("config" to mapOf("prepareOnly" to true, "setupCommand" to "make deps"))),
        task("read", "agent-task", overrides = mapOf("config" to mapOf("push" to false, "branchTemplate" to "x", "repositories" to listOf("docs")))),
        mapOf("id" to "done", "kind" to "END", "name" to "Done", "end" to mapOf("outcome" to "SUCCESS")),
    )

    private fun multiEdges(): List<Map<String, Any?>> = listOf(
        edge("e1", "start", "out", "docs"),
        edge("e2", "docs", "success", "code"),
        edge("e3", "code", "success", "prep"),
        edge("e4", "prep", "success", "read"),
        edge("e5", "read", "success", "done"),
    )

    @Test
    fun `a step that pushed becomes the step and a delivery with its git settings, the edges rewired`() {
        val nodes = nodesOf("select nodes::text from workflow_definition where id = '$idea'")
        assertThat(nodes.map { it["id"] }).containsExactly("start", "implement", "implement-deliver", "review", "failed", "done")
        val step = nodes.single { it["id"] == "implement" }
        assertThat(overrides(step)["config"]).describedAs("git keys leave the step, the legacy session role stays").isEqualTo(mapOf("sessionRole" to "implementer"))
        assertThat(overrides(step)["agentKey"]).isEqualTo("claude-code")
        val deliver = nodes.single { it["id"] == "implement-deliver" }
        assertThat(deliver["kind"]).isEqualTo("ACTION")
        assertThat(deliver["name"]).isEqualTo("Deliver changes")
        assertThat(deliver["position"]).isEqualTo(mapOf("x" to 484, "y" to 200))
        assertThat(deliver["maxVisits"]).isEqualTo(3)
        assertThat(deliver["action"]).isEqualTo(
            mapOf(
                "type" to "DELIVER_CHANGES",
                "config" to mapOf(
                    "branchTemplate" to "bo/{{run.short}}", "commitSubjectTemplate" to "{{issue.key}}: {{issue.summary}}", "mergeRequest" to true,
                    "mergeRequestTitleTemplate" to "BO: {{issue.summary}}", "mergeRequestDraft" to false,
                ),
            ),
        )
        val edges = edgesOf("select edges::text from workflow_definition where id = '$idea'").map { listOf(it["id"], it["from"], it["fromPort"], it["to"]) }
        assertThat(edges).containsExactlyInAnyOrder(
            listOf("e1", "start", "out", "implement"),
            listOf("e2", "implement-deliver", "success", "review"),
            listOf("e3", "implement", "failure", "failed"),
            listOf("e4", "review", "success", "done"),
            listOf("e5", "review", "failure", "implement"),
            listOf("implement-deliver-in", "implement", "success", "implement-deliver"),
            listOf("implement-deliver-failure", "implement-deliver", "failure", "failed"),
        )
    }

    @Test
    fun `the lineage pushes, Spec Kit steps name the replacements and get no delivery, a taken id gets a suffix`() {
        val nodes = nodesOf("select nodes::text from workflow_definition where id = '$issue'")
        assertThat(nodes.map { it["id"] }).containsExactly("start", "specify", "plan", "implement", "implement-deliver-2", "implement-deliver", "done")
        assertThat(templateKey(nodes, "specify")).isEqualTo("write-spec")
        assertThat(templateKey(nodes, "plan")).isEqualTo("write-plan")
        assertThat(overrides(nodes.single { it["id"] == "plan" })["config"]).describedAs("phase leaves the step").isEqualTo(emptyMap<String, Any?>())
        assertThat(templateKey(nodes, "implement")).isEqualTo("implement-opencode")
        assertThat((nodes.single { it["id"] == "implement-deliver-2" }["action"] as Map<*, *>)["config"]).isEqualTo(
            mapOf(
                "branchTemplate" to "feature/{{issue.key | lower}}", "mergeRequest" to true,
                "mergeRequestTitleTemplate" to "{{issue.key}}: {{issue.summary}}", "mergeRequestDraft" to false,
            ),
        )
        val edges = edgesOf("select edges::text from workflow_definition where id = '$issue'").map { listOf(it["from"], it["fromPort"], it["to"]) }
        assertThat(edges).contains(listOf("implement-deliver-2", "success", "implement-deliver"), listOf("implement", "success", "implement-deliver-2"))
        assertThat(edges).doesNotContain(listOf("specify", "success", "specify-deliver"))
        assertThat(query("select setup_command from workflow_definition where id = '$issue'") { it.getString(1) }).containsExactly("npm ci")
    }

    @Test
    fun `step repositories become the sources, step setup commands join the workflow's, only pushers get a delivery`() {
        val nodes = nodesOf("select nodes::text from workflow_definition where id = '$multi'")
        assertThat(nodes.map { it["id"] }).containsExactly("start", "docs", "docs-deliver", "code", "code-deliver", "prep", "read", "done")
        assertThat((nodes.single { it["id"] == "docs-deliver" }["action"] as Map<*, *>)["config"]).isEqualTo(mapOf("repositories" to listOf("docs")))
        assertThat(overrides(nodes.single { it["id"] == "docs" })["config"]).describedAs("collectArtifacts stays as extra output files")
            .isEqualTo(mapOf("collectArtifacts" to listOf("notes.md")))
        assertThat(overrides(nodes.single { it["id"] == "prep" })["config"]).isEqualTo(mapOf("prepareOnly" to true))
        assertThat(overrides(nodes.single { it["id"] == "read" })["config"]).isEqualTo(emptyMap<String, Any?>())
        // union in the order first seen (a plain key is a project repository); the primary joins first: implement-task-list relied on it
        assertThat(sources("select sources::text from workflow_definition where id = '$multi'")).containsExactly(
            mapOf("key" to "app", "library" to false), mapOf("key" to "docs", "library" to false), mapOf("key" to "standards", "library" to true),
        )
        assertThat(query("select setup_command from workflow_definition where id = '$multi'") { it.getString(1) }).containsExactly("make deps && npm ci")
    }

    @Test
    fun `library versions keep library sources only, unfinished runs are rewritten, finished runs keep their snapshot`() {
        assertThat(sources("select sources::text from library_workflow_version where version = 1")).containsExactly(mapOf("key" to "standards", "library" to true))
        assertThat(nodesOf("select nodes::text from library_workflow_version where version = 1").map { it["id"] }).contains("docs-deliver", "code-deliver")

        val waiting = nodesOf("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$running'")
        assertThat(waiting.map { it["id"] }).contains("implement-deliver")
        assertThat(edgesOf("select (definition_snapshot -> 'edges')::text from workflow_run where id = '$running'").map { it["id"] }).contains("implement-deliver-in")
        assertThat(query("select definition_snapshot ->> 'name' from workflow_run where id = '$running'") { it.getString(1) }).containsExactly("Idea flow")
        assertThat(query("select definition_snapshot -> 'sources' from workflow_run where id = '$running'") { it.getString(1) }).containsExactly("[]")
        val done = nodesOf("select (definition_snapshot -> 'nodes')::text from workflow_run where id = '$finished'")
        assertThat(done.map { it["id"] }).doesNotContain("implement-deliver")
    }

    @Test
    fun `templates get output files and lose the git keys - pushing or Spec Kit templates keep needing a coding agent`() {
        assertThat(config("implement-opencode")).isEqualTo(mapOf("verifyCommand" to null, "codingAgent" to true))
        assertThat(config("implement-task-list")).isEqualTo(mapOf("verifyCommand" to "tail work-log.md"))
        assertThat(outputFiles("implement-task-list")).containsExactly("work-log.md")
        assertThat(config("agent-task")).isEqualTo(mapOf("codingAgent" to true, "repositoryOptional" to true))
        assertThat(config("spec-kit-specify")).isEqualTo(mapOf("codingAgent" to true))
        assertThat(config("pusher")).isEqualTo(mapOf("codingAgent" to true))
        assertThat(outputFiles("reporter")).containsExactly("a.md", "b.md")
        assertThat(config("reporter")).isEqualTo(mapOf("codingAgent" to true))
        assertThat(outputFiles("code-review")).isEmpty()
        assertThat(query("select count(*) from pg_proc where proname like 'adr0063%'") { it.getInt(1) }).containsExactly(0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun config(key: String): Map<String, Any?> =
        json.readValue(query("select config::text from task_template where key = '$key'") { it.getString(1) }.single(), Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun outputFiles(key: String): List<String> =
        json.readValue(query("select output_files::text from task_template where key = '$key'") { it.getString(1) }.single(), List::class.java) as List<String>

    private fun task(id: String, templateKey: String, x: Int = 0, overrides: Map<String, Any?>? = null): Map<String, Any?> = mapOf(
        "id" to id, "kind" to "TASK", "name" to id, "position" to mapOf("x" to x, "y" to 40),
        "task" to (if (overrides == null) mapOf("templateKey" to templateKey) else mapOf("templateKey" to templateKey, "overrides" to overrides)),
    )

    private fun edge(id: String, from: String, port: String, to: String) = mapOf("id" to id, "from" to from, "fromPort" to port, "to" to to)

    private fun write(value: Any): String = json.writeValueAsString(value).replace("'", "''")

    private fun snapshot(nodes: List<Map<String, Any?>>, edges: List<Map<String, Any?>>): String =
        write(mapOf("name" to "Idea flow", "nodes" to nodes, "edges" to edges))

    @Suppress("UNCHECKED_CAST")
    private fun nodesOf(sql: String): List<Map<String, Any?>> =
        json.readValue(query(sql) { it.getString(1) }.single(), List::class.java) as List<Map<String, Any?>>

    private fun edgesOf(sql: String): List<Map<String, Any?>> = nodesOf(sql)

    private fun sources(sql: String): List<Map<String, Any?>> = nodesOf(sql)

    @Suppress("UNCHECKED_CAST")
    private fun overrides(node: Map<String, Any?>): Map<String, Any?> = ((node["task"] as Map<String, Any?>)["overrides"] as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun templateKey(nodes: List<Map<String, Any?>>, id: String): Any? = (nodes.single { it["id"] == id }["task"] as Map<String, Any?>)["templateKey"]

    private fun template(key: String, config: Map<String, Any?>, extendsKey: String? = null): String =
        "insert into task_template (id, key, name, config, extends_key, scope_type, built_in, created_at, updated_at) " +
            "values (gen_random_uuid(), ${q(key)}, ${q(key)}, ${q(json.writeValueAsString(config))}::jsonb, ${q(extendsKey)}, 'GLOBAL', true, now(), now())"

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
