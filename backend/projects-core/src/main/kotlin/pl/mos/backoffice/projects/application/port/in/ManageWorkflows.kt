package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.Canvas
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ActionDescriptor
import pl.mos.backoffice.projects.domain.service.ControlNodeDescriptor
import pl.mos.backoffice.projects.domain.service.StateField
import pl.mos.backoffice.projects.domain.service.WorkflowValidationResult
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameter
import java.util.UUID

/** ADR 0056: the JSON Schema of a task step's `config` keys for a model answer agent and for a coding agent. */
data class TaskConfigSchemas(val modelAnswer: Map<String, Any?>, val codingAgent: Map<String, Any?>)

/** Use case: design workflows. `update` bumps the version; `delete` refuses while runs are active. */
interface ManageWorkflows {
    fun create(projectId: ProjectId, command: WorkflowCommand): WorkflowDefinition
    fun update(id: WorkflowDefinitionId, command: WorkflowCommand): WorkflowDefinition
    fun delete(id: WorkflowDefinitionId)
    fun get(id: WorkflowDefinitionId): WorkflowDefinition
    fun listByProject(projectId: ProjectId): List<WorkflowDefinition>
    fun duplicate(id: WorkflowDefinitionId): WorkflowDefinition
    fun validate(projectId: ProjectId, command: WorkflowCommand): WorkflowValidationResult
    fun palette(projectId: ProjectId): Palette
    fun validateExpression(expression: String): ExpressionCheck

    /** ADR 0056: the config schemas of model answer and coding agent steps (static, any signed-in user). */
    fun taskConfigSchemas(): TaskConfigSchemas

    /** ADR 0050: the model of every task step of a workflow and whether the caller can use it (`project.view`). */
    fun modelPlan(id: WorkflowDefinitionId): List<StepModelPlan>

    /** ADR 0053: the effective run inputs of a workflow for the start dialog, the union over its task steps (`project.view`). */
    fun runInputs(id: WorkflowDefinitionId): List<RunInputField>

    /** ADR 0050: the models a step of the project may use, usable ones first, with route and reason (`project.view`). */
    fun modelOptions(projectId: ProjectId, stepKind: StepKind, harness: AgentHarness?): List<ModelOption>

    /** One task step of [modelPlan]: [source] NODE, TEMPLATE, PROJECT, PROJECT_REVIEW or NONE; [problem] stops a run. */
    data class StepModelPlan(
        val nodeId: String,
        val nodeName: String,
        val stepKind: StepKind,
        val harness: AgentHarness?,
        val source: String,
        val profileId: ModelProfileId?,
        val model: ModelOption?,
        val problem: String?,
        val note: String?,
    )

    data class WorkflowCommand(
        val name: String,
        val description: String = "",
        val nodes: List<WorkflowNode>,
        val edges: List<WorkflowEdge>,
        val triggers: List<Trigger> = listOf(Trigger(TriggerType.MANUAL)),
        val enabled: Boolean = true,
        val canvas: Canvas? = null,
        /** ADR 0060: runs once per run directory before the first coding step; null = none. */
        val setupCommand: String? = null,
    )

    data class PaletteTask(
        val key: String,
        val name: String,
        val description: String,
        /** ADR 0056: who runs the task by default (the template's agent). */
        val agent: AgentSnapshot,
        /** ADR 0055: groups the designer palette; null when the template's lineage sets none. */
        val category: TemplateCategory?,
        val ports: List<String>,
        val projectScoped: Boolean,
        val configSchema: Map<String, Any?>,
        /** ADR 0054: kept for the steps that use it; the designer and MCP do not offer it for new steps. */
        val deprecated: Boolean = false,
        /** ADR 0058: merged parameter declarations of the lineage. */
        val parameters: List<TemplateParameter> = emptyList(),
        /** ADR 0058: values the template lineage sets (step values win). */
        val parameterValues: Map<String, Any?> = emptyMap(),
        /** ADR 0058: a required parameter has no value without the step (not a ready block). */
        val needsParameters: Boolean = false,
    )

    data class Palette(
        val controlNodes: List<ControlNodeDescriptor>,
        val tasks: List<PaletteTask>,
        val stateFields: List<StateField>,
        /** ADR 0056: the actions, with their defaults, schemas, run inputs and connection capabilities. */
        val actions: List<ActionDescriptor> = emptyList(),
    )

    data class ExpressionCheck(val valid: Boolean, val message: String?)

    companion object {
        fun projectUuid(projectId: ProjectId): UUID = projectId.value
    }
}
