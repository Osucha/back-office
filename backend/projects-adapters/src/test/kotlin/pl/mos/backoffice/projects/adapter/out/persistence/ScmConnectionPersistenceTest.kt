package pl.mos.backoffice.projects.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmMessage
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** V119 (ADR 0051): repository tokens round-trip sealed, with their verification, and go with their project. */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class ScmConnectionPersistenceTest {

    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var connections: ScmConnectionRepository

    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val alice = Actor(PrincipalKind.USER, UUID.fromString("00000000-0000-0000-0000-00000000a11c"), "Alice")

    @Test
    fun `a repository token round-trips with its verification, re-wraps by compare-and-set and is deleted with the project`() {
        val project = projects.save(
            Project(
                id = ProjectId.new(), key = "SCM1", name = "Scm", repositories = listOf(GitRepository.legacy("https://git.example.com/group/shop.git", "main")),
                constitution = "", defaults = ProjectDefaults(modelProfileId = ModelProfileId.new()), createdAt = now, updatedAt = now, createdBy = alice,
            ),
        )
        val verification = ScmVerification(
            VerificationStatus.WARNING, now, "bo-read", listOf("read_api", "read_repository"), "Reporter", LocalDate.parse("2027-01-15"),
            setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ), emptySet(), listOf("merge into main"),
            listOf(ScmMessage(MessageSeverity.WARNING, "EXCESS", "too much")),
        )
        val sealed = SealedSecret("k1", "wrapped", "cipher")
        val connection = connections.save(
            ScmConnection(
                ScmConnectionId.new(), project.id, "main", "CI reader", ScmProvider.GITLAB, setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ),
                sealed, LocalDate.parse("2027-01-15"), verification, now, alice, now, alice,
            ),
        )

        assertThat(connections.findById(connection.id)).isEqualTo(connection)
        assertThat(connections.findByProject(project.id)).containsExactly(connection)

        val rewrapped = sealed.copy(keyId = "k2", wrappedKey = "rewrapped")
        assertThat(connections.replaceSecret(connection.id, sealed, rewrapped)).isTrue()
        assertThat(connections.replaceSecret(connection.id, sealed, rewrapped)).isFalse()
        assertThat(connections.findById(connection.id)!!.secret).isEqualTo(rewrapped)

        projects.delete(project.id)
        assertThat(connections.findById(connection.id)).isNull()
    }
}
