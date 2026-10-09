package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/** `workflow-library` tag of openapi.yaml (ADR 0037): library entries, publishing, adopting, updating and detaching copies. */
@RestController
@RequestMapping("/api/v1")
class WorkflowLibraryController(
    private val library: ManageWorkflowLibrary,
    private val mapper: RestMapper,
) {
    @GetMapping("/library/workflows")
    fun list(@RequestParam(defaultValue = "true") includeDeprecated: Boolean): List<LibraryWorkflowDto> =
        library.list(includeDeprecated).map { mapper.toDto(it) }

    @GetMapping("/library/workflows/{libraryWorkflowId}")
    fun get(@PathVariable libraryWorkflowId: UUID): LibraryWorkflowDto = mapper.toDto(library.get(LibraryWorkflowId(libraryWorkflowId)), detail = true)

    @PutMapping("/library/workflows/{libraryWorkflowId}")
    fun update(@PathVariable libraryWorkflowId: UUID, @RequestBody @Valid body: LibraryWorkflowUpdateDto): LibraryWorkflowDto =
        mapper.toDto(
            library.update(LibraryWorkflowId(libraryWorkflowId), ManageWorkflowLibrary.LibraryDetailsCommand(body.name, body.description, body.tags, body.deprecated)),
            detail = true,
        )

    @DeleteMapping("/library/workflows/{libraryWorkflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable libraryWorkflowId: UUID) = library.delete(LibraryWorkflowId(libraryWorkflowId))

    @GetMapping("/library/workflows/{libraryWorkflowId}/versions/{version}")
    fun version(@PathVariable libraryWorkflowId: UUID, @PathVariable version: Int): LibraryWorkflowVersionDetailDto =
        mapper.toDto(library.version(LibraryWorkflowId(libraryWorkflowId), version))

    @PostMapping("/workflows/{workflowId}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    fun publish(@PathVariable workflowId: UUID, @RequestBody body: PublishWorkflowRequestDto): LibraryWorkflowDto =
        mapper.toDto(
            library.publish(
                WorkflowDefinitionId(workflowId),
                ManageWorkflowLibrary.PublishCommand(body.libraryWorkflowId?.let(::LibraryWorkflowId), body.key, body.name, body.description, body.tags, body.changeNote),
            ),
            detail = true,
        )

    @PostMapping("/projects/{projectId}/workflows/from-library")
    @ResponseStatus(HttpStatus.CREATED)
    fun adopt(@PathVariable projectId: UUID, @RequestBody body: AdoptLibraryWorkflowRequestDto): WorkflowDefinitionDto {
        val id = body.libraryWorkflowId ?: throw ValidationException("is required", "libraryWorkflowId")
        return withStatus(library.adopt(ProjectId(projectId), LibraryWorkflowId(id), body.version, body.name))
    }

    @PostMapping("/workflows/{workflowId}/library/update")
    fun updateFromLibrary(@PathVariable workflowId: UUID, @RequestBody(required = false) body: LibraryUpdateRequestDto?): WorkflowDefinitionDto =
        withStatus(library.updateFromLibrary(WorkflowDefinitionId(workflowId), body?.version, body?.discardLocalChanges ?: false))

    @PostMapping("/workflows/{workflowId}/library/detach")
    fun detach(@PathVariable workflowId: UUID): WorkflowDefinitionDto = withStatus(library.detach(WorkflowDefinitionId(workflowId)))

    private fun withStatus(d: WorkflowDefinition): WorkflowDefinitionDto =
        mapper.toDto(d, d.library?.let { library.linkStatus(listOf(it.libraryWorkflowId))[it.libraryWorkflowId] })
}
