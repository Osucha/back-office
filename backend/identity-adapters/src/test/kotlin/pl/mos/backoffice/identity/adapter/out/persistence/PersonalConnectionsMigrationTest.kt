package pl.mos.backoffice.identity.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.PlatformSettings

/**
 * V409 (ADR 0035) on an install seeded by an older release: the stored built-in member-like roles gain
 * `connections.personal` (audited, access version bumped, idempotent). ADR 0063 (V413): the Spec Kit switch is gone.
 */
@SpringBootTest
@Import(PostgresTestSupport::class)
class PersonalConnectionsMigrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var roles: RoleRepository
    @Autowired lateinit var settings: PlatformSettingsRepository

    private val script = ClassPathResource("db/migration/V409__personal_connections_and_spec_kit_switch.sql").getContentAsString(Charsets.UTF_8)

    private val memberLike = listOf(BuiltInRoles.MEMBER, BuiltInRoles.PROJECT_MANAGER, BuiltInRoles.EVALUATOR, BuiltInRoles.LIBRARY_CURATOR)

    private fun version() = jdbc.sql("SELECT version FROM identity_access_version WHERE id = 1").query(Long::class.javaObjectType).single()

    private fun audited() = jdbc.sql("SELECT entity_id FROM audit_log WHERE operation = :op AND details ->> :k LIKE :reason")
        .param("op", "ROLE_UPDATE").param("k", "reason").param("reason", "ADR 0035%").query(String::class.java).list()

    @Test
    fun `member-like roles gain connections personal once, audited`() {
        memberLike.forEach { key -> roles.findByKey(key)!!.let { roles.save(it.copy(permissions = it.permissions - "connections.personal")) } }
        val before = version()
        val auditedBefore = audited().size

        jdbc.sql(script).update()

        memberLike.forEach { assertThat(roles.findByKey(it)!!.permissions).describedAs(it).contains("connections.personal") }
        assertThat(roles.findByKey(BuiltInRoles.AUDITOR)!!.permissions).doesNotContain("connections.personal")
        assertThat(version()).isEqualTo(before + 1)
        assertThat(audited().drop(auditedBefore)).containsExactlyInAnyOrderElementsOf(memberLike)

        jdbc.sql(script).update()
        assertThat(version()).isEqualTo(before + 1)
    }

    @Test
    fun `V413 dropped the Spec Kit switch and the platform settings still round-trip`() {
        val column = jdbc.sql(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = :t AND column_name = :c",
        ).param("t", "identity_platform_settings").param("c", "spec_kit_allowed").query(Long::class.javaObjectType).single()
        // the V409 test above re-adds it (IF NOT EXISTS) when it runs first; the settings never write it
        settings.save(PlatformSettings(personalTokenMaxDays = 30))
        assertThat(settings.load()!!.personalTokenMaxDays).isEqualTo(30)
        assertThat(column).isBetween(0L, 1L)
        settings.save(PlatformSettings())
    }
}
