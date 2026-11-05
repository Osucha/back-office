package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

/**
 * ADR 0055: a norm the agent always has in its context, unlike a [Skill], which the agent loads when it needs it.
 * Model answer steps get rules in the system prompt, coding agents at the start of their prompt. A rule applies to the
 * templates that list it in `rules`; an [automatic] rule applies to every step of its scope (global: every project,
 * project: every step of that project) unless a template drops it with `removeRules`. Uniqueness is by `(key, scope)`;
 * a project rule shadows the global one with the same key.
 */
data class Rule(
    val id: RuleId,
    val key: String,
    val name: String,
    val description: String,
    val content: String,
    val scope: Scope,
    val automatic: Boolean,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (content.isBlank()) throw ValidationException("must not be blank", "content")
    }

    /** Editable fields; a null [key] is derived from the name on create and kept on update. */
    data class Content(
        val key: String? = null,
        val name: String,
        val description: String = "",
        val content: String,
        val scope: Scope = Scope.Global,
        val automatic: Boolean = false,
        val tags: List<String> = emptyList(),
    )

    fun update(c: Content, now: Instant, actor: Actor? = null): Rule = copy(
        key = c.key ?: key, name = c.name, description = c.description, content = c.content, scope = c.scope,
        automatic = c.automatic, tags = c.tags.distinct(), updatedAt = now, updatedBy = actor,
    )

    companion object {
        fun create(c: Content, now: Instant, builtIn: Boolean = false, actor: Actor? = null): Rule = Rule(
            id = RuleId.new(),
            key = c.key ?: throw ValidationException("required", "key"),
            name = c.name,
            description = c.description,
            content = c.content,
            scope = c.scope,
            automatic = c.automatic,
            tags = c.tags.distinct(),
            builtIn = builtIn,
            createdAt = now,
            updatedAt = now,
            updatedBy = actor,
        )
    }
}
