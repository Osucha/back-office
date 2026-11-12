package pl.mos.backoffice.projects.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.mos.backoffice.projects.application.port.`in`.WorkflowJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowNodeJson
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.testing.TestAgents
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V121 (ADR 0060) on a database with project workspaces (V118): the owner's workspace `bo` (sources [main], the primary
 * repository; roles implementer / advisor; no setup) used by "Back Office: ask the agent" and "Back Office: change from
 * idea to reviewed MR" keeps those workflows unchanged (the default repository is the same, `sessionRole` keeps the
 * sessions); a workspace with a setup command and more sources passes them to the workflows and steps that named it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunDirectoryMigrationTest {

    private val postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @AfterAll
    fun stop() = postgres.stop()

    private fun flyway(target: String?) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .outOfOrder(true)
        .apply { if (target != null) target(target) }
        .load()

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun step(id: String, template: String, config: Map<String, Any?>) =
        mapOf("id" to id, "kind" to "TASK", "name" to id, "task" to mapOf("templateKey" to template, "overrides" to mapOf("config" to config)))

    private fun Connection.exec(sql: String, vararg params: Any?) = prepareStatement(sql).use { s ->
        params.forEachIndexed { i, p -> s.setObject(i + 1, p) }
        s.executeUpdate()
    }

    private fun Connection.workflow(project: UUID, name: String, nodes: List<Map<String, Any?>>): UUID = UUID.randomUUID().also { id ->
        exec(
            "INSERT INTO workflow_definition (id, project_id, name, nodes, edges, triggers, created_at, updated_at) " +
                "VALUES (?, ?, ?, CAST(? AS jsonb), '[]'::jsonb, '[]'::jsonb, now(), now())",
            id, project, name, json.writeValueAsString(listOf(mapOf("id" to "start", "kind" to "START", "name" to "Start")) + nodes),
        )
    }

    private fun Connection.workspace(project: UUID, key: String, sources: List<Map<String, Any?>>, setup: String?, roles: List<String>) = exec(
        "INSERT INTO project_workspace (id, project_id, key, name, sources, setup_command, roles, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), now(), now())",
        UUID.randomUUID(), project, key, key, json.writeValueAsString(sources), setup, json.writeValueAsString(roles),
    )

    @Test
    fun `workspaces become setup commands and step repositories, the owner's workflows stay as they were`() {
        flyway("120").migrate()
        val project = UUID.randomUUID()
        val bo = mutableMapOf<String, UUID>()
        val multi: UUID
        connection().use { c ->
            c.exec(
                "INSERT INTO project (id, key, name, repo_url, ci_entrypoints, repositories, created_at, updated_at) " +
                    "VALUES (?, 'BO', 'Back Office', 'https://git.example.com/bo.git', '[]'::jsonb, CAST(? AS jsonb), now(), now())",
                project,
                json.writeValueAsString(
                    listOf(
                        mapOf("key" to "main", "url" to "https://git.example.com/bo.git", "defaultBranch" to "develop", "purpose" to "SOURCE", "primary" to true),
                        mapOf("key" to "docs", "url" to "https://git.example.com/docs.git", "defaultBranch" to "main", "purpose" to "DOCS", "primary" to false),
                    ),
                ),
            )
            // the library (tasks context) as far as the migration reads it: templates with agent and config, agents with harness
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE IF NOT EXISTS task_template (id uuid PRIMARY KEY, key text, project_id uuid, extends_key text, agent_key text, " +
                        "config jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now())",
                )
                s.execute("CREATE TABLE IF NOT EXISTS agent (id uuid PRIMARY KEY, key text, project_id uuid, harness text)")
                s.execute(
                    "INSERT INTO task_template (id, key, agent_key, config) VALUES " +
                        "(gen_random_uuid(), 'agent-task', 'claude-code', '{\"push\": false}'), " +
                        "(gen_random_uuid(), 'implement', 'opencode', '{\"push\": true}'), " +
                        "(gen_random_uuid(), 'my-implement', NULL, '{}'), " +
                        "(gen_random_uuid(), 'write-story', 'model-answer', '{}')",
                )
                s.execute("UPDATE task_template SET extends_key = 'implement' WHERE key = 'my-implement'")
                s.execute(
                    "INSERT INTO agent (id, key, harness) VALUES (gen_random_uuid(), 'claude-code', 'CLAUDE_CODE'), " +
                        "(gen_random_uuid(), 'opencode', 'OPENCODE'), (gen_random_uuid(), 'model-answer', NULL)",
                )
            }
            c.workspace(project, "bo", listOf(mapOf("key" to "main")), null, listOf("implementer", "advisor"))
            c.workspace(
                project, "multi", listOf(mapOf("key" to "docs"), mapOf("key" to "main"), mapOf("key" to "standards", "library" to true)), "npm ci", emptyList(),
            )
            bo["ask"] = c.workflow(project, "Back Office: ask the agent", listOf(step("ask", "agent-task", mapOf("workspace" to "bo", "sessionRole" to "advisor"))))
            bo["change"] = c.workflow(
                project, "Back Office: change from idea to reviewed MR",
                listOf(
                    step("implement", "implement", mapOf("workspace" to "bo", "sessionRole" to "implementer")),
                    step("advise", "agent-task", mapOf("workspace" to "bo", "sessionRole" to "advisor")),
                ),
            )
            multi = c.workflow(
                project, "multi",
                listOf(
                    step("build", "my-implement", mapOf("workspace" to "multi")),
                    step("look", "agent-task", mapOf("workspace" to "multi")),
                    step("own", "implement", mapOf("workspace" to "multi", "repositories" to listOf(mapOf("key" to "docs", "access" to "READ")))),
                    step("story", "write-story", mapOf("workspace" to "multi")),
                    step("forced", "agent-task", mapOf("workspace" to "multi", "push" to true)),
                ),
            )
        }

        flyway(null).migrate()

        connection().use { c ->
            fun read(id: UUID): Pair<String?, List<WorkflowNode>> = c.prepareStatement("SELECT setup_command, nodes::text FROM workflow_definition WHERE id = ?").use { s ->
                s.setObject(1, id)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1) to json.readValue(rs.getString(2), Array<WorkflowNodeJson>::class.java).map(WorkflowJson::toDomain)
                }
            }
            fun config(nodes: List<WorkflowNode>, id: String) = (nodes.single { it.id == id } as WorkflowNode.Task).task.overrides.config

            // the owner's workflows: no setup, no repositories written, sessions by their old roles
            val (askSetup, askNodes) = read(bo.getValue("ask"))
            assertThat(askSetup).isNull()
            assertThat(config(askNodes, "ask")).isEqualTo(mapOf("sessionRole" to "advisor"))
            val (changeSetup, changeNodes) = read(bo.getValue("change"))
            assertThat(changeSetup).isNull()
            assertThat(config(changeNodes, "implement")).doesNotContainKeys("workspace", "repositories")
            assertThat(RunDirectory.sessionRole("implement", RunDirectory.of(config(changeNodes, "implement"), TestAgents.OPENCODE, runnerStep = true), "opencode")).isEqualTo("implementer")
            assertThat(RunDirectory.sessionRole("advise", RunDirectory.of(config(changeNodes, "advise"), TestAgents.OPENCODE, runnerStep = true), "opencode")).isEqualTo("advisor")

            // a workspace with a setup command and several sources
            val (setup, nodes) = read(multi)
            assertThat(setup).isEqualTo("npm ci")
            assertThat(config(nodes, "build")["repositories"]).describedAs("pushes (inherited): the project's primary gets PUSH_MR").isEqualTo(
                listOf(
                    mapOf("key" to "docs", "access" to "READ"), mapOf("key" to "main", "access" to "PUSH_MR"),
                    mapOf("key" to "standards", "access" to "READ", "library" to true),
                ),
            )
            assertThat((config(nodes, "look")["repositories"] as List<*>).map { (it as Map<*, *>)["access"] }).describedAs("push false: all READ")
                .containsOnly("READ")
            assertThat((config(nodes, "forced")["repositories"] as List<*>).single { (it as Map<*, *>)["key"] == "main" })
                .isEqualTo(mapOf("key" to "main", "access" to "PUSH_MR"))
            assertThat(config(nodes, "own")["repositories"]).describedAs("own repositories win").isEqualTo(listOf(mapOf("key" to "docs", "access" to "READ")))
            assertThat(config(nodes, "story")).describedAs("a model answer ignored its workspace").doesNotContainKey("repositories")

            c.createStatement().use { s ->
                s.executeQuery("SELECT to_regclass('project_workspace')").use { rs -> rs.next(); assertThat(rs.getString(1)).isNull() }
            }
            // an agent-questions approval can be stored
            c.prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'approval_request_kind_check'").use { s ->
                s.executeQuery().use { rs -> rs.next(); assertThat(rs.getString(1)).contains("AGENT_QUESTIONS") }
            }
        }
    }
}
