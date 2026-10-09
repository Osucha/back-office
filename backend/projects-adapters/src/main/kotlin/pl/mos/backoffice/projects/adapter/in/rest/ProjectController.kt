package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.beans.factory.ObjectProvider
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
import pl.mos.backoffice.projects.application.port.`in`.ManageCiTokens
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.security.PermissionScope
import java.util.UUID

/**
 * `projects`, `ci-tokens` and project-scoped `workflows` endpoints. Every project carries the caller's
 * `myRoles` and `myPermissions` so the UI can hide what they cannot do (the use cases enforce it anyway).
 */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectController(
    private val projects: ManageProjects,
    private val workflows: ManageWorkflows,
    private val tokens: ManageCiTokens,
    private val gate: AuthorizationGate,
    private val catalog: ObjectProvider<PermissionCatalogContribution>,
    private val mapper: RestMapper,
) {
    @GetMapping
    fun list(): List<ProjectDto> = projects.list().map(::withStats)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid body: ProjectInputDto): ProjectDto {
        val project = projects.create(mapper.toCommand(body))
        return try {
            withStats(project)
        } catch (e: InsufficientPermissionException) {
            // the caller's permissions were resolved before they became OWNER; the next request sees them
            mapper.toDto(project, myRoles = listOf(DefaultWorkflows.ACCOUNTABLE_ROLE), myPermissions = userProjectPermissions())
        }
    }

    @GetMapping("/{projectId}")
    fun get(@PathVariable projectId: UUID): ProjectDto = withStats(projects.get(ProjectId(projectId)))

    @PutMapping("/{projectId}")
    fun update(@PathVariable projectId: UUID, @RequestBody @Valid body: ProjectInputDto): ProjectDto =
        withStats(projects.update(ProjectId(projectId), mapper.toCommand(body)))

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable projectId: UUID) = projects.delete(ProjectId(projectId))

    // ---- Spec Kit (ADR 0036)

    @GetMapping("/{projectId}/spec-kit")
    fun specKit(@PathVariable projectId: UUID): SpecKitStatusDto =
        projects.specKitStatus(ProjectId(projectId)).let { SpecKitStatusDto(it.enabled, it.allowedByPlatform, it.effective, it.missingWorkflows) }

    /** "Add Spec Kit workflows": seeds the Spec Kit workflows the project lacks. */
    @PostMapping("/{projectId}/spec-kit/workflows")
    fun addSpecKitWorkflows(@PathVariable projectId: UUID): List<WorkflowDefinitionDto> =
        projects.addSpecKitWorkflows(ProjectId(projectId)).map(mapper::toDto)

    // ---- CI entrypoints and snippet

    @GetMapping("/{projectId}/ci-entrypoints")
    fun entrypoints(@PathVariable projectId: UUID): List<CiEntrypointDto> {
        val names = workflowNames(ProjectId(projectId))
        return projects.entrypoints(ProjectId(projectId)).map { mapper.toDto(it, names[it.workflowId]) }
    }

    @PutMapping("/{projectId}/ci-entrypoints")
    fun replaceEntrypoints(@PathVariable projectId: UUID, @RequestBody @Valid body: List<CiEntrypointDto>): List<CiEntrypointDto> {
        val id = ProjectId(projectId)
        val replaced = projects.replaceEntrypoints(id, body.map(mapper::toDomain))
        val names = workflowNames(id)
        return replaced.map { mapper.toDto(it, names[it.workflowId]) }
    }

    @GetMapping("/{projectId}/ci-snippet")
    fun ciSnippet(@PathVariable projectId: UUID): CiSnippetDto = mapper.toDto(projects.ciSnippet(ProjectId(projectId)))

    // ---- CI tokens

    @GetMapping("/{projectId}/ci-tokens")
    fun listTokens(@PathVariable projectId: UUID): List<CiTokenDto> = tokens.list(ProjectId(projectId)).map(mapper::toDto)

    @PostMapping("/{projectId}/ci-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun createToken(@PathVariable projectId: UUID, @RequestBody @Valid body: CiTokenInputDto): CiTokenCreatedDto =
        mapper.toDto(tokens.create(ProjectId(projectId), body.name))

    @DeleteMapping("/{projectId}/ci-tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeToken(@PathVariable projectId: UUID, @PathVariable tokenId: UUID) = tokens.revoke(ProjectId(projectId), ProjectCiTokenId(tokenId))

    // ---- workflows

    @GetMapping("/{projectId}/workflows")
    fun listWorkflows(@PathVariable projectId: UUID): List<WorkflowDefinitionDto> {
        projects.get(ProjectId(projectId))
        return workflows.listByProject(ProjectId(projectId)).map(mapper::toDto)
    }

    @PostMapping("/{projectId}/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    fun createWorkflow(@PathVariable projectId: UUID, @RequestBody @Valid body: WorkflowDefinitionInputDto): WorkflowDefinitionDto =
        mapper.toDto(workflows.create(ProjectId(projectId), mapper.toCommand(body)))

    private fun withStats(project: Project): ProjectDto {
        val caller = gate.currentUser()
        val pid = project.id.value
        val permissions = if (caller.allProjects) userProjectPermissions() else caller.projectPermissions[pid].orEmpty()
        return mapper.toDto(project, projects.stats(project.id), workflowNames(project.id), caller.projectRoles[pid].orEmpty(), permissions)
    }

    /** Every project-scoped permission people can hold (CI-token-only `ci.*` excluded). */
    private fun userProjectPermissions(): Set<String> =
        catalog.orderedStream().toList().flatMap { it.permissions() }
            .filter { it.scope == PermissionScope.PROJECT && !it.key.startsWith("ci.") }
            .map { it.key }.toSet()

    private fun workflowNames(projectId: ProjectId) = workflows.listByProject(projectId).associate { it.id to it.name }
}
