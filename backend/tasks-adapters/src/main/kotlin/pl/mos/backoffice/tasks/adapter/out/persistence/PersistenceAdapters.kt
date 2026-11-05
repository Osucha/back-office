package pl.mos.backoffice.tasks.adapter.out.persistence

import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.RuleId
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import java.util.UUID

/** `TaskTemplateRepository` on JPA; the `skills` / `rules` checks use JSONB containment. */
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

    override fun existsByKey(key: String): Boolean = jpa.existsByKey(key)
    override fun existsExtending(key: String): Boolean = jpa.existsByExtendsKey(key)
    override fun existsUsingSkill(skillKey: String): Boolean = jpa.existsUsingSkill(skillKey)
    override fun existsUsingRule(ruleKey: String): Boolean = jpa.existsUsingRule(ruleKey)
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

    override fun existsByKey(key: String): Boolean = jpa.existsByKey(key)
    override fun delete(id: SkillId) = jpa.deleteById(id.value)
}

/** `RuleRepository` on JPA (ADR 0055). */
@Repository
@Transactional
class RulePersistenceAdapter(
    private val jpa: RuleJpaRepository,
    private val mappers: TaskMappers,
) : RuleRepository {
    override fun save(rule: Rule): Rule = mappers.toDomain(jpa.save(mappers.toEntity(rule)))
    override fun findById(id: RuleId): Rule? = jpa.findById(id.value).map(mappers::toDomain).orElse(null)

    override fun findByKeyAndScope(key: String, scope: Scope): Rule? = when (scope) {
        Scope.Global -> jpa.findByKeyAndScopeTypeAndProjectIdIsNull(key, ScopeType.GLOBAL)
        is Scope.Project -> jpa.findByKeyAndScopeTypeAndProjectId(key, ScopeType.PROJECT, scope.projectId)
    }?.let(mappers::toDomain)

    override fun findVisible(projectId: UUID?): List<Rule> =
        (if (projectId == null) jpa.findAllByScopeType(ScopeType.GLOBAL) else jpa.findAllByScopeTypeOrProjectId(ScopeType.GLOBAL, projectId))
            .map(mappers::toDomain)

    override fun findAutomatic(scope: Scope): List<Rule> = when (scope) {
        Scope.Global -> jpa.findAllByAutomaticTrueAndScopeType(ScopeType.GLOBAL)
        is Scope.Project -> jpa.findAllByAutomaticTrueAndScopeTypeAndProjectId(ScopeType.PROJECT, scope.projectId)
    }.map(mappers::toDomain)

    override fun existsByKey(key: String): Boolean = jpa.existsByKey(key)
    override fun delete(id: RuleId) = jpa.deleteById(id.value)
}
