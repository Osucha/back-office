package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.ValidationException.FieldError
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Keys
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * Structural checks of a template against the rest of the library, run before a template is saved:
 * key format, parent existence and no self-extension, the agent (ADR 0056) exists when one is named,
 * `outputSchema` is well-formed JSON, `confidenceField` needs an `outputSchema`, referenced skills and rules exist,
 * run input names are unique. All problems are collected into one
 * `ValidationException`.
 */
class TemplateValidator(private val lookup: LibraryLookup) {

    fun validate(template: TaskTemplate) {
        val errors = problems(template)
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    fun problems(template: TaskTemplate): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val projectId = template.scope.projectId

        if (!Keys.isValid(template.key)) errors += FieldError("key", "must match ${Keys.PATTERN.pattern}")

        val parent = resolveParent(template, projectId, errors)
        // a project template shadowing the global one extends a parent with its own key: that parent is no cycle
        val ancestors = ancestors(parent, projectId, template.key.takeUnless { parent?.key == template.key }).toList()

        template.agentKey?.let { key ->
            if (lookup.visibleAgent(key, projectId) == null) errors += FieldError("agentKey", "agent $key does not exist")
        }

        template.outputSchema?.let { schema ->
            JsonSyntax.problem(schema)?.let { errors += FieldError("outputSchema", "not well-formed JSON: $it") }
        }
        if (template.confidenceField != null && template.outputSchema == null && ancestors.none { it.outputSchema != null }) {
            errors += FieldError("confidenceField", "requires an outputSchema on this template or an ancestor")
        }

        template.skills.forEach { key ->
            if (lookup.visibleSkill(key, projectId) == null) errors += FieldError("skills", "skill $key does not exist")
        }
        template.rules.forEach { key ->
            if (lookup.visibleRule(key, projectId) == null) errors += FieldError("rules", "rule $key does not exist")
        }
        // blank names and labels are refused by RunInputField itself
        template.runInputs.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { name ->
            errors += FieldError("runInputs", "run input names must be unique; $name is declared more than once")
        }
        return errors
    }

    /** The template [template] extends (a project template may shadow the global one of the same key), or null. */
    private fun resolveParent(template: TaskTemplate, projectId: UUID?, errors: MutableList<FieldError>): TaskTemplate? {
        val parentKey = template.extendsKey ?: return null
        val problem: String?
        val parent: TaskTemplate?
        when {
            parentKey == template.key && template.scope is Scope.Project -> {
                // shadowing: a project template extends the global template with the same key
                parent = lookup.template(parentKey, Scope.Global)
                problem = if (parent == null) "no global template $parentKey to shadow" else null
            }
            parentKey == template.key -> { parent = null; problem = "a template cannot extend itself" }
            !Keys.isValid(parentKey) -> { parent = null; problem = "must match ${Keys.PATTERN.pattern}" }
            else -> {
                parent = lookup.visibleTemplate(parentKey, projectId)
                problem = if (parent == null) "template $parentKey does not exist" else null
            }
        }
        if (problem != null) errors += FieldError(FIELD_EXTENDS_KEY, problem)
        return parent
    }

    private companion object {
        const val FIELD_EXTENDS_KEY = "extendsKey"
    }

    /**
     * Walks up from [start] without throwing: a cycle back to [selfKey] or an unknown parent simply ends
     * the walk (the resolver reports those as errors when the chain is used).
     */
    private fun ancestors(start: TaskTemplate?, projectId: UUID?, selfKey: String?): Sequence<TaskTemplate> = sequence {
        val seen = mutableSetOf<String>().apply { selfKey?.let { add(it) } }
        var current = start
        while (current != null && seen.add(current.key) && seen.size <= TaskResolver.MAX_DEPTH) {
            yield(current)
            current = current.extendsKey?.let { lookup.visibleTemplate(it, projectId) }
        }
    }
}
