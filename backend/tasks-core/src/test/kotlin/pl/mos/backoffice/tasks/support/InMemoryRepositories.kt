package pl.mos.backoffice.tasks.support

import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskInterfaceId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import java.util.UUID

class InMemoryTaskInterfaceRepository : TaskInterfaceRepository {
    val rows = linkedMapOf<TaskInterfaceId, TaskInterface>()
    override fun save(definition: TaskInterface): TaskInterface = definition.also { rows[it.id] = it }
    override fun findById(id: TaskInterfaceId): TaskInterface? = rows[id]
    override fun findByKey(key: String): TaskInterface? = rows.values.firstOrNull { it.key == key }
    override fun findAll(): List<TaskInterface> = rows.values.toList()
    override fun delete(id: TaskInterfaceId) { rows.remove(id) }
}

class InMemoryTaskTemplateRepository : TaskTemplateRepository {
    val rows = linkedMapOf<TaskTemplateId, TaskTemplate>()
    override fun save(template: TaskTemplate): TaskTemplate = template.also { rows[it.id] = it }
    override fun findById(id: TaskTemplateId): TaskTemplate? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): TaskTemplate? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<TaskTemplate> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun existsExtending(key: String): Boolean = rows.values.any { it.extendsKey == key }
    override fun findKeysImplementing(interfaceKey: String): List<String> = rows.values.filter { interfaceKey in it.implements }.map { it.key }.distinct().sorted()
    override fun existsUsingSkill(skillKey: String): Boolean = rows.values.any { skillKey in it.skills }
    override fun delete(id: TaskTemplateId) { rows.remove(id) }
}

class InMemorySkillRepository : SkillRepository {
    val rows = linkedMapOf<SkillId, Skill>()
    override fun save(skill: Skill): Skill = skill.also { rows[it.id] = it }
    override fun findById(id: SkillId): Skill? = rows[id]
    override fun findByKeyAndScope(key: String, scope: Scope): Skill? = rows.values.firstOrNull { it.key == key && it.scope == scope }
    override fun findVisible(projectId: UUID?): List<Skill> =
        rows.values.filter { it.scope == Scope.Global || (projectId != null && it.scope == Scope.Project(projectId)) }
    override fun delete(id: SkillId) { rows.remove(id) }
}
