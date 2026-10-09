package pl.mos.backoffice.tasks.adapter.out.persistence

import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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

/** `TaskInterfaceRepository` on JPA. */
@Repository
@Transactional
class TaskInterfacePersistenceAdapter(
    private val jpa: TaskInterfaceJpaRepository,
    private val mappers: TaskMappers,
) : TaskInterfaceRepository {
    override fun save(definition: TaskInterface): TaskInterface = mappers.toDomain(jpa.save(mappers.toEntity(definition)))
    override fun findById(id: TaskInterfaceId): TaskInterface? = jpa.findById(id.value).map(mappers::toDomain).orElse(null)
    override fun findByKey(key: String): TaskInterface? = jpa.findByKey(key)?.let(mappers::toDomain)
    override fun findAll(): List<TaskInterface> = jpa.findAll().map(mappers::toDomain)
    override fun delete(id: TaskInterfaceId) = jpa.deleteById(id.value)
}

/** `TaskTemplateRepository` on JPA; the `implements` / `skills` checks use JSONB containment. */
@Repository
@Transactional
class TaskTemplatePersistenceAdapter(
    private val jpa: TaskTemplateJpaRepository,
    private val mappers: TaskMappers,
) : TaskTemplateRepository {
    override fun save(template: TaskTemplate): TaskTemplate = mappers.toDomain(jpa.save(mappers.toEntity(template)))
    override fun findById(id: TaskTemplateId): TaskTemplate? = jpa.findById(id.value).map(mappers::toDomain).orElse(null)

    override fun findByKeyAndScope(key: String, scope: Scope): TaskTemplate? = when (scope) {
        Scope.Global -> jpa.findByKeyAndScopeTypeAndProjectIdIsNull(key, ScopeType.GLOBAL)
        is Scope.Project -> jpa.findByKeyAndScopeTypeAndProjectId(key, ScopeType.PROJECT, scope.projectId)
    }?.let(mappers::toDomain)

    override fun findVisible(projectId: UUID?): List<TaskTemplate> =
        (if (projectId == null) jpa.findAllByScopeType(ScopeType.GLOBAL) else jpa.findAllByScopeTypeOrProjectId(ScopeType.GLOBAL, projectId))
            .map(mappers::toDomain)

    override fun existsExtending(key: String): Boolean = jpa.existsByExtendsKey(key)
    override fun findKeysImplementing(interfaceKey: String): List<String> = jpa.findKeysImplementing(interfaceKey)
    override fun existsUsingSkill(skillKey: String): Boolean = jpa.existsUsingSkill(skillKey)
    override fun delete(id: TaskTemplateId) = jpa.deleteById(id.value)
}

/** `SkillRepository` on JPA. */
@Repository
@Transactional
class SkillPersistenceAdapter(
    private val jpa: SkillJpaRepository,
    private val mappers: TaskMappers,
) : SkillRepository {
    override fun save(skill: Skill): Skill = mappers.toDomain(jpa.save(mappers.toEntity(skill)))
    override fun findById(id: SkillId): Skill? = jpa.findById(id.value).map(mappers::toDomain).orElse(null)

    override fun findByKeyAndScope(key: String, scope: Scope): Skill? = when (scope) {
        Scope.Global -> jpa.findByKeyAndScopeTypeAndProjectIdIsNull(key, ScopeType.GLOBAL)
        is Scope.Project -> jpa.findByKeyAndScopeTypeAndProjectId(key, ScopeType.PROJECT, scope.projectId)
    }?.let(mappers::toDomain)

    override fun findVisible(projectId: UUID?): List<Skill> =
        (if (projectId == null) jpa.findAllByScopeType(ScopeType.GLOBAL) else jpa.findAllByScopeTypeOrProjectId(ScopeType.GLOBAL, projectId))
            .map(mappers::toDomain)

    override fun delete(id: SkillId) = jpa.deleteById(id.value)
}
