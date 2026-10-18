package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId

/** Run workspaces of projects (ADR 0044). Keys are unique per project. */
interface ProjectWorkspaceRepository {
    fun findByProjectId(projectId: ProjectId): List<ProjectWorkspace>
    fun findById(id: ProjectWorkspaceId): ProjectWorkspace?
    fun save(workspace: ProjectWorkspace): ProjectWorkspace
    fun delete(id: ProjectWorkspaceId)
}
