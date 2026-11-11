package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import java.time.Instant

/**
 * ADR 0056/0057: who works on a task and how: a [harness], optionally a model, and the agent's environment:
 * [instructions] (its role and way of working, markdown, may use `{{project.*}}`), [rules] (always in context, after the
 * automatic ones), [skills] (loaded on demand) and [mcpServers] (remote, no credentials). Without a harness the step is
 * one model answer (no tools, no repository); with `OPENCODE` or `CLAUDE_CODE` it is a coding agent session in the runner
 * sandbox. Without a [modelProfileId] the step uses the defaults for its kind. Uniqueness is by `(key, scope)`; a project
 * agent shadows the global one with the same key. That every rule and skill exists is checked by the service on save.
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
    val instructions: String = "",
    val rules: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val mcpServers: List<AgentMcpServer> = emptyList(),
    /** ADR 0060: what the agent may change (coding agents). */
    val access: AgentAccess = AgentAccess.FULL,
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (instructions.length > MAX_INSTRUCTIONS_LENGTH) throw ValidationException("is longer than $MAX_INSTRUCTIONS_LENGTH characters", "instructions")
        if (mcpServers.size > AgentMcpServer.MAX_PER_AGENT) throw ValidationException("at most ${AgentMcpServer.MAX_PER_AGENT} MCP servers per agent", "mcpServers")
        mcpServers.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ValidationException("MCP server names must be unique; $it is listed more than once", "mcpServers.name")
        }
    }

    /** What a resolved task carries. */
    val snapshot: AgentSnapshot get() = AgentSnapshot(key, name, harness, modelProfileId, instructions, rules, skills, mcpServers, access)

    /** Editable fields; a null [key] is derived from the name on create and kept on update. */
    data class Content(
        val key: String? = null,
        val name: String,
        val description: String = "",
        val scope: Scope = Scope.Global,
        val harness: AgentHarness? = null,
        val modelProfileId: ModelProfileId? = null,
        val tags: List<String> = emptyList(),
        val instructions: String = "",
        val rules: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val mcpServers: List<AgentMcpServer> = emptyList(),
        val access: AgentAccess = AgentAccess.FULL,
    )

    val content: Content
        get() = Content(key, name, description, scope, harness, modelProfileId, tags, instructions, rules, skills, mcpServers, access)

    fun update(c: Content, now: Instant, actor: Actor? = null): Agent = copy(
        key = c.key ?: key, name = c.name, description = c.description, scope = c.scope, harness = c.harness,
        modelProfileId = c.modelProfileId, tags = c.tags.distinct(), instructions = c.instructions, rules = c.rules.distinct(),
        skills = c.skills.distinct(), mcpServers = c.mcpServers, access = c.access, updatedAt = now, updatedBy = actor,
    )

    companion object {
        /** Bounds the environment document every step of the agent carries. */
        const val MAX_INSTRUCTIONS_LENGTH = 20_000

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
            instructions = c.instructions,
            rules = c.rules.distinct(),
            skills = c.skills.distinct(),
            mcpServers = c.mcpServers,
            access = c.access,
        )
    }
}
