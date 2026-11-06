package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import java.time.Instant

/**
 * ADR 0056: who runs a task, a [harness] and optionally a model. Without a harness the step is one model answer
 * (no tools, no repository); with `OPENCODE` or `CLAUDE_CODE` it is a coding agent session in the runner sandbox.
 * Without a [modelProfileId] the step uses the defaults for its kind. Uniqueness is by `(key, scope)`; a project agent
 * shadows the global one with the same key.
 */
data class Agent(
    val id: AgentId,
    val key: String,
    val name: String,
    val description: String,
    val scope: Scope,
    val harness: AgentHarness?,
    val modelProfileId: ModelProfileId?,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
    }

    /** What a resolved task carries. */
    val snapshot: AgentSnapshot get() = AgentSnapshot(key, name, harness, modelProfileId)

    /** Editable fields; a null [key] is derived from the name on create and kept on update. */
    data class Content(
        val key: String? = null,
        val name: String,
        val description: String = "",
        val scope: Scope = Scope.Global,
        val harness: AgentHarness? = null,
        val modelProfileId: ModelProfileId? = null,
        val tags: List<String> = emptyList(),
    )

    fun update(c: Content, now: Instant, actor: Actor? = null): Agent = copy(
        key = c.key ?: key, name = c.name, description = c.description, scope = c.scope, harness = c.harness,
        modelProfileId = c.modelProfileId, tags = c.tags.distinct(), updatedAt = now, updatedBy = actor,
    )

    companion object {
        fun create(c: Content, now: Instant, builtIn: Boolean = false, actor: Actor? = null): Agent = Agent(
            id = AgentId.new(),
            key = c.key ?: throw ValidationException("required", "key"),
            name = c.name,
            description = c.description,
            scope = c.scope,
            harness = c.harness,
            modelProfileId = c.modelProfileId,
            tags = c.tags.distinct(),
            builtIn = builtIn,
            createdAt = now,
            updatedAt = now,
            updatedBy = actor,
        )
    }
}
