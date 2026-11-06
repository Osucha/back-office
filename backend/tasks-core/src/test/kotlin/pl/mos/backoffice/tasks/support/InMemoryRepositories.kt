package pl.mos.backoffice.tasks.support

import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
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

class InMemoryTaskTemplateRepository : TaskTemplateRepository {
    val rows = linkedMapOf<TaskTemplateId, TaskTemplate>()
    override fun save(template: TaskTemplate): TaskTemplate = template.also { rows[it.id] = it }
    override fun findById(id: TaskTemplateId): TaskTemplate? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): TaskTemplate? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<TaskTemplate> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun existsByKey(key: String): Boolean = rows.values.any { it.key == key }
    override fun existsExtending(key: String): Boolean = rows.values.any { it.extendsKey == key }
    override fun existsUsingAgent(agentKey: String, projectId: UUID?): Boolean =
        rows.values.any { it.agentKey == agentKey && (projectId == null || it.scope.projectId == null || it.scope.projectId == projectId) }
    override fun delete(id: TaskTemplateId) { rows.remove(id) }
}

class InMemorySkillRepository : SkillRepository {
    val rows = linkedMapOf<SkillId, Skill>()
    override fun save(skill: Skill): Skill = skill.also { rows[it.id] = it }
    override fun findById(id: SkillId): Skill? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): Skill? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<Skill> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun existsByKey(key: String): Boolean = rows.values.any { it.key == key }
    override fun delete(id: SkillId) { rows.remove(id) }
}

class InMemoryRuleRepository : RuleRepository {
    val rows = linkedMapOf<RuleId, Rule>()
    override fun save(rule: Rule): Rule = rule.also { rows[it.id] = it }
    override fun findById(id: RuleId): Rule? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): Rule? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<Rule> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun findAutomatic(scope: Scope): List<Rule> = rows.values.filter { it.automatic && it.scope == scope }
    override fun existsByKey(key: String): Boolean = rows.values.any { it.key == key }
    override fun delete(id: RuleId) { rows.remove(id) }
}

class InMemoryAgentRepository : AgentRepository {
    val rows = linkedMapOf<AgentId, Agent>()
    override fun save(agent: Agent): Agent = agent.also { rows[it.id] = it }
    override fun findById(id: AgentId): Agent? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): Agent? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<Agent> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun existsByKey(key: String): Boolean = rows.values.any { it.key == key }
    override fun existsUsingSkill(skillKey: String): Boolean = rows.values.any { skillKey in it.skills }
    override fun existsUsingRule(ruleKey: String): Boolean = rows.values.any { ruleKey in it.rules }
    override fun delete(id: AgentId) { rows.remove(id) }
}
