package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectWorkspaces
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.domain.model.WorkspaceSource
import pl.mos.backoffice.shared.NotFoundException
import java.time.Instant
import java.util.UUID

data class WorkspaceSourceDto(val key: String = "", val library: Boolean = false)

data class ProjectWorkspaceInputDto(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]{0,19}$") val key: String = "",
    val name: String? = null,
    val description: String = "",
    val sources: List<WorkspaceSourceDto> = emptyList(),
    val setupCommand: String? = null,
    val roles: List<String> = emptyList(),
)

data class ProjectWorkspaceDto(
    val id: UUID,
    val projectId: UUID,
    val key: String,
    val name: String,
    val description: String,
    val sources: List<WorkspaceSourceDto>,
    val setupCommand: String?,
    val roles: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto?,
)

/** `project-workspaces` tag of openapi.yaml (ADR 0044): run workspaces of a project, picked by steps of its workflows. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workspaces")
class ProjectWorkspaceController(private val workspaces: ManageProjectWorkspaces, private val mapper: RestMapper) {

    @GetMapping
    fun list(@PathVariable projectId: UUID): List<ProjectWorkspaceDto> = workspaces.list(ProjectId(projectId)).map(::toDto)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable projectId: UUID, @Valid @RequestBody body: ProjectWorkspaceInputDto): ProjectWorkspaceDto =
        toDto(workspaces.create(ProjectId(projectId), command(body)))

    @PutMapping("/{workspaceId}")
    fun update(@PathVariable projectId: UUID, @PathVariable workspaceId: UUID, @Valid @RequestBody body: ProjectWorkspaceInputDto): ProjectWorkspaceDto {
        if (workspaces.list(ProjectId(projectId)).none { it.id.value == workspaceId }) throw NotFoundException("ProjectWorkspace", workspaceId)
        return toDto(workspaces.update(ProjectWorkspaceId(workspaceId), command(body)))
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable projectId: UUID, @PathVariable workspaceId: UUID) {
        if (workspaces.list(ProjectId(projectId)).none { it.id.value == workspaceId }) throw NotFoundException("ProjectWorkspace", workspaceId)
        workspaces.delete(ProjectWorkspaceId(workspaceId))
    }

    private fun command(body: ProjectWorkspaceInputDto) = ManageProjectWorkspaces.WorkspaceCommand(
        key = body.key, name = body.name, description = body.description,
        sources = body.sources.map { WorkspaceSource(it.key, it.library) }, setupCommand = body.setupCommand, roles = body.roles,
    )

    private fun toDto(w: ProjectWorkspace) = ProjectWorkspaceDto(
        w.id.value, w.projectId.value, w.key, w.name, w.description, w.sources.map { WorkspaceSourceDto(it.key, it.library) },
        w.setupCommand, w.roles, w.createdAt, w.updatedAt, mapper.actor(w.updatedBy),
    )
}
