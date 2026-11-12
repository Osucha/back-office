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
 * The run start form of a workflow (ADR 0053/0062): task steps contribute the parameters they bind `START`, actions (ADR
 * 0056) their run inputs; a workflow asks
 * for the union of them. The START node declares nothing of its own.
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
        nodes.forEach { node ->
            val fields = when (node) {
                is WorkflowNode.Task -> ofTask(node, projectId, catalog)
                is WorkflowNode.Action -> ActionCatalog.runInputs(node.action)
                else -> emptyList()
            }
            fields.forEach { field ->
                val first = merged[field.name]
                merged[field.name] = if (first == null) field else first.copy(required = first.required || field.required)
            }
        }
        return merged.values.toList()
    }

    /**
     * Checks [input] against the declared [fields] of a REST start: a required field must not be missing or blank, no
     * declared field may be longer than [RunInputField.MAX_LENGTH], an `ISSUE_KEY` value must look like `ABC-123`
     * (also the request's own `issueKey` when the workflow declares an `ISSUE_KEY` field). An `ISSUE_KEY` field reads
     * its parameter, else the request's issue key; its value becomes the run's issue key unless the request names one.
     * Undeclared parameters are kept as they are. Throws one `ValidationException` naming every failing field.
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
                field.type == RunInputType.ISSUE_KEY && !value.isNullOrBlank() && !isIssueKey(value.trim()) ->
                    ValidationException.FieldError(
                        "parameters.${field.name}",
                        "run input \"${field.label}\" (${field.name}) must be an issue key such as ABC-123",
                    )
                // ADR 0062: a START-bound parameter checks its type (number, true/false, one of the options)
                !value.isNullOrBlank() && field.parameter?.valueProblem(value.trim()) != null ->
                    ValidationException.FieldError(
                        "parameters.${field.name}",
                        "run input \"${field.label}\" (${field.name}): ${field.parameter?.valueProblem(value.trim())}",
                    )
                else -> null
            }
        }
        // The request's own key wins for the run (below), so it needs the same shape. A field whose parameter is blank
        // already checked it through valueOf; when every ISSUE_KEY field has its own value, check it here.
        val keyFields = fields.filter { it.type == RunInputType.ISSUE_KEY }
        val requestKeyUnchecked = keyFields.isNotEmpty() && keyFields.none { input.parameters[it.name]?.toString().isNullOrBlank() }
        val requestKeyError = requestKey?.takeIf { requestKeyUnchecked && !isIssueKey(it) }
            ?.let { ValidationException.FieldError("issueKey", "the issue key must be an issue key such as ABC-123") }
        val allErrors = errors + listOfNotNull(requestKeyError)
        if (allErrors.isNotEmpty()) throw ValidationException(allErrors)
        val issueKey = requestKey ?: fields.filter { it.type == RunInputType.ISSUE_KEY }
            .firstNotNullOfOrNull { field -> valueOf(field, input, null)?.trim()?.takeIf { it.isNotEmpty() } }
        return input.copy(issueKey = issueKey)
    }

    private const val ISSUE_KEY_FROM = ActionCatalog.ISSUE_KEY_FROM
    private const val ISSUE_KEY_FROM_INPUT = ActionCatalog.ISSUE_KEY_FROM_INPUT

    /** A Jira issue key: project key, dash, number (`ABC-123`); upper or lower case. */
    private val ISSUE_KEY = Regex("^[A-Z][A-Z0-9_]{0,19}-[0-9]{1,10}$", RegexOption.IGNORE_CASE)

    private fun isIssueKey(value: String): Boolean = value.length <= RunInputField.MAX_LENGTH && ISSUE_KEY.matches(value)

    private fun valueOf(field: RunInputField, input: RunInput, requestKey: String?): String? {
        val value = input.parameters[field.name]?.toString()
        return if (field.type == RunInputType.ISSUE_KEY && value.isNullOrBlank()) requestKey ?: value else value
    }

    /**
     * The run start fields of task step [node] (ADR 0062: the parameters it binds `START`), or none when the template is
     * unknown or cannot be resolved. When
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
