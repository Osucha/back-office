package pl.mos.backoffice.projects.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager
import java.util.UUID

/**
 * V109 (ADR 0034) on a database written by an older release: the single repository of every project becomes the
 * list entry `main` (SOURCE, primary); the legacy columns stay as nullable mirrors; CI executions gain repository_key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectRepositoriesMigrationTest {

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

    @Test
    fun `the single repository of an old project becomes the primary repository main`() {
        flyway("108").migrate()
        val withRepo = UUID.randomUUID()
        val blankBranch = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.prepareStatement(
                "INSERT INTO project (id, key, name, repo_url, repo_default_branch, ci_entrypoints, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), now(), now())",
            ).use { s ->
                listOf(Triple(withRepo, "OLD", "develop"), Triple(blankBranch, "OLD2", "")).forEach { (id, key, branch) ->
                    s.setObject(1, id); s.setString(2, key); s.setString(3, "Old $key"); s.setString(4, "https://git.example.com/g/${key.lowercase()}.git")
                    s.setString(5, branch); s.setString(6, "[]"); s.executeUpdate()
                }
            }
        }

        flyway(null).migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            fun repositories(id: UUID): List<Map<*, *>> = c.prepareStatement("SELECT repositories::text FROM project WHERE id = ?").use { s ->
                s.setObject(1, id)
                s.executeQuery().use { rs -> rs.next(); json.readValue(rs.getString(1), List::class.java).map { it as Map<*, *> } }
            }
            assertThat(repositories(withRepo).single()).isEqualTo(
                mapOf("key" to "main", "url" to "https://git.example.com/g/old.git", "defaultBranch" to "develop", "purpose" to "SOURCE", "description" to null, "primary" to true),
            )
            assertThat(repositories(blankBranch).single()["defaultBranch"]).isEqualTo("main")

            // a project without a repository can now be stored, and CI executions remember their repository
            c.prepareStatement(
                "INSERT INTO project (id, key, name, ci_entrypoints, created_at, updated_at) VALUES (?, 'NEW', 'New', '[]'::jsonb, now(), now())",
            ).use { s -> s.setObject(1, UUID.randomUUID()); assertThat(s.executeUpdate()).isEqualTo(1) }
            c.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_name = 'ci_execution' AND column_name = 'repository_key'").use { s ->
                s.executeQuery().use { rs -> rs.next(); assertThat(rs.getInt(1)).isEqualTo(1) }
            }
        }
    }
}
