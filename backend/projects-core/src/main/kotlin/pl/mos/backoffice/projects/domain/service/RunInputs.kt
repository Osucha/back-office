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
 * The run start form of a workflow (ADR 0053): library tasks declare run inputs, a workflow asks for the union of
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
     * declared field may be longer than [RunInputField.MAX_LENGTH], an `ISSUE_KEY` value must look like `ABC-123`. An
     * `ISSUE_KEY` field reads its parameter, else the request's issue key; its value becomes the run's issue key unless the request names one. Undeclared parameters
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
                // the value becomes the run's issue key: it reaches Jira calls and branch names, so only key-shaped text passes
                field.type == RunInputType.ISSUE_KEY && !value.isNullOrBlank() && !ISSUE_KEY.matches(value.trim()) ->
                    ValidationException.FieldError(
                        "parameters.${field.name}",
                        "run input \"${field.label}\" (${field.name}) must be an issue key such as ABC-123",
                    )
                else -> null
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val issueKey = requestKey ?: fields.filter { it.type == RunInputType.ISSUE_KEY }
            .firstNotNullOfOrNull { field -> valueOf(field, input, null)?.trim()?.takeIf { it.isNotEmpty() } }
        return input.copy(issueKey = issueKey)
    }

    private const val ISSUE_KEY_FROM = "issueKeyFrom"
    private const val ISSUE_KEY_FROM_INPUT = "input"

    /** A Jira issue key: project key, dash, number (`ABC-123`); upper or lower case. */
    private val ISSUE_KEY = Regex("^[A-Z][A-Z0-9_]{0,19}-[0-9]{1,10}$", RegexOption.IGNORE_CASE)

    private fun valueOf(field: RunInputField, input: RunInput, requestKey: String?): String? {
        val value = input.parameters[field.name]?.toString()
        return if (field.type == RunInputType.ISSUE_KEY && value.isNullOrBlank()) requestKey ?: value else value
    }

    /**
     * The run inputs the template of [node] declares, or none when the template is unknown or cannot be resolved. When
     * the step reads the issue key from another parameter (`config.issueKeyFrom` other than `input`), its `ISSUE_KEY`
     * fields are optional.
     */
    fun ofTask(node: WorkflowNode.Task, projectId: UUID, catalog: TaskCatalog): List<RunInputField> {
        if (node.task.templateKey.isBlank()) return emptyList()
        val task = try {
            catalog.resolve(node.task.templateKey, projectId, node.task.overrides)
        } catch (_: ValidationException) {
            null
        } ?: return emptyList()
        // a step that reads the issue key from another run parameter (`issueKeyFrom`) does not need the run's key
        val from = task.config[ISSUE_KEY_FROM]?.toString()?.trim()
        if (from.isNullOrEmpty() || from == ISSUE_KEY_FROM_INPUT) return task.runInputs
        return task.runInputs.map { if (it.type == RunInputType.ISSUE_KEY) it.copy(required = false) else it }
    }
}
