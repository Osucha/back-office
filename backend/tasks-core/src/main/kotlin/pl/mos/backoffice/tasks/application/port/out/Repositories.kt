package pl.mos.backoffice.tasks.application.port.out

import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskInterfaceId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import java.util.UUID

/** Driven port: interfaces, unique by key. */
interface TaskInterfaceRepository {
    fun save(definition: TaskInterface): TaskInterface
    fun findById(id: TaskInterfaceId): TaskInterface?
    fun findByKey(key: String): TaskInterface?
    fun findAll(): List<TaskInterface>
    fun delete(id: TaskInterfaceId)
}

/** Driven port: templates, unique by `(key, scope)`. */
interface TaskTemplateRepository {
    fun save(template: TaskTemplate): TaskTemplate
    fun findById(id: TaskTemplateId): TaskTemplate?
    fun findByKeyAndScope(key: String, scope: Scope): TaskTemplate?
    /** Global templates plus the project's own ones (both when a key is shadowed). */
    fun findVisible(projectId: UUID?): List<TaskTemplate>
    /** Some template, in any scope, has `extendsKey == key`. */
    fun existsExtending(key: String): Boolean
    /** Keys (distinct, sorted) of templates, in any scope, listing [interfaceKey] in `implements`. */
    fun findKeysImplementing(interfaceKey: String): List<String>
    fun existsImplementing(interfaceKey: String): Boolean = findKeysImplementing(interfaceKey).isNotEmpty()
    /** Some template, in any scope, lists [skillKey] in `skills`. */
    fun existsUsingSkill(skillKey: String): Boolean
    fun delete(id: TaskTemplateId)
}

/** Driven port: skills, unique by `(key, scope)`. */
interface SkillRepository {
    fun save(skill: Skill): Skill
    fun findById(id: SkillId): Skill?
    fun findByKeyAndScope(key: String, scope: Scope): Skill?
    /** Global skills plus the project's own ones. */
    fun findVisible(projectId: UUID?): List<Skill>
    fun delete(id: SkillId)
}
