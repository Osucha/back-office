package pl.mos.backoffice.tasks.application.port.`in`

import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import java.util.UUID

/**
 * Use case: maintain agents (ADR 0056). Uniqueness is `(key, scope)`; a project agent shadows a global one with the
 * same key. A new agent without a key gets one derived from its name. Deleting an agent a template names as its agent
 * is refused (`InvalidStateException`).
 */
interface ManageAgents {
    fun create(command: Agent.Content): Agent
    fun update(id: AgentId, command: Agent.Content): Agent
    fun get(id: AgentId): Agent
    /** Global agents plus, when [projectId] is given, that project's own. */
    fun list(projectId: UUID? = null): List<Agent>
    fun delete(id: AgentId)
}
