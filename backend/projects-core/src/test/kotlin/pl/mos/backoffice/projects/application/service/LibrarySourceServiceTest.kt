package pl.mos.backoffice.projects.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.application.port.`in`.ManageLibrarySources.SourceCommand
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Instant

/** ADR 0043: sources of the library, read with library.view and changed with library.manage. */
class LibrarySourceServiceTest {

    private val store = object : LibrarySourceRepository {
        val rows = LinkedHashMap<LibrarySourceId, LibrarySource>()
        override fun findAll() = rows.values.toList()
        override fun findById(id: LibrarySourceId) = rows[id]
        override fun findByKey(key: String) = rows.values.firstOrNull { it.key == key }
        override fun save(source: LibrarySource) = source.also { rows[it.id] = it }
        override fun delete(id: LibrarySourceId) { rows.remove(id) }
    }
    private val audit = RecordingAuditPort()
    private val clock = Clock { Instant.parse("2026-10-17T10:00:00Z") }
    private fun service(vararg permissions: String) = LibrarySourceService(store, FakeAuthorizationGate.permitting(*permissions), audit, clock)

    private val command = SourceCommand("standards", "Company standards", "Coding and review rules", "https://git.example.com/acme/standards.git", null, RepositoryPurpose.STANDARDS)

    @Test
    fun `curators create, change and delete sources, the key is fixed and unique`() {
        val curator = service(ProjectsPermissions.LIBRARY_MANAGE, ProjectsPermissions.LIBRARY_VIEW)
        val created = curator.create(command)
        assertThat(created.defaultBranch).isEqualTo("main")
        assertThat(created.jobKey).isEqualTo("lib-standards")
        assertThat(created.asRepository().key).isEqualTo("lib-standards")
        assertThatThrownBy { curator.create(command) }.isInstanceOf(ValidationException::class.java)

        val updated = curator.update(created.id, command.copy(name = "Standards", defaultBranch = "trunk"))
        assertThat(updated.defaultBranch).isEqualTo("trunk")
        assertThatThrownBy { curator.update(created.id, command.copy(key = "other")) }.isInstanceOf(ValidationException::class.java)
        assertThat(curator.list().map { it.name }).containsExactly("Standards")

        curator.delete(created.id)
        assertThat(store.rows).isEmpty()
        assertThat(audit.entries.map { it.operation }).containsExactly("LIBRARY_SOURCE_CREATE", "LIBRARY_SOURCE_UPDATE", "LIBRARY_SOURCE_DELETE")
    }

    @Test
    fun `readers list, only curators change`() {
        service(ProjectsPermissions.LIBRARY_MANAGE).create(command)
        assertThat(service(ProjectsPermissions.LIBRARY_VIEW).list()).hasSize(1)
        assertThatThrownBy { service(ProjectsPermissions.LIBRARY_VIEW).create(command.copy(key = "templates")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service().list() }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(ProjectsPermissions.LIBRARY_MANAGE).create(command.copy(key = "Bad Key")) }.isInstanceOf(ValidationException::class.java)
    }
}
