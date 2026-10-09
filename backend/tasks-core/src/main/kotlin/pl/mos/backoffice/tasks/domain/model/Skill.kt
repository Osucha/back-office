package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

/**
 * A reusable markdown instruction block following the Agent Skills `SKILL.md` convention (frontmatter
 * with `name` and `description`, then rules). LLM tasks get it appended to the system prompt; runner
 * tasks get it written into the workspace. Uniqueness is by `(key, scope)`.
 */
data class Skill(
    val id: SkillId,
    val key: String,
    val name: String,
    val description: String,
    val content: String,
    val scope: Scope,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (ADR 0017); null for rows written before actor stamps existed. */
    val updatedBy: Actor? = null,
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (content.isBlank()) throw ValidationException("must not be blank", "content")
    }

    data class Content(
        val key: String,
        val name: String,
        val description: String = "",
        val content: String,
        val scope: Scope = Scope.Global,
        val tags: List<String> = emptyList(),
    )

    fun update(c: Content, now: Instant, actor: Actor? = null): Skill = copy(
        key = c.key, name = c.name, description = c.description, content = c.content, scope = c.scope, tags = c.tags.distinct(),
        updatedAt = now, updatedBy = actor,
    )

    companion object {
        fun create(c: Content, now: Instant, builtIn: Boolean = false, actor: Actor? = null): Skill = Skill(
            id = SkillId.new(),
            key = c.key,
            name = c.name,
            description = c.description,
            content = c.content,
            scope = c.scope,
            tags = c.tags.distinct(),
            builtIn = builtIn,
            createdAt = now,
            updatedAt = now,
            updatedBy = actor,
        )
    }
}
