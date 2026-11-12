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
 * `outputSchema` is well-formed JSON, `confidenceField` needs an `outputSchema`, parameter
 * declarations are consistent and preset values name declared parameters with fitting values (ADR 0058). Rules and
 * skills belong to agents (ADR 0057). All problems are collected into one
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

        template.outputSchema?.let { schema ->
            JsonSyntax.problem(schema)?.let { errors += FieldError("outputSchema", "not well-formed JSON: $it") }
        }
        if (template.confidenceField != null && template.outputSchema == null && ancestors.none { it.outputSchema != null }) {
            errors += FieldError("confidenceField", "requires an outputSchema on this template or an ancestor")
        }

        // ADR 0060: a required skill must exist where the template is visible
        template.requiresSkills.forEachIndexed { i, key ->
            if (lookup.visibleSkill(key, projectId) == null) errors += FieldError("requiresSkills[$i]", "skill $key does not exist")
        }

        errors += parameterProblems(template, ancestors)
        errors += outputFileProblems(template.outputFiles)
        return errors
    }

    /** ADR 0063: output files are globs relative to a repository root: no absolute paths, no `..`, bounded. */
    private fun outputFileProblems(files: List<String>): List<FieldError> = buildList {
        if (files.size > MAX_OUTPUT_FILES) add(FieldError(FIELD_OUTPUT_FILES, "at most $MAX_OUTPUT_FILES output files"))
        files.forEachIndexed { i, glob ->
            val field = "$FIELD_OUTPUT_FILES[$i]"
            when {
                glob.startsWith("/") || glob.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(glob) ->
                    add(FieldError(field, "$glob must be relative to the repository root (no leading /)"))
                glob.split('/', '\\').any { it == ".." } -> add(FieldError(field, "$glob must not contain .."))
                glob.length > MAX_OUTPUT_FILE_LENGTH -> add(FieldError(field, "$glob is longer than $MAX_OUTPUT_FILE_LENGTH characters"))
            }
        }
    }

    /**
     * ADR 0058: own declarations are consistent and unique by name; every `parameterValues` name is declared by the
     * template or an ancestor (child wins by name) and its value fits that declaration.
     */
    private fun parameterProblems(template: TaskTemplate, ancestors: List<TaskTemplate>): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        template.parameters.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { name ->
            errors += FieldError(FIELD_PARAMETERS, "parameter names must be unique; $name is declared more than once")
        }
        template.parameters.flatMap { it.problems() }.forEach { errors += FieldError(FIELD_PARAMETERS, it) }
        // nearest declaration first: the template's own, then its parent's...
        val declared = (listOf(template) + ancestors).flatMap { it.parameters }.distinctBy { it.name }.associateBy { it.name }
        template.parameterValues.forEach { (name, value) ->
            val parameter = declared[name]
            when {
                parameter == null -> errors += FieldError(FIELD_PARAMETER_VALUES, "parameter $name is not declared by this template or a template it extends")
                else -> parameter.valueProblem(value)?.let { errors += FieldError(FIELD_PARAMETER_VALUES, "parameter $name: $it") }
            }
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
        const val FIELD_PARAMETERS = "parameters"
        const val FIELD_PARAMETER_VALUES = "parameterValues"
        const val FIELD_OUTPUT_FILES = "outputFiles"
        const val MAX_OUTPUT_FILES = 50
        const val MAX_OUTPUT_FILE_LENGTH = 300
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
