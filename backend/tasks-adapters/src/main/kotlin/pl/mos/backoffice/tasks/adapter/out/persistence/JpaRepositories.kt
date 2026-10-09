package pl.mos.backoffice.tasks.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TaskInterfaceJpaRepository : JpaRepository<TaskInterfaceEntity, UUID> {
    fun findByKey(key: String): TaskInterfaceEntity?
}

interface TaskTemplateJpaRepository : JpaRepository<TaskTemplateEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): TaskTemplateEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): TaskTemplateEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<TaskTemplateEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<TaskTemplateEntity>
    fun existsByExtendsKey(extendsKey: String): Boolean

    @Query(value = "select distinct t.key from task_template t where t.implements @> to_jsonb(cast(:key as text)) order by t.key", nativeQuery = true)
    fun findKeysImplementing(key: String): List<String>

    @Query(value = "select exists(select 1 from task_template t where t.skills @> to_jsonb(cast(:key as text)))", nativeQuery = true)
    fun existsUsingSkill(key: String): Boolean
}

interface SkillJpaRepository : JpaRepository<SkillEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): SkillEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): SkillEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<SkillEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<SkillEntity>
}
