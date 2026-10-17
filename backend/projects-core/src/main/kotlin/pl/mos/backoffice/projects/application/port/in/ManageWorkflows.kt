package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.Canvas
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ControlNodeDescriptor
import pl.mos.backoffice.projects.domain.service.StateField
import pl.mos.backoffice.projects.domain.service.WorkflowValidationResult
import pl.mos.backoffice.shared.tasks.TaskExecutor
import java.util.UUID
import pl.mos.backoffice.projects.domain.model.WorkflowWorkspace

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

    data class WorkflowCommand(
        val name: String,
        val description: String = "",
        val nodes: List<WorkflowNode>,
        val edges: List<WorkflowEdge>,
        val triggers: List<Trigger> = listOf(Trigger(TriggerType.MANUAL)),
        val enabled: Boolean = true,
        val canvas: Canvas? = null,
        /** ADR 0044: run workspaces of the workflow. */
        val workspaces: List<WorkflowWorkspace> = emptyList(),
    )

    data class PaletteTask(
        val key: String,
        val name: String,
        val description: String,
        val executor: TaskExecutor,
        val interfaces: List<String>,
        val ports: List<String>,
        val projectScoped: Boolean,
        val configSchema: Map<String, Any?>,
        /** Connection capabilities a Jira task needs (ADR 0030); empty for other executors. */
        val requiredCapabilities: List<ConnectionCapability> = emptyList(),
        /** The task writes to an external system (a required capability has the `write` flag): approval before write applies (ADR 0033). */
        val externalWrite: Boolean = false,
    )

    /** `interfaces` is owned by the Task Library context and left empty here. */
    data class Palette(
        val controlNodes: List<ControlNodeDescriptor>,
        val tasks: List<PaletteTask>,
        val stateFields: List<StateField>,
    )

    data class ExpressionCheck(val valid: Boolean, val message: String?)

    companion object {
        fun projectUuid(projectId: ProjectId): UUID = projectId.value
    }
}
