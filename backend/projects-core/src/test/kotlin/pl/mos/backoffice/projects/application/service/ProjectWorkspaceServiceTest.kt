package pl.mos.backoffice.projects.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectWorkspaces.WorkspaceCommand
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkspaceSource
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Instant

/** ADR 0044: run workspaces of a project, read with project.view and changed with project.manage. */
class ProjectWorkspaceServiceTest {
    private val project = Fixtures.project()
    private val store = object : ProjectWorkspaceRepository {
        val rows = LinkedHashMap<ProjectWorkspaceId, ProjectWorkspace>()
        override fun findByProjectId(projectId: ProjectId) = rows.values.filter { it.projectId == projectId }
        override fun findById(id: ProjectWorkspaceId) = rows[id]
        override fun save(workspace: ProjectWorkspace) = workspace.also { rows[it.id] = it }
        override fun delete(id: ProjectWorkspaceId) { rows.remove(id) }
    }
    private val projects = object : ProjectRepository {
        override fun save(project: Project) = project
        override fun findById(id: ProjectId) = project.takeIf { it.id == id }
        override fun findByKey(key: String) = project.takeIf { it.key == key }
        override fun findAll(projects: ProjectFilter) = listOf(project)
        override fun delete(id: ProjectId) = Unit
    }
    private val audit = RecordingAuditPort()
    private val clock = Clock { Instant.parse("2026-10-18T10:00:00Z") }
    private fun service(vararg permissions: String) = ProjectWorkspaceService(store, projects, FakeAuthorizationGate.permitting(*permissions, projects = setOf(project.id.value)), audit, clock)

    private val command = WorkspaceCommand("main", "Main", "shared clones", listOf(WorkspaceSource("main")), " npm ci ", listOf("implementer", " reviewer "))

    @Test
    fun `managers create, change and delete workspaces, the key is fixed and unique per project`() {
        val manager = service(ProjectsPermissions.PROJECT_MANAGE, ProjectsPermissions.PROJECT_VIEW)
        val created = manager.create(project.id, command)
        assertThat(created.setupCommand).isEqualTo("npm ci")
        assertThat(created.roles).containsExactly("implementer", "reviewer")
        assertThatThrownBy { manager.create(project.id, command) }.isInstanceOf(ValidationException::class.java)

        val updated = manager.update(created.id, command.copy(name = "", sources = emptyList(), setupCommand = " "))
        assertThat(listOf(updated.name, updated.setupCommand, updated.sources)).containsExactly("main", null, emptyList<WorkspaceSource>())
        assertThatThrownBy { manager.update(created.id, command.copy(key = "other")) }.isInstanceOf(ValidationException::class.java)
        assertThat(manager.list(project.id).map { it.key }).containsExactly("main")

        manager.delete(created.id)
        assertThat(manager.list(project.id)).isEmpty()
        assertThat(audit.entries.map { it.operation }).containsExactly("PROJECT_WORKSPACE_CREATE", "PROJECT_WORKSPACE_UPDATE", "PROJECT_WORKSPACE_DELETE")
    }

    @Test
    fun `sources must be repositories of the project or library sources`() {
        val manager = service(ProjectsPermissions.PROJECT_MANAGE)
        assertThatThrownBy { manager.create(project.id, command.copy(sources = listOf(WorkspaceSource("nope")))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("no repository nope")
        assertThatThrownBy { manager.create(project.id, command.copy(sources = listOf(WorkspaceSource("standards", library = true)))) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `viewers read, only managers change`() {
        service(ProjectsPermissions.PROJECT_MANAGE).create(project.id, command)
        val viewer = service(ProjectsPermissions.PROJECT_VIEW)
        assertThat(viewer.list(project.id)).hasSize(1)
        assertThatThrownBy { viewer.create(project.id, command.copy(key = "docs")) }.isInstanceOf(InsufficientPermissionException::class.java)
    }
}
