package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.ValidationException.FieldError
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.tasks.application.port.`in`.ManageAgents
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.Keys
import pl.mos.backoffice.tasks.domain.service.LibraryLookup
import java.util.UUID

/**
 * Agent CRUD (ADR 0056/0057). Same access rules as rules and skills (global: `library.view` / `library.manage`; project:
 * members read, `library.project.manage` writes); an agent a template names keeps its key and scope and cannot be
 * deleted. ADR 0057: every rule and skill an agent lists must be visible in its scope (a global agent: global entries;
 * a project agent: the project's and the global ones). Changes are audited as `AGENT_CREATE`, `AGENT_UPDATE`,
 * `AGENT_DELETE`.
 */
class AgentService(
    private val agents: AgentRepository,
    private val templates: TaskTemplateRepository,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val lookup: LibraryLookup,
) : ManageAgents {

    override fun create(command: Agent.Content): Agent {
        val user = gate.requireWrite(command.scope)
        val key = command.key ?: Keys.unique(Keys.fromName(command.name, KEY_PREFIX)) { agents.existsByKey(it) }
        if (agents.findByKeyAndScope(key, command.scope) != null) {
            throw InvalidStateException("agent $key already exists in scope ${command.scope.describe()}")
        }
        validateEnvironment(command)
        val saved = agents.save(Agent.create(command.copy(key = key), clock.now(), actor = user.actor))
        audit.record(entry("AGENT_CREATE", saved, mapOf(
            "key" to saved.key, "scope" to saved.scope.describe(), "harness" to saved.harness?.name,
            "modelProfileId" to saved.modelProfileId?.toString(), "instructionsLength" to saved.instructions.length,
            "rules" to saved.rules, "skills" to saved.skills, "mcpServers" to saved.mcpServers.map { "${it.name}=${it.url}" },
        )))
        return saved
    }

    override fun update(id: AgentId, command: Agent.Content): Agent {
        val user = gate.requireWrite(command.scope)
        val existing = load(id)
        if (existing.scope != command.scope) gate.requireWrite(existing.scope)
        val key = command.key ?: existing.key
        agents.findByKeyAndScope(key, command.scope)?.let { clash ->
            if (clash.id != id) throw InvalidStateException("agent $key already exists in scope ${command.scope.describe()}")
        }
        if ((key != existing.key || command.scope != existing.scope) && inUse(existing)) {
            throw InvalidStateException("agent ${existing.key} is used by templates; its key and scope cannot change")
        }
        validateEnvironment(command)
        val saved = agents.save(existing.update(command.copy(key = key), clock.now(), user.actor))
        val changes = AuditableChanges.changed(
            "key" to (existing.key to saved.key),
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "scope" to (existing.scope.describe() to saved.scope.describe()),
            "harness" to (existing.harness?.name to saved.harness?.name),
            "modelProfileId" to (existing.modelProfileId?.toString() to saved.modelProfileId?.toString()),
            "tags" to (existing.tags to saved.tags),
            "instructionsLength" to (existing.instructions.length to saved.instructions.length),
            "instructionsChanged" to (false to (existing.instructions != saved.instructions)),
            "rules" to (existing.rules to saved.rules),
            "skills" to (existing.skills to saved.skills),
            "mcpServers" to (existing.mcpServers.map { "${it.name}=${it.url}" } to saved.mcpServers.map { "${it.name}=${it.url}" }),
        )
        if (changes.isNotEmpty()) audit.record(entry("AGENT_UPDATE", saved, changes))
        return saved
    }

    override fun get(id: AgentId): Agent {
        val agent = load(id)
        gate.requireRead(agent.scope)
        return agent
    }

    override fun list(projectId: UUID?): List<Agent> {
        val access = gate.requireListing(projectId)
        return agents.findVisible(access.projectId)
            .filter { access.allows(it.scope) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.scope.projectId != null }))
    }

    override fun delete(id: AgentId) {
        val existing = load(id)
        gate.requireWrite(existing.scope)
        if (inUse(existing)) throw InvalidStateException("agent ${existing.key} is used by templates and cannot be deleted")
        agents.delete(id)
        audit.record(entry("AGENT_DELETE", existing, mapOf("key" to existing.key, "scope" to existing.scope.describe())))
    }

    /** ADR 0057: the rules and skills the agent lists exist in its scope; one `ValidationException` with every problem. */
    private fun validateEnvironment(command: Agent.Content) {
        val projectId = command.scope.projectId
        val errors = mutableListOf<FieldError>()
        command.rules.distinct().forEachIndexed { i, key ->
            if (lookup.visibleRule(key, projectId) == null) errors += FieldError("rules[$i]", "rule $key does not exist in scope ${command.scope.describe()}")
        }
        command.skills.distinct().forEachIndexed { i, key ->
            if (lookup.visibleSkill(key, projectId) == null) errors += FieldError("skills[$i]", "skill $key does not exist in scope ${command.scope.describe()}")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    /** A template that sees the agent names it: any template for a global agent, the project's and global ones for a project agent. */
    private fun inUse(agent: Agent): Boolean = templates.existsUsingAgent(agent.key, agent.scope.projectId)

    private fun load(id: AgentId): Agent = agents.findById(id) ?: throw NotFoundException("Agent", id)

    private fun entry(operation: String, a: Agent, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "Agent", entityId = a.id.toString(), projectId = a.scope.projectId, details = details)

    private companion object {
        const val KEY_PREFIX = "agent"
    }
}
