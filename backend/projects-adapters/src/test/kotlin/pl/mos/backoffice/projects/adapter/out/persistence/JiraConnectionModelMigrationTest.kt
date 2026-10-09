package pl.mos.backoffice.projects.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V110 (ADR 0035, ADR 0036) on a database written by the previous release: existing connections keep working (classic,
 * acknowledged), projects that use Jira get the least-privileged reader-or-writer as system connection and every other
 * candidate as additional connections, projects that use Spec Kit keep it on, and personal connections are possible.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JiraConnectionModelMigrationTest {

    private val postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun stop() = postgres.stop()

    private fun flyway(target: String?) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .outOfOrder(true)
        .apply { if (target != null) target(target) }
        .load()

    private fun db(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun Connection.project(id: UUID, key: String, jiraKey: String?, nodes: String = "[]") {
        prepareStatement(
            "INSERT INTO project (id, key, name, jira_project_key, ci_entrypoints, created_at, updated_at) VALUES (?, ?, ?, ?, '[]'::jsonb, now(), now())",
        ).use { s -> s.setObject(1, id); s.setString(2, key); s.setString(3, key); s.setString(4, jiraKey); s.executeUpdate() }
        prepareStatement(
            "INSERT INTO workflow_definition (id, project_id, name, nodes, edges, triggers, created_at, updated_at) " +
                "VALUES (?, ?, 'wf', CAST(? AS jsonb), '[]'::jsonb, '[]'::jsonb, now(), now())",
        ).use { s -> s.setObject(1, UUID.randomUUID()); s.setObject(2, id); s.setString(3, nodes); s.executeUpdate() }
    }

    private fun Connection.connection(id: UUID, name: String, project: UUID?, capabilities: List<String>, allowed: List<String> = emptyList(), status: String? = null) {
        prepareStatement(
            "INSERT INTO connection (id, scope, project_id, type, name, site_url, account_email, capabilities, allowed_project_keys, " +
                "secret_key_id, secret_wrapped_key, secret_ciphertext, verification, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'JIRA', ?, 'https://acme.atlassian.net', 'bot@acme.com', CAST(? AS jsonb), CAST(? AS jsonb), 'k1', 'w', 'c', CAST(? AS jsonb), now(), now())",
        ).use { s ->
            s.setObject(1, id); s.setString(2, if (project == null) "PLATFORM" else "PROJECT"); s.setObject(3, project); s.setString(4, name)
            s.setString(5, json.writeValueAsString(capabilities)); s.setString(6, json.writeValueAsString(allowed))
            s.setString(7, status?.let { """{"status":"$it","checkedAt":"2026-10-01T00:00:00Z"}""" })
            s.executeUpdate()
        }
    }

    private fun task(key: String) = """[{"id":"n1","kind":"TASK","task":{"templateKey":"$key"}}]"""

    @Test
    fun `existing connections keep working and projects using Jira get their system connection and list`() {
        flyway("109").migrate()
        val shop = UUID.randomUUID()
        val quiet = UUID.randomUUID()
        val speckit = UUID.randomUUID()
        val platformWriter = UUID.randomUUID()
        val platformReader = UUID.randomUUID()
        val webOnlyReader = UUID.randomUUID()
        val projectWriter = UUID.randomUUID()
        db().use { c ->
            c.project(shop, "SHOP", "SHOP", task("fetch-issue"))
            c.project(quiet, "QUIET", null, task("llm-task"))
            c.project(speckit, "SPEC", null, task("spec-kit-specify"))
            c.connection(platformWriter, "Platform writer", null, listOf("ISSUE_COMMENT", "ISSUE_READ", "ISSUE_TRANSITION"), status = "OK")
            c.connection(platformReader, "Platform reader", null, listOf("ISSUE_READ"), status = "WARNING")
            c.connection(webOnlyReader, "Web reader", null, listOf("ISSUE_READ"), allowed = listOf("WEB"))
            c.connection(projectWriter, "Shop writer", shop, listOf("ISSUE_COMMENT", "ISSUE_READ"))
        }

        flyway(null).migrate()

        db().use { c ->
            fun row(id: UUID): Triple<UUID?, List<String>, Boolean> = c.prepareStatement(
                "SELECT jira_system_connection_id, jira_connection_ids::text, spec_kit_enabled FROM project WHERE id = ?",
            ).use { s ->
                s.setObject(1, id)
                s.executeQuery().use { rs ->
                    rs.next()
                    Triple(rs.getObject(1) as UUID?, json.readValue(rs.getString(2), List::class.java).map { it.toString() }, rs.getBoolean(3))
                }
            }
            val (system, additional, specKit) = row(shop)
            assertThat(system).isEqualTo(platformReader) // a reader, the fewest capabilities; the WEB-only reader does not fit SHOP
            assertThat(additional).containsExactly(projectWriter.toString(), platformWriter.toString())
            assertThat(specKit).isFalse()
            assertThat(row(quiet).first).isNull() // no Jira: nothing chosen, the ADR 0030 rule stays
            assertThat(row(speckit).third).isTrue()

            c.createStatement().use { s ->
                s.executeQuery("SELECT bool_and(classic_acknowledged), bool_and(token_kind = 'CLASSIC') FROM connection").use { rs ->
                    rs.next()
                    assertThat(rs.getBoolean(1)).isTrue()
                    assertThat(rs.getBoolean(2)).isTrue()
                }
            }

            // personal connections are possible now, and need an owner
            val owner = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO connection (id, scope, owner_user_id, owner_name, type, name, site_url, account_email, capabilities, secret_key_id, " +
                    "secret_wrapped_key, secret_ciphertext, token_kind, cloud_id, token_scopes, created_at, updated_at) VALUES (?, 'USER', ?, 'Anna', 'JIRA', " +
                    "'Platform reader', 'https://acme.atlassian.net', 'anna@acme.com', '[\"ISSUE_READ\"]', 'k1', 'w', 'c', 'SCOPED', " +
                    "'11111111-2222-3333-4444-555555555555', '[\"read:jira-work\"]', now(), now())",
            ).use { s -> s.setObject(1, UUID.randomUUID()); s.setObject(2, owner); s.executeUpdate() }
            assertThatThrownBy {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO connection (id, scope, type, name, site_url, account_email, capabilities, secret_key_id, secret_wrapped_key, " +
                            "secret_ciphertext, created_at, updated_at) VALUES (gen_random_uuid(), 'USER', 'JIRA', 'x', 'https://a', 'a@b.c', '[]', 'k', 'w', 'c', now(), now())",
                    )
                }
            }.hasMessageContaining("connection_scope_owner")

            // deleting the system connection clears it on the project
            c.createStatement().use { it.executeUpdate("DELETE FROM connection WHERE id = '$platformReader'") }
            assertThat(row(shop).first).isNull()
        }
    }
}
