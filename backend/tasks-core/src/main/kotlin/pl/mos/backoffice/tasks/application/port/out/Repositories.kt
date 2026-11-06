package pl.mos.backoffice.tasks.application.port.out

import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.RuleId
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import java.util.UUID

/** Driven port: templates, unique by `(key, scope)`. */
interface TaskTemplateRepository {
    fun save(template: TaskTemplate): TaskTemplate
    fun findById(id: TaskTemplateId): TaskTemplate?
    fun findByKeyAndScope(key: String, scope: Scope): TaskTemplate?
    /** Global templates plus the project's own ones (both when a key is shadowed). */
    fun findVisible(projectId: UUID?): List<TaskTemplate>
    /** Some template, in any scope, has this key (ADR 0055: generated keys are unique across scopes). */
    fun existsByKey(key: String): Boolean
    /** Some template, in any scope, has `extendsKey == key`. */
    fun existsExtending(key: String): Boolean
    /** Some template, in any scope, lists [skillKey] in `skills`. */
    fun existsUsingSkill(skillKey: String): Boolean
    /** Some template, in any scope, lists [ruleKey] in `rules`. */
    fun existsUsingRule(ruleKey: String): Boolean
    /**
     * ADR 0056: some template names [agentKey] as its agent where the agent is visible: with [projectId] the project's
     * templates and the global ones, without it every template.
     */
    fun existsUsingAgent(agentKey: String, projectId: UUID?): Boolean
    fun delete(id: TaskTemplateId)
}

/** Driven port: skills, unique by `(key, scope)`. */
interface SkillRepository {
    fun save(skill: Skill): Skill
    fun findById(id: SkillId): Skill?
    fun findByKeyAndScope(key: String, scope: Scope): Skill?
    /** Global skills plus the project's own ones. */
    fun findVisible(projectId: UUID?): List<Skill>
    fun existsByKey(key: String): Boolean
    fun delete(id: SkillId)
}

/** Driven port: rules (ADR 0055), unique by `(key, scope)`. */
interface RuleRepository {
    fun save(rule: Rule): Rule
    fun findById(id: RuleId): Rule?
    fun findByKeyAndScope(key: String, scope: Scope): Rule?
    /** Global rules plus the project's own ones. */
    fun findVisible(projectId: UUID?): List<Rule>
    /** Automatic rules stored in exactly [scope]. */
    fun findAutomatic(scope: Scope): List<Rule>
    fun existsByKey(key: String): Boolean
    fun delete(id: RuleId)
}

/** Driven port: agents (ADR 0056), unique by `(key, scope)`. */
interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: AgentId): Agent?
    fun findByKeyAndScope(key: String, scope: Scope): Agent?
    /** Global agents plus the project's own ones. */
    fun findVisible(projectId: UUID?): List<Agent>
    fun existsByKey(key: String): Boolean
    fun delete(id: AgentId)
}
