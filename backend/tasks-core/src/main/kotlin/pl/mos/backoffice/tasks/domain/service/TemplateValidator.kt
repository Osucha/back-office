package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.ValidationException.FieldError
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Keys
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * Structural checks of a template against the rest of the library, run before a template is saved:
 * key format, parent existence and no self-extension, executor present on concrete templates unless an
 * ancestor provides one, `outputSchema` is well-formed JSON, `confidenceField` needs an `outputSchema`,
 * implemented interfaces and referenced skills exist. All problems are collected into one
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
        val ancestors = ancestors(parent, projectId, template.key).toList()

        if (!template.abstract && template.executor == null && ancestors.none { it.executor != null }) {
            errors += FieldError("executor", "required on a concrete template unless inherited from an ancestor")
        }

        template.outputSchema?.let { schema ->
            JsonSyntax.problem(schema)?.let { errors += FieldError("outputSchema", "not well-formed JSON: $it") }
        }
        if (template.confidenceField != null && template.outputSchema == null && ancestors.none { it.outputSchema != null }) {
            errors += FieldError("confidenceField", "requires an outputSchema on this template or an ancestor")
        }

        template.implements.forEach { key ->
            if (lookup.taskInterface(key) == null) errors += FieldError("implements", "interface $key does not exist")
        }
        template.skills.forEach { key ->
            if (lookup.visibleSkill(key, projectId) == null) errors += FieldError("skills", "skill $key does not exist")
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
    private fun ancestors(start: TaskTemplate?, projectId: UUID?, selfKey: String): Sequence<TaskTemplate> = sequence {
        val seen = mutableSetOf(selfKey)
        var current = start
        while (current != null && seen.add(current.key) && seen.size <= TaskResolver.MAX_DEPTH) {
            yield(current)
            current = current.extendsKey?.let { lookup.visibleTemplate(it, projectId) }
        }
    }
}
