package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.domain.model.WorkspaceSource

/** Run workspaces of a project (ADR 0044): read with `project.view`, changed with `project.manage`. */
interface ManageProjectWorkspaces {
    data class WorkspaceCommand(
        val key: String,
        val name: String? = null,
        val description: String = "",
        val sources: List<WorkspaceSource> = emptyList(),
        val setupCommand: String? = null,
        val roles: List<String> = emptyList(),
    )

    fun list(projectId: ProjectId): List<ProjectWorkspace>
    fun create(projectId: ProjectId, command: WorkspaceCommand): ProjectWorkspace

    /** The key cannot change: workflow steps refer to it. */
    fun update(id: ProjectWorkspaceId, command: WorkspaceCommand): ProjectWorkspace
    fun delete(id: ProjectWorkspaceId)
}
