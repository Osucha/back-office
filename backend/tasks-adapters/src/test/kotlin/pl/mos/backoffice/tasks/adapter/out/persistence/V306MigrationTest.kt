package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/** ADR 0060: V306 keeps every existing agent FULL (the analyst and reviewer too) and gives templates no required skills. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V306MigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

    @AfterAll
    fun stop() = postgres.stop()

    private fun flyway(target: String?): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    @Test
    fun `agents keep full access and templates require no skills`() {
        flyway("305").migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                listOf("analyst", "reviewer", "my-coder").forEach { key ->
                    s.executeUpdate(
                        "insert into agent (id, key, name, scope_type, created_at, updated_at) values (gen_random_uuid(), '$key', '$key', 'GLOBAL', now(), now())",
                    )
                }
                s.executeUpdate(
                    "insert into task_template (id, key, name, scope_type, built_in, version, created_at, updated_at) " +
                        "values (gen_random_uuid(), 'tt', 'tt', 'GLOBAL', false, 1, now(), now())",
                )
            }
        }
        flyway(null).migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select key, access ->> 'mode' from agent where key in ('analyst', 'reviewer', 'my-coder') order by key").use { rs ->
                    val modes = buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    assertThat(modes).containsExactly("analyst" to "FULL", "my-coder" to "FULL", "reviewer" to "FULL")
                }
                s.executeQuery("select requires_skills::text from task_template where key = 'tt'").use { rs ->
                    rs.next()
                    assertThat(rs.getString(1)).isEqualTo("[]")
                }
            }
        }
    }
}
