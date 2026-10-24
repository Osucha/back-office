package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskCatalog
import java.util.UUID

/**
 * The run start form of a workflow (ADR 0052): library tasks declare run inputs, a workflow asks for the union of
 * the inputs of its task steps. The START node declares nothing of its own.
 */
object RunInputs {

    /**
     * Union of the resolved run inputs of all task nodes of [definition] (node overrides applied), in definition
     * order: a field is required when any task requires it; the first declaration's label, type and help win.
     * Unknown or unresolvable templates contribute nothing (the run start refuses such workflows anyway).
     */
    fun effective(definition: WorkflowDefinition, catalog: TaskCatalog): List<RunInputField> =
        effective(definition.nodes, definition.projectId.value, catalog)

    fun effective(nodes: List<WorkflowNode>, projectId: UUID, catalog: TaskCatalog): List<RunInputField> {
        val merged = LinkedHashMap<String, RunInputField>()
        nodes.filterIsInstance<WorkflowNode.Task>().forEach { node ->
            ofTask(node, projectId, catalog).forEach { field ->
                val first = merged[field.name]
                merged[field.name] = if (first == null) field else first.copy(required = first.required || field.required)
            }
        }
        return merged.values.toList()
    }

    /**
     * Checks [input] against the declared [fields] of a REST start: a required field must not be missing or blank, no
     * declared field may be longer than [RunInputField.MAX_LENGTH]. An `ISSUE_KEY` field reads its parameter, else the
     * request's issue key; its value becomes the run's issue key unless the request names one. Undeclared parameters
     * are kept as they are. Throws one `ValidationException` naming every failing field (`parameters.<name>`).
     */
    fun check(fields: List<RunInputField>, input: RunInput): RunInput {
        val requestKey = input.issueKey?.trim()?.takeIf { it.isNotEmpty() }
        val errors = fields.mapNotNull { field ->
            val value = valueOf(field, input, requestKey)
            when {
                field.required && value.isNullOrBlank() ->
                    ValidationException.FieldError("parameters.${field.name}", "run input \"${field.label}\" (${field.name}) is required")
                value != null && value.length > RunInputField.MAX_LENGTH ->
                    ValidationException.FieldError(
                        "parameters.${field.name}",
                        "run input \"${field.label}\" (${field.name}) is longer than ${RunInputField.MAX_LENGTH} characters",
                    )
                else -> null
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val issueKey = requestKey ?: fields.filter { it.type == RunInputType.ISSUE_KEY }
            .firstNotNullOfOrNull { field -> valueOf(field, input, null)?.trim()?.takeIf { it.isNotEmpty() } }
        return input.copy(issueKey = issueKey)
    }

    private fun valueOf(field: RunInputField, input: RunInput, requestKey: String?): String? {
        val value = input.parameters[field.name]?.toString()
        return if (field.type == RunInputType.ISSUE_KEY && value.isNullOrBlank()) requestKey ?: value else value
    }

    /** The run inputs the template of [node] declares, or none when the template is unknown or cannot be resolved. */
    fun ofTask(node: WorkflowNode.Task, projectId: UUID, catalog: TaskCatalog): List<RunInputField> {
        if (node.task.templateKey.isBlank()) return emptyList()
        return try {
            catalog.resolve(node.task.templateKey, projectId, node.task.overrides)?.runInputs.orEmpty()
        } catch (_: ValidationException) {
            emptyList()
        }
    }
}
