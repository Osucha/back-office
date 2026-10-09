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
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/** `workflows` endpoints: get/update/delete, duplicate, validate, palette, expression check and starting runs. */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowController(
    private val workflows: ManageWorkflows,
    private val startRun: StartRun,
    private val mapper: RestMapper,
) {
    @PostMapping("/validate")
    fun validate(@RequestParam projectId: UUID, @RequestBody body: WorkflowDefinitionInputDto): ValidationResultDto {
        val command = try {
            mapper.toCommand(body)
        } catch (e: ValidationException) {
            return ValidationResultDto(false, e.errors.map { ValidationIssueDto(null, null, it.field, it.message) }, emptyList())
        }
        return mapper.toDto(workflows.validate(ProjectId(projectId), command))
    }

    @GetMapping("/palette")
    fun palette(@RequestParam projectId: UUID): WorkflowPaletteDto = mapper.toDto(workflows.palette(ProjectId(projectId)))

    @PostMapping("/expressions/validate")
    fun validateExpression(@RequestBody @Valid body: ExpressionInputDto): ExpressionCheckDto = mapper.toDto(workflows.validateExpression(body.expression ?: ""))

    @GetMapping("/{workflowId}")
    fun get(@PathVariable workflowId: UUID): WorkflowDefinitionDto = mapper.toDto(workflows.get(WorkflowDefinitionId(workflowId)))

    @PutMapping("/{workflowId}")
    fun update(@PathVariable workflowId: UUID, @RequestBody @Valid body: WorkflowDefinitionInputDto): WorkflowDefinitionDto =
        mapper.toDto(workflows.update(WorkflowDefinitionId(workflowId), mapper.toCommand(body)))

    @DeleteMapping("/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workflowId: UUID) = workflows.delete(WorkflowDefinitionId(workflowId))

    @PostMapping("/{workflowId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    fun duplicate(@PathVariable workflowId: UUID): WorkflowDefinitionDto = mapper.toDto(workflows.duplicate(WorkflowDefinitionId(workflowId)))

    @PostMapping("/{workflowId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable workflowId: UUID, @RequestBody body: StartRunRequestDto): WorkflowRunDto =
        mapper.toDto(startRun.start(WorkflowDefinitionId(workflowId), mapper.toInput(body), TriggerSource.MANUAL))
}
