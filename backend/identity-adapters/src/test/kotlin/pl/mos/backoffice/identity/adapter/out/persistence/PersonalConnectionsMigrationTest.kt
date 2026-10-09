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
import pl.mos.backoffice.shared.platform.PlatformFeatures

/**
 * V409 (ADR 0035, ADR 0036) on an install seeded by an older release: the stored built-in member-like roles gain
 * `connections.personal` (audited, access version bumped, idempotent); the platform switch "Spec Kit allowed" is stored
 * and read by [PlatformFeatures] (default allowed).
 */
@SpringBootTest
@Import(PostgresTestSupport::class)
class PersonalConnectionsMigrationTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var roles: RoleRepository
    @Autowired lateinit var settings: PlatformSettingsRepository
    @Autowired lateinit var features: PlatformFeatures

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
    fun `the Spec Kit switch is stored in the platform settings and read by the platform features`() {
        assertThat(features.specKitAllowed()).isTrue()
        settings.save(PlatformSettings(specKitAllowed = false))
        assertThat(settings.load()!!.specKitAllowed).isFalse()
        assertThat(features.specKitAllowed()).isFalse()
        settings.save(PlatformSettings(specKitAllowed = true))
        assertThat(features.specKitAllowed()).isTrue()
    }
}
