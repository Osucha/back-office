package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.tasks.application.port.`in`.ManageRules
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.domain.model.Keys
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.RuleId
import java.util.UUID

/**
 * Rule CRUD (ADR 0055). Same access rules as skills (global: `library.view` / `library.manage`; project: members
 * read, `library.project.manage` writes); a rule an agent lists (ADR 0057) keeps its key and cannot be deleted. Changes are
 * audited as `RULE_CREATE`, `RULE_UPDATE`, `RULE_DELETE`.
 */
class RuleService(
    private val rules: RuleRepository,
    private val agents: AgentRepository,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageRules {

    override fun create(command: Rule.Content): Rule {
        val user = gate.requireWrite(command.scope)
        val key = command.key ?: Keys.unique(Keys.fromName(command.name, KEY_PREFIX)) { rules.existsByKey(it) }
        if (rules.findByKeyAndScope(key, command.scope) != null) {
            throw InvalidStateException("rule $key already exists in scope ${command.scope.describe()}")
        }
        val saved = rules.save(Rule.create(command.copy(key = key), clock.now(), actor = user.actor))
        audit.record(entry("RULE_CREATE", saved, mapOf(
            "key" to saved.key, "scope" to saved.scope.describe(), "automatic" to saved.automatic, "contentLength" to saved.content.length,
        )))
        return saved
    }

    override fun update(id: RuleId, command: Rule.Content): Rule {
        val user = gate.requireWrite(command.scope)
        val existing = load(id)
        if (existing.scope != command.scope) gate.requireWrite(existing.scope)
        val key = command.key ?: existing.key
        rules.findByKeyAndScope(key, command.scope)?.let { clash ->
            if (clash.id != id) throw InvalidStateException("rule $key already exists in scope ${command.scope.describe()}")
        }
        if ((key != existing.key || command.scope != existing.scope) && agents.existsUsingRule(existing.key)) {
            throw InvalidStateException("rule ${existing.key} is used by agents; its key and scope cannot change")
        }
        val saved = rules.save(existing.update(command.copy(key = key), clock.now(), user.actor))
        val changes = AuditableChanges.changed(
            "key" to (existing.key to saved.key),
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "scope" to (existing.scope.describe() to saved.scope.describe()),
            "automatic" to (existing.automatic to saved.automatic),
            "contentLength" to (existing.content.length to saved.content.length),
            "contentChanged" to (false to (existing.content != saved.content)),
            "tags" to (existing.tags to saved.tags),
        )
        if (changes.isNotEmpty()) audit.record(entry("RULE_UPDATE", saved, changes))
        return saved
    }

    override fun get(id: RuleId): Rule {
        val rule = load(id)
        gate.requireRead(rule.scope)
        return rule
    }

    override fun list(projectId: UUID?): List<Rule> {
        val access = gate.requireListing(projectId)
        return rules.findVisible(access.projectId)
            .filter { access.allows(it.scope) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.scope.projectId != null }))
    }

    override fun delete(id: RuleId) {
        val existing = load(id)
        gate.requireWrite(existing.scope)
        if (agents.existsUsingRule(existing.key)) throw InvalidStateException("rule ${existing.key} is used by agents and cannot be deleted")
        rules.delete(id)
        audit.record(entry("RULE_DELETE", existing, mapOf("key" to existing.key, "scope" to existing.scope.describe())))
    }

    private fun load(id: RuleId): Rule = rules.findById(id) ?: throw NotFoundException("Rule", id)

    private fun entry(operation: String, r: Rule, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "Rule", entityId = r.id.toString(), projectId = r.scope.projectId, details = details)

    private companion object {
        const val KEY_PREFIX = "rule"
    }
}
