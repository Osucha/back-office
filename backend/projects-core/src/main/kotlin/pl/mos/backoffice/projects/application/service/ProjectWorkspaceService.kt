package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageProjectWorkspaces
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectWorkspaces.WorkspaceCommand
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkspaceSource
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate

/**
 * Run workspaces of a project (ADR 0044), shared by all its workflows: the sources cloned into a workspace, its setup
 * command and the agent roles of its steps. Steps of any workflow pick one by key; a step that names a deleted
 * workspace fails validation (draft, ADR 0041).
 */
class ProjectWorkspaceService(
    private val workspaces: ProjectWorkspaceRepository,
    private val projects: ProjectRepository,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    /** Library sources a workspace may list; null = none known (tests). */
    private val librarySources: LibrarySourceRepository? = null,
) : ManageProjectWorkspaces {

    override fun list(projectId: ProjectId): List<ProjectWorkspace> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        return workspaces.findByProjectId(projectId).sortedBy { it.key }
    }

    override fun create(projectId: ProjectId, command: WorkspaceCommand): ProjectWorkspace {
        val actor = gate.require(ProjectsPermissions.PROJECT_MANAGE, projectId.value).actor
        val project = projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        val key = command.key.trim()
        if (workspaces.findByProjectId(projectId).any { it.key == key }) throw ValidationException("workspace $key already exists in this project", "key")
        val now = clock.now()
        val saved = workspaces.save(
            ProjectWorkspace(
                id = ProjectWorkspaceId.new(), projectId = projectId, key = key, name = command.name?.trim()?.ifEmpty { null } ?: key,
                description = command.description.trim(), sources = sources(project, command.sources), setupCommand = setup(command),
                roles = roles(command), createdAt = now, updatedAt = now, updatedBy = actor,
            ),
        )
        audit.record(
            AuditEntry("PROJECT_WORKSPACE_CREATE", ENTITY, saved.id.toString(), projectId = projectId.value, details = mapOf("key" to key, "sources" to saved.sources.size)),
        )
        return saved
    }

    override fun update(id: ProjectWorkspaceId, command: WorkspaceCommand): ProjectWorkspace {
        val existing = load(id)
        val actor = gate.require(ProjectsPermissions.PROJECT_MANAGE, existing.projectId.value).actor
        if (command.key.trim() != existing.key) throw ValidationException("the key of a workspace cannot be changed: workflow steps refer to it", "key")
        val project = projects.findById(existing.projectId) ?: throw NotFoundException("Project", existing.projectId)
        val saved = workspaces.save(
            existing.copy(
                name = command.name?.trim()?.ifEmpty { null } ?: existing.key, description = command.description.trim(),
                sources = sources(project, command.sources), setupCommand = setup(command), roles = roles(command),
                updatedAt = clock.now(), updatedBy = actor,
            ),
        )
        audit.record(AuditEntry("PROJECT_WORKSPACE_UPDATE", ENTITY, id.toString(), projectId = existing.projectId.value, details = mapOf("key" to saved.key)))
        return saved
    }

    override fun delete(id: ProjectWorkspaceId) {
        val existing = load(id)
        gate.require(ProjectsPermissions.PROJECT_MANAGE, existing.projectId.value)
        workspaces.delete(id)
        audit.record(AuditEntry("PROJECT_WORKSPACE_DELETE", ENTITY, id.toString(), projectId = existing.projectId.value, details = mapOf("key" to existing.key)))
    }

    /** Only repositories of the project and existing library sources. */
    private fun sources(project: Project, sources: List<WorkspaceSource>): List<WorkspaceSource> {
        val cleaned = sources.map { WorkspaceSource(it.key.trim(), it.library) }.distinct()
        cleaned.forEach { s ->
            val known = if (s.library) librarySources?.findByKey(s.key) != null else project.repositories.any { it.key == s.key }
            if (!known) throw ValidationException(if (s.library) "unknown library source ${s.key}" else "project ${project.key} has no repository ${s.key}", "sources")
        }
        return cleaned
    }

    private fun setup(command: WorkspaceCommand): String? = command.setupCommand?.trim()?.ifEmpty { null }

    private fun roles(command: WorkspaceCommand): List<String> = command.roles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun load(id: ProjectWorkspaceId): ProjectWorkspace = workspaces.findById(id) ?: throw NotFoundException("ProjectWorkspace", id)

    companion object {
        const val ENTITY = "ProjectWorkspace"
    }
}
