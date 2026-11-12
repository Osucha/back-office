package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameter
import java.time.Instant

/** ADR 0062: how a template's instructions combine with its parent's: appended after a blank line, or replacing them. */
enum class InstructionsMode { APPEND, REPLACE }

/**
 * A task definition ("class"): the work an agent does. Templates form single-parent inheritance chains through
 * [extendsKey] and are merged by the `TaskResolver` into a `ResolvedTask`. Structural rules that need other templates or
 * agents are checked by `TemplateValidator`; this class only guards its own invariants. ADR 0055: [category] groups
 * templates (inherited, child wins). ADR 0057: rules and skills are the agent's, not the template's. ADR 0061: the
 * template is only the work; the step chooses the agent and the model. ADR 0058: [parameters] the instructions read as
 * `{{params.<name>}}` (inherited, child wins by name) and [parameterValues] for parameters of the lineage (a preset).
 * ADR 0062: one [instructions] text (what to do and the acceptance criteria) combined with the parent's by
 * [instructionsMode]; the platform adds the standard data sections; no run inputs (a step binds parameters `START`).
 */
data class TaskTemplate(
    val id: TaskTemplateId,
    val key: String,
    val name: String,
    val description: String,
    val abstract: Boolean,
    val extendsKey: String?,
    val category: TemplateCategory?,
    val scope: Scope,
    val instructions: String?,
    val instructionsMode: InstructionsMode,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val tags: List<String>,
    val builtIn: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (ADR 0017); null for rows written before actor stamps existed. */
    val updatedBy: Actor? = null,
    /** ADR 0058: parameters this template declares; merged root to leaf, child wins by name. */
    val parameters: List<TemplateParameter> = emptyList(),
    /** ADR 0058: values for parameters of the lineage; the nearest template wins, the step wins over all. */
    val parameterValues: Map<String, Any?> = emptyMap(),
    /** ADR 0060: skills the work needs; a step whose agent lacks one gets a warning (inherited, unioned). */
    val requiresSkills: List<String> = emptyList(),
    /**
     * ADR 0063: globs of files (relative to the repository) the agent leaves that come back as artefacts, named like the
     * file without extension (the former config key `collectArtifacts`); inherited, unioned.
     */
    val outputFiles: List<String> = emptyList(),
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (version < 1) throw ValidationException("must be positive", "version")
        if ((instructions?.length ?: 0) > MAX_INSTRUCTIONS_LENGTH) {
            throw ValidationException("is longer than $MAX_INSTRUCTIONS_LENGTH characters", "instructions")
        }
    }

    /**
     * Field values an owner can edit; identity, scope flags and audit fields are managed by the aggregate. A null [key]
     * is derived from the name on create and kept on update (ADR 0055).
     */
    data class Content(
        val key: String? = null,
        val name: String,
        val description: String = "",
        val abstract: Boolean = false,
        val extendsKey: String? = null,
        val category: TemplateCategory? = null,
        val scope: Scope = Scope.Global,
        val instructions: String? = null,
        val instructionsMode: InstructionsMode = InstructionsMode.APPEND,
        val config: Map<String, Any?> = emptyMap(),
        val outputSchema: String? = null,
        val confidenceField: String? = null,
        val produces: List<String> = emptyList(),
        val consumes: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val parameters: List<TemplateParameter> = emptyList(),
        val parameterValues: Map<String, Any?> = emptyMap(),
        val requiresSkills: List<String> = emptyList(),
        val outputFiles: List<String> = emptyList(),
    )

    val content: Content
        get() = Content(
            key, name, description, abstract, extendsKey, category, scope, instructions, instructionsMode,
            config, outputSchema, confidenceField, produces, consumes, tags, parameters, parameterValues, requiresSkills, outputFiles,
        )

    /** Replaces the editable content and bumps [version]; a null key keeps the current one. */
    fun update(content: Content, now: Instant, actor: Actor? = null): TaskTemplate =
        from(content.copy(key = content.key ?: key), id, builtIn, version + 1, createdAt, now, actor)

    companion object {
        /** Bounds what every step of the template carries in its message. */
        const val MAX_INSTRUCTIONS_LENGTH = 100_000

        fun create(content: Content, now: Instant, builtIn: Boolean = false, actor: Actor? = null): TaskTemplate =
            from(content, TaskTemplateId.new(), builtIn, 1, now, now, actor)

        private fun from(c: Content, id: TaskTemplateId, builtIn: Boolean, version: Int, createdAt: Instant, updatedAt: Instant, actor: Actor?) = TaskTemplate(
            id = id,
            key = c.key ?: throw ValidationException("required", "key"),
            name = c.name,
            description = c.description,
            abstract = c.abstract,
            extendsKey = c.extendsKey?.takeIf { it.isNotBlank() },
            category = c.category,
            scope = c.scope,
            instructions = c.instructions?.takeIf { it.isNotBlank() },
            instructionsMode = c.instructionsMode,
            config = c.config,
            outputSchema = c.outputSchema?.takeIf { it.isNotBlank() },
            confidenceField = c.confidenceField?.takeIf { it.isNotBlank() },
            produces = c.produces.distinct(),
            consumes = c.consumes.distinct(),
            tags = c.tags.distinct(),
            builtIn = builtIn,
            version = version,
            createdAt = createdAt,
            updatedAt = updatedAt,
            updatedBy = actor,
            parameters = c.parameters,
            parameterValues = c.parameterValues,
            requiresSkills = c.requiresSkills.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            outputFiles = c.outputFiles.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
