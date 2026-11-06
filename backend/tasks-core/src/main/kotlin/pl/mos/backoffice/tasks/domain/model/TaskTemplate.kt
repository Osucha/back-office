package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.TemplateCategory
import java.time.Instant

/** How a template's prompt combines with its parent's: appended after a blank line, or replacing it. */
enum class PromptMode { APPEND, REPLACE }

/** System and user prompt fragments of a template. `mode` applies to both fields, per field only when the field is set. */
data class Prompt(
    val system: String? = null,
    val user: String? = null,
    val mode: PromptMode = PromptMode.APPEND,
) {
    companion object {
        val EMPTY = Prompt()
    }
}

/**
 * A task definition ("class"): what an agent does and how it is prompted. Templates form single-parent
 * inheritance chains through [extendsKey] and are merged by the `TaskResolver` into a `ResolvedTask`. Structural
 * rules that need other templates, skills or rules are checked by `TemplateValidator`; this class only guards its
 * own invariants. ADR 0055: [category] groups templates (inherited, child wins); [rules] are always in the context.
 */
data class TaskTemplate(
    val id: TaskTemplateId,
    val key: String,
    val name: String,
    val description: String,
    val abstract: Boolean,
    val extendsKey: String?,
    val category: TemplateCategory?,
    /** ADR 0056: the agent that runs the task by default; inherited, nearest non-null wins; none anywhere = model-answer. */
    val agentKey: String?,
    val scope: Scope,
    val modelProfileId: ModelProfileId?,
    val prompt: Prompt,
    val skills: List<String>,
    val removeSkills: List<String>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val tags: List<String>,
    /** Run start form fields this task asks for (ADR 0053); merged child-wins by name over the lineage. */
    val runInputs: List<RunInputField>,
    val builtIn: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (ADR 0017); null for rows written before actor stamps existed. */
    val updatedBy: Actor? = null,
    /** ADR 0055: keys of rules this template adds; inherited and unioned like skills. */
    val rules: List<String> = emptyList(),
    /** Inherited or automatic rule keys this template drops. */
    val removeRules: List<String> = emptyList(),
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (version < 1) throw ValidationException("must be positive", "version")
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
        val agentKey: String? = null,
        val scope: Scope = Scope.Global,
        val modelProfileId: ModelProfileId? = null,
        val prompt: Prompt = Prompt.EMPTY,
        val skills: List<String> = emptyList(),
        val removeSkills: List<String> = emptyList(),
        val config: Map<String, Any?> = emptyMap(),
        val outputSchema: String? = null,
        val confidenceField: String? = null,
        val produces: List<String> = emptyList(),
        val consumes: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val runInputs: List<RunInputField> = emptyList(),
        val rules: List<String> = emptyList(),
        val removeRules: List<String> = emptyList(),
    )

    val content: Content
        get() = Content(
            key, name, description, abstract, extendsKey, category, agentKey, scope, modelProfileId, prompt,
            skills, removeSkills, config, outputSchema, confidenceField, produces, consumes, tags, runInputs, rules, removeRules,
        )

    /** Replaces the editable content and bumps [version]; a null key keeps the current one. */
    fun update(content: Content, now: Instant, actor: Actor? = null): TaskTemplate =
        from(content.copy(key = content.key ?: key), id, builtIn, version + 1, createdAt, now, actor)

    companion object {
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
            agentKey = c.agentKey?.takeIf { it.isNotBlank() },
            scope = c.scope,
            modelProfileId = c.modelProfileId,
            prompt = c.prompt,
            skills = c.skills.distinct(),
            removeSkills = c.removeSkills.distinct(),
            config = c.config,
            outputSchema = c.outputSchema?.takeIf { it.isNotBlank() },
            confidenceField = c.confidenceField?.takeIf { it.isNotBlank() },
            produces = c.produces.distinct(),
            consumes = c.consumes.distinct(),
            tags = c.tags.distinct(),
            runInputs = c.runInputs,
            rules = c.rules.distinct(),
            removeRules = c.removeRules.distinct(),
            builtIn = builtIn,
            version = version,
            createdAt = createdAt,
            updatedAt = updatedAt,
            updatedBy = actor,
        )
    }
}
