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
 * ADR 0064: V310 turns agent MCP servers into platform connectors the agents list, and the step setting agentTools into the
 * project connector gitlab-read. The data is like the owner's database: the workflows "Back Office: ask the agent",
 * "Back Office: watch the development" and "Back Office: change from idea to reviewed MR" whose steps use agentTools
 * gitlab-read, an agent with an MCP server, a platform connector already holding one of the keys.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V310MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    private val json = JsonMapper.builder().build()
    private val backOffice: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val quiet: UUID = UUID.randomUUID()
    private val libraryOnly: UUID = UUID.randomUUID()
    private val runOnly: UUID = UUID.randomUUID()
    private val own: UUID = UUID.randomUUID()
    private val ask: UUID = UUID.randomUUID()
    private val watch: UUID = UUID.randomUUID()
    private val idea: UUID = UUID.randomUUID()
    private val plain: UUID = UUID.randomUUID()
    private val running: UUID = UUID.randomUUID()
    private val finished: UUID = UUID.randomUUID()
    private val researcher: UUID = UUID.randomUUID()
    private val second: UUID = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        flyway("309").migrate()
        connection().use { c ->
            c.createStatement().use { s ->
                listOf(
                    // the tables of the projects module after V123 (Flyway runs the modules out of order)
                    "create table project (id uuid primary key, repositories jsonb not null default '[]'::jsonb)",
                    "create table workflow_definition (id uuid primary key, project_id uuid not null, name text, nodes jsonb not null, edges jsonb not null default '[]'::jsonb)",
                    "create table library_workflow_version (library_workflow_id uuid not null, version integer not null, nodes jsonb not null, source_project_id uuid)",
                    "create table workflow_run (id uuid primary key, project_id uuid not null, status text not null, definition_snapshot jsonb not null)",
                    """create table connector (id uuid primary key, key text not null, name text not null, description text not null default '', kind text not null,
                        project_id uuid references project (id) on delete cascade, url text, auth text not null default 'NONE', header_name text, repository text,
                        secret_key_id text, secret_wrapped_key text, secret_ciphertext text, last_test jsonb, created_at timestamptz not null, created_by jsonb,
                        updated_at timestamptz not null, updated_by jsonb)""",
                    "insert into project (id) values ('$backOffice'), ('$quiet'), ('$libraryOnly'), ('$runOnly'), ('$own')",
                    // the owner already made a platform connector "docs" for another server, and project own has its own gitlab-read
                    "insert into connector (id, key, name, kind, url, created_at, updated_at) values (gen_random_uuid(), 'docs', 'Docs', 'MCP_HTTP', 'https://other.example.com/mcp', now(), now())",
                    "insert into connector (id, key, name, kind, url, auth, created_at, updated_at) values (gen_random_uuid(), 'wiki', 'Wiki', 'MCP_HTTP', 'https://wiki.example.com/mcp', 'NONE', now(), now())",
                    "insert into connector (id, key, name, kind, project_id, created_at, updated_at) values (gen_random_uuid(), 'gitlab-read', 'Mine', 'GITLAB_READ', '$own', now(), now())",
                    agent(researcher, "researcher", listOf(mapOf("name" to "docs", "url" to "https://mcp.example.com/docs"), mapOf("name" to "wiki", "url" to "https://wiki.example.com/mcp"))),
                    agent(second, "second", listOf(mapOf("name" to "docs", "url" to "https://mcp.example.com/docs"), mapOf("name" to "BAD NAME", "url" to "https://x.example"))),
                    workflow(ask, backOffice, "Back Office: ask the agent", listOf(step("answer", "agent-task", mapOf("agentTools" to listOf("gitlab-read"), "allowQuestions" to true)))),
                    workflow(watch, backOffice, "Back Office: watch the development", listOf(step("watch", "agent-task", mapOf("agentTools" to listOf("gitlab-read"))), step("digest", "llm-task", null))),
                    workflow(idea, backOffice, "Back Office: change from idea to reviewed MR", listOf(step("implement", "agent-task", mapOf("agentTools" to "gitlab-read, other", "verifyCommand" to "make")))),
                    workflow(plain, quiet, "Quiet", listOf(step("implement", "agent-task", mapOf("agentTools" to emptyList<String>())))),
                    workflow(UUID.randomUUID(), own, "Own", listOf(step("implement", "agent-task", mapOf("agentTools" to listOf("gitlab-read"))))),
                    "insert into library_workflow_version values (gen_random_uuid(), 1, ${q(write(listOf(step("x", "agent-task", mapOf("agentTools" to listOf("gitlab-read"))))))}::jsonb, '$libraryOnly')",
                    "insert into workflow_run values ('$running', '$runOnly', 'WAITING', ${q(snapshot(listOf(step("x", "agent-task", mapOf("agentTools" to listOf("gitlab-read"))))))}::jsonb)",
                    "insert into workflow_run values ('$finished', '$quiet', 'SUCCEEDED', ${q(snapshot(listOf(step("x", "agent-task", mapOf("agentTools" to listOf("gitlab-read"))))))}::jsonb)",
                    "update task_template set config = config || '{\"agentTools\":[\"gitlab-read\"]}'::jsonb where key = (select min(key) from task_template)",
                ).forEach { s.executeUpdate(it) }
            }
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `agent MCP servers become platform connectors the agents list, reusing an equal one and avoiding a taken key`() {
        assertThat(columns("agent")).contains("connectors").doesNotContain("mcp_servers")
        assertThat(strings("select connectors::text from agent where id = '$researcher'").single()).isEqualTo("""["docs-2", "wiki"]""")
        assertThat(strings("select connectors::text from agent where id = '$second'").single()).describedAs("the same server again: the same connector; an invalid name is dropped")
            .isEqualTo("""["docs-2"]""")
        val platform = query("select key, kind, url, auth, created_by ->> 'kind' from connector where project_id is null order by key") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
        assertThat(platform).containsExactly(
            listOf("docs", "MCP_HTTP", "https://other.example.com/mcp", "NONE", null),
            listOf("docs-2", "MCP_HTTP", "https://mcp.example.com/docs", "NONE", "SYSTEM"),
            listOf("wiki", "MCP_HTTP", "https://wiki.example.com/mcp", "NONE", null),
        )
        assertThat(strings("select connectors::text from agent where key = 'opencode'").single()).describedAs("built-ins get gitlab-read from the seed").isEqualTo("[]")
    }

    @Test
    fun `projects whose steps used gitlab-read get the project connector, agentTools leaves every step and template`() {
        val gitlab = query("select project_id, kind, repository, auth from connector where key = 'gitlab-read' order by project_id") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        assertThat(gitlab).containsExactlyInAnyOrder(
            listOf(backOffice.toString(), "GITLAB_READ", null, "NONE"),
            listOf(libraryOnly.toString(), "GITLAB_READ", null, "NONE"),
            listOf(runOnly.toString(), "GITLAB_READ", null, "NONE"),
            listOf(own.toString(), "GITLAB_READ", null, "NONE"),
        )
        assertThat(strings("select name from connector where key = 'gitlab-read' and project_id = '$own'")).describedAs("an existing one is kept").containsExactly("Mine")
        assertThat(strings("select name from connector where key = 'gitlab-read' and project_id = '$backOffice'")).containsExactly("GitLab (read only)")
        assertThat(strings("select id::text from connector where project_id = '$quiet'")).describedAs("an empty list and a finished run are no use").isEmpty()

        assertThat(strings("select nodes::text from workflow_definition") + strings("select nodes::text from library_workflow_version"))
            .allSatisfy { assertThat(it).doesNotContain("agentTools") }
        assertThat(strings("select definition_snapshot::text from workflow_run where id = '$running'").single()).doesNotContain("agentTools").contains("\"name\": \"Flow\"")
        assertThat(strings("select definition_snapshot::text from workflow_run where id = '$finished'").single()).describedAs("finished runs keep their history").contains("agentTools")
        val idea = strings("select (nodes -> 0 -> 'task' -> 'overrides' -> 'config')::text from workflow_definition where id = '$idea'").single()
        assertThat(idea).describedAs("other keys stay").contains("verifyCommand").doesNotContain("agentTools")
        val ask = strings("select (nodes -> 0 -> 'task' -> 'overrides' -> 'config')::text from workflow_definition where id = '$ask'").single()
        assertThat(ask).contains("allowQuestions")
        assertThat(strings("select (nodes -> 1)::text from workflow_definition where id = '$watch'").single()).describedAs("a step without overrides is untouched").doesNotContain("overrides")
        assertThat(strings("select config::text from task_template where config ? 'agentTools'")).isEmpty()
        assertThat(strings("select proname from pg_proc where proname like 'adr0064%'")).isEmpty()
    }

    private fun agent(id: UUID, key: String, servers: List<Map<String, Any?>>) =
        "insert into agent (id, key, name, scope_type, created_at, updated_at, mcp_servers) values ('$id', ${q(key)}, ${q(key)}, 'GLOBAL', now(), now(), ${q(write(servers))}::jsonb)"

    private fun workflow(id: UUID, project: UUID, name: String, nodes: List<Map<String, Any?>>) =
        "insert into workflow_definition (id, project_id, name, nodes) values ('$id', '$project', ${q(name)}, ${q(write(nodes))}::jsonb)"

    private fun step(id: String, templateKey: String, config: Map<String, Any?>?): Map<String, Any?> = mapOf(
        "id" to id, "kind" to "TASK", "name" to id,
        "task" to (if (config == null) mapOf("templateKey" to templateKey) else mapOf("templateKey" to templateKey, "overrides" to mapOf("agentKey" to "claude-code", "config" to config))),
    )

    private fun snapshot(nodes: List<Map<String, Any?>>) = write(mapOf("name" to "Flow", "nodes" to nodes, "edges" to emptyList<Any>()))

    private fun write(value: Any): String = json.writeValueAsString(value)

    private fun q(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun columns(table: String) = strings("select column_name from information_schema.columns where table_name = '$table'")

    private fun strings(sql: String): List<String?> = query(sql) { it.getString(1) }

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
