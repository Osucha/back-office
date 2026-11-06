package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.tasks.application.port.`in`.ManageSkills
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.domain.model.Keys
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import java.util.UUID

/**
 * Skill CRUD. A new skill without a key gets one derived from its name (ADR 0055); a skill an agent lists (ADR 0057)
 * keeps its key and cannot be deleted. Same access rules as
 * templates (global: `library.view` / `library.manage`; project: members read, `library.project.manage`
 * writes); changes are audited as `SKILL_CREATE`, `SKILL_UPDATE`, `SKILL_DELETE`.
 */
class SkillService(
    private val skills: SkillRepository,
    private val agents: AgentRepository,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageSkills {

    override fun create(command: Skill.Content): Skill {
        val user = gate.requireWrite(command.scope)
        val key = command.key ?: Keys.unique(Keys.fromName(command.name, KEY_PREFIX)) { skills.existsByKey(it) }
        if (skills.findByKeyAndScope(key, command.scope) != null) {
            throw InvalidStateException("skill $key already exists in scope ${command.scope.describe()}")
        }
        val saved = skills.save(Skill.create(command.copy(key = key), clock.now(), actor = user.actor))
        audit.record(entry("SKILL_CREATE", saved, mapOf("key" to saved.key, "scope" to saved.scope.describe(), "contentLength" to saved.content.length)))
        return saved
    }

    override fun update(id: SkillId, command: Skill.Content): Skill {
        val user = gate.requireWrite(command.scope)
        val existing = load(id)
        if (existing.scope != command.scope) gate.requireWrite(existing.scope)
        val key = command.key ?: existing.key
        skills.findByKeyAndScope(key, command.scope)?.let { clash ->
            if (clash.id != id) throw InvalidStateException("skill $key already exists in scope ${command.scope.describe()}")
        }
        if ((key != existing.key || command.scope != existing.scope) && agents.existsUsingSkill(existing.key)) {
            throw InvalidStateException("skill ${existing.key} is used by agents; its key and scope cannot change")
        }
        val saved = skills.save(existing.update(command.copy(key = key), clock.now(), user.actor))
        val changes = AuditableChanges.changed(
            "key" to (existing.key to saved.key),
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "scope" to (existing.scope.describe() to saved.scope.describe()),
            "contentLength" to (existing.content.length to saved.content.length),
            "contentChanged" to (false to (existing.content != saved.content)),
            "tags" to (existing.tags to saved.tags),
        )
        if (changes.isNotEmpty()) audit.record(entry("SKILL_UPDATE", saved, changes))
        return saved
    }

    override fun get(id: SkillId): Skill {
        val skill = load(id)
        gate.requireRead(skill.scope)
        return skill
    }

    override fun list(projectId: UUID?): List<Skill> {
        val access = gate.requireListing(projectId)
        return skills.findVisible(access.projectId)
            .filter { access.allows(it.scope) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.scope.projectId != null }))
    }

    override fun delete(id: SkillId) {
        val existing = load(id)
        gate.requireWrite(existing.scope)
        if (agents.existsUsingSkill(existing.key)) throw InvalidStateException("skill ${existing.key} is used by agents and cannot be deleted")
        skills.delete(id)
        audit.record(entry("SKILL_DELETE", existing, mapOf("key" to existing.key, "scope" to existing.scope.describe())))
    }

    private fun load(id: SkillId): Skill = skills.findById(id) ?: throw NotFoundException("Skill", id)

    private fun entry(operation: String, s: Skill, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "Skill", entityId = s.id.toString(), projectId = s.scope.projectId, details = details)

    private companion object {
        const val KEY_PREFIX = "skill"
    }
}
