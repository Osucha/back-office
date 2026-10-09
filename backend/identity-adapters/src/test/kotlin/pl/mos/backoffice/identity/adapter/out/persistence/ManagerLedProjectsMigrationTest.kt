package pl.mos.backoffice.identity.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles

/**
 * V406 (ADR 0028) on an install seeded by an older release: the stored built-in member, evaluator and
 * library-curator roles lose `projects.create`, owner and maintainer gain `run.assign`, every change is audited
 * and the access version bumped. Running it again changes nothing. The seeding creates `project-manager`.
 */
@SpringBootTest
@Import(PostgresTestSupport::class)
class ManagerLedProjectsMigrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var roles: RoleRepository

    private val script = ClassPathResource("db/migration/V406__manager_led_projects.sql").getContentAsString(Charsets.UTF_8)

    private fun permissions(key: String) = roles.findByKey(key)!!.permissions

    private fun version() = jdbc.sql("SELECT version FROM identity_access_version WHERE id = 1").query(Long::class.javaObjectType).single()

    private fun audited() = jdbc.sql("SELECT entity_id FROM audit_log WHERE operation = :op AND details ->> :k LIKE :reason")
        .param("op", "ROLE_UPDATE").param("k", "reason").param("reason", "ADR 0028%").query(String::class.java).list()

    @Test
    fun `the migration moves project creation to managers and gives owners and maintainers run assign`() {
        assertThat(roles.findByKey(BuiltInRoles.PROJECT_MANAGER)!!.permissions).contains("projects.create")
        // the roles as an older release stored them
        listOf(BuiltInRoles.MEMBER, BuiltInRoles.EVALUATOR, BuiltInRoles.LIBRARY_CURATOR).forEach { key ->
            roles.findByKey(key)!!.let { roles.save(it.copy(permissions = it.permissions + "projects.create")) }
        }
        listOf(BuiltInRoles.OWNER, BuiltInRoles.MAINTAINER).forEach { key ->
            roles.findByKey(key)!!.let { roles.save(it.copy(permissions = it.permissions - "run.assign")) }
        }
        val before = version()
        val auditedBefore = audited().size

        jdbc.sql(script).update()

        listOf(BuiltInRoles.MEMBER, BuiltInRoles.EVALUATOR, BuiltInRoles.LIBRARY_CURATOR).forEach {
            assertThat(permissions(it)).describedAs(it).doesNotContain("projects.create")
        }
        assertThat(permissions(BuiltInRoles.MEMBER)).contains("library.view", "tokens.personal")
        assertThat(permissions(BuiltInRoles.PLATFORM_ADMIN)).contains("projects.create")
        assertThat(permissions(BuiltInRoles.OWNER)).contains("run.assign", "run.approve")
        assertThat(permissions(BuiltInRoles.MAINTAINER)).contains("run.assign")
        assertThat(permissions(BuiltInRoles.DEVELOPER)).doesNotContain("run.assign")
        assertThat(version()).isEqualTo(before + 1)
        assertThat(audited().drop(auditedBefore)).containsExactlyInAnyOrder("member", "evaluator", "library-curator", "owner", "maintainer")

        // idempotent: nothing left to change, nothing audited, no version bump
        jdbc.sql(script).update()
        assertThat(version()).isEqualTo(before + 1)
        assertThat(audited()).hasSize(auditedBefore + 5)
    }
}
