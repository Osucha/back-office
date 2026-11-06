package pl.mos.backoffice.tasks.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TaskTemplateJpaRepository : JpaRepository<TaskTemplateEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): TaskTemplateEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): TaskTemplateEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<TaskTemplateEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<TaskTemplateEntity>
    fun existsByExtendsKey(extendsKey: String): Boolean
    fun existsByKey(key: String): Boolean

    @Query(value = "select exists(select 1 from task_template t where t.rules @> to_jsonb(cast(:key as text)))", nativeQuery = true)
    fun existsUsingRule(key: String): Boolean

    @Query(value = "select exists(select 1 from task_template t where t.skills @> to_jsonb(cast(:key as text)))", nativeQuery = true)
    fun existsUsingSkill(key: String): Boolean

    fun existsByAgentKey(agentKey: String): Boolean

    @Query(value = "select exists(select 1 from task_template t where t.agent_key = :key and (t.project_id is null or t.project_id = :projectId))", nativeQuery = true)
    fun existsUsingAgentVisibleTo(key: String, projectId: UUID): Boolean
}

interface AgentJpaRepository : JpaRepository<AgentEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): AgentEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): AgentEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<AgentEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<AgentEntity>
    fun existsByKey(key: String): Boolean
}

interface SkillJpaRepository : JpaRepository<SkillEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): SkillEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): SkillEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<SkillEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<SkillEntity>
    fun existsByKey(key: String): Boolean
}

interface RuleJpaRepository : JpaRepository<RuleEntity, UUID> {
    fun findByKeyAndScopeTypeAndProjectId(key: String, scopeType: ScopeType, projectId: UUID?): RuleEntity?
    fun findByKeyAndScopeTypeAndProjectIdIsNull(key: String, scopeType: ScopeType): RuleEntity?
    fun findAllByScopeType(scopeType: ScopeType): List<RuleEntity>
    fun findAllByScopeTypeOrProjectId(scopeType: ScopeType, projectId: UUID): List<RuleEntity>
    fun findAllByAutomaticTrueAndScopeType(scopeType: ScopeType): List<RuleEntity>
    fun findAllByAutomaticTrueAndScopeTypeAndProjectId(scopeType: ScopeType, projectId: UUID): List<RuleEntity>
    fun existsByKey(key: String): Boolean
}
