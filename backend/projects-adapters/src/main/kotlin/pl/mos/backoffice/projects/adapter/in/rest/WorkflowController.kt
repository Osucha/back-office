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
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.`in`.TaskExecutorDescriptor
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import java.util.UUID

/** `workflows` endpoints: get/update/delete, duplicate, validate, palette, expression check and starting runs. */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowController(
    private val workflows: ManageWorkflows,
    private val startRun: StartRun,
    private val mapper: RestMapper,
    private val library: ManageWorkflowLibrary,
) {
    /** The DTO with the live state of the linked library entry (ADR 0037). */
    private fun dto(d: WorkflowDefinition): WorkflowDefinitionDto =
        mapper.toDto(d, d.library?.let { library.linkStatus(listOf(it.libraryWorkflowId))[it.libraryWorkflowId] })

    @PostMapping("/validate")
    fun validate(@RequestParam projectId: UUID, @RequestBody body: WorkflowDefinitionInputDto): ValidationResultDto {
        val command = try {
            mapper.toCommand(body)
        } catch (e: ValidationException) {
            return ValidationResultDto(false, e.errors.map { ValidationIssueDto(null, null, it.field, it.message) }, emptyList())
        }
        return mapper.toDto(workflows.validate(ProjectId(projectId), command))
    }

    /** ADR 0054: the config schema of every task executor, for the library editor. */
    @GetMapping("/task-executors")
    fun taskExecutors(): List<TaskExecutorDescriptor> = workflows.taskExecutors()

    @GetMapping("/palette")
    fun palette(@RequestParam projectId: UUID): WorkflowPaletteDto = mapper.toDto(workflows.palette(ProjectId(projectId)))

    /** ADR 0050: the models a step of the project may use for the caller. */
    @GetMapping("/model-options")
    fun modelOptions(@RequestParam projectId: UUID, @RequestParam stepKind: StepKind, @RequestParam(required = false) harness: AgentHarness?): List<ModelOptionDto> =
        workflows.modelOptions(ProjectId(projectId), stepKind, harness).map(ModelOptionDto::of)

    /** ADR 0053: the effective run inputs of the workflow for the start dialog. */
    @GetMapping("/{workflowId}/run-inputs")
    fun runInputs(@PathVariable workflowId: UUID): List<RunInputFieldDto> =
        workflows.runInputs(WorkflowDefinitionId(workflowId)).map(RunInputFieldDto::of)

    /** ADR 0050: the model of every task step and whether the caller can use it. */
    @GetMapping("/{workflowId}/model-plan")
    fun modelPlan(@PathVariable workflowId: UUID): List<StepModelPlanDto> =
        workflows.modelPlan(WorkflowDefinitionId(workflowId)).map(StepModelPlanDto::of)

    @PostMapping("/expressions/validate")
    fun validateExpression(@RequestBody @Valid body: ExpressionInputDto): ExpressionCheckDto = mapper.toDto(workflows.validateExpression(body.expression ?: ""))

    @GetMapping("/{workflowId}")
    fun get(@PathVariable workflowId: UUID): WorkflowDefinitionDto = dto(workflows.get(WorkflowDefinitionId(workflowId)))

    @PutMapping("/{workflowId}")
    fun update(@PathVariable workflowId: UUID, @RequestBody @Valid body: WorkflowDefinitionInputDto): WorkflowDefinitionDto =
        dto(workflows.update(WorkflowDefinitionId(workflowId), mapper.toCommand(body)))

    @DeleteMapping("/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workflowId: UUID) = workflows.delete(WorkflowDefinitionId(workflowId))

    @PostMapping("/{workflowId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    fun duplicate(@PathVariable workflowId: UUID): WorkflowDefinitionDto = dto(workflows.duplicate(WorkflowDefinitionId(workflowId)))

    @PostMapping("/{workflowId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable workflowId: UUID, @RequestBody body: StartRunRequestDto): WorkflowRunDto =
        mapper.toDto(startRun.startChecked(WorkflowDefinitionId(workflowId), mapper.toInput(body), TriggerSource.MANUAL))
}
