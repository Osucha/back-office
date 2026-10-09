package pl.mos.backoffice.projects.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.PersonalIntakeRepository
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import java.time.Instant
import java.util.UUID

/** `personal_intake_rule` (V112, ADR 0038). */
@Entity
@Table(name = "personal_intake_rule")
class PersonalIntakeRuleEntity(
    @Id var id: UUID,
    @Column(name = "owner_user_id", nullable = false) var ownerUserId: UUID,
    @Column(name = "owner_name", nullable = false) var ownerName: String,
    @Column(nullable = false) var name: String,
    @Column(name = "connection_id", nullable = false) var connectionId: UUID,
    @Column(nullable = false) var jql: String,
    @Column(name = "poll_interval_minutes", nullable = false) var pollIntervalMinutes: Int,
    @Column(name = "workflow_id", nullable = false) var workflowId: UUID,
    @Column(nullable = false) var enabled: Boolean,
    @Column(name = "include_existing", nullable = false) var includeExisting: Boolean,
    @Column(nullable = false) var initialized: Boolean,
    @Column(name = "next_poll_at") var nextPollAt: Instant? = null,
    @Column(name = "last_poll_at") var lastPollAt: Instant? = null,
    @Column(name = "last_success_at") var lastSuccessAt: Instant? = null,
    @Column(name = "last_error") var lastError: String? = null,
    @Column(name = "consecutive_failures", nullable = false) var consecutiveFailures: Int = 0,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

/** `intake_proposal` (V112, ADR 0038); unique per rule and issue key. */
@Entity
@Table(name = "intake_proposal")
class IntakeProposalEntity(
    @Id var id: UUID,
    @Column(name = "rule_id", nullable = false) var ruleId: UUID,
    @Column(name = "owner_user_id", nullable = false) var ownerUserId: UUID,
    @Column(name = "issue_key", nullable = false) var issueKey: String,
    var summary: String? = null,
    @Column(name = "issue_type") var issueType: String? = null,
    var status: String? = null,
    var priority: String? = null,
    @Column(nullable = false) var state: String,
    @Column(name = "detected_at", nullable = false) var detectedAt: Instant,
    @Column(name = "decided_at") var decidedAt: Instant? = null,
    @Column(name = "workflow_id") var workflowId: UUID? = null,
    @Column(name = "run_id") var runId: UUID? = null,
)

interface PersonalIntakeRuleJpaRepository : JpaRepository<PersonalIntakeRuleEntity, UUID> {
    fun findByOwnerUserId(ownerUserId: UUID): List<PersonalIntakeRuleEntity>

    @Query("select r from PersonalIntakeRuleEntity r where r.enabled = true and (r.nextPollAt is null or r.nextPollAt <= :now)")
    fun findDue(@Param("now") now: Instant): List<PersonalIntakeRuleEntity>
}

interface IntakeProposalJpaRepository : JpaRepository<IntakeProposalEntity, UUID> {
    @Query("select p.issueKey from IntakeProposalEntity p where p.ruleId = :ruleId")
    fun issueKeys(@Param("ruleId") ruleId: UUID): List<String>

    fun findByOwnerUserIdAndStateOrderByDetectedAtDesc(ownerUserId: UUID, state: String, page: PageRequest): List<IntakeProposalEntity>

    @Query("select p.ruleId, count(p) from IntakeProposalEntity p where p.ownerUserId = :owner and p.state = 'NEW' group by p.ruleId")
    fun newCounts(@Param("owner") owner: UUID): List<Array<Any>>

    @Modifying
    @Query(
        value = "INSERT INTO intake_proposal (id, rule_id, owner_user_id, issue_key, summary, issue_type, status, priority, state, detected_at) " +
            "VALUES (:id, :ruleId, :owner, :issueKey, :summary, :issueType, :status, :priority, :state, :detectedAt) ON CONFLICT (rule_id, issue_key) DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID, @Param("ruleId") ruleId: UUID, @Param("owner") owner: UUID, @Param("issueKey") issueKey: String,
        @Param("summary") summary: String?, @Param("issueType") issueType: String?, @Param("status") status: String?,
        @Param("priority") priority: String?, @Param("state") state: String, @Param("detectedAt") detectedAt: Instant,
    ): Int

    @Modifying
    @Query("delete from IntakeProposalEntity p where p.ruleId = :ruleId")
    fun deleteByRule(@Param("ruleId") ruleId: UUID): Int
}

@Component
@Transactional
class PersonalIntakePersistenceAdapter(
    private val rules: PersonalIntakeRuleJpaRepository,
    private val proposals: IntakeProposalJpaRepository,
) : PersonalIntakeRepository {

    override fun saveRule(rule: PersonalIntakeRule): PersonalIntakeRule = toDomain(rules.save(toEntity(rule)))

    @Transactional(readOnly = true)
    override fun findRule(id: IntakeRuleId): PersonalIntakeRule? = rules.findById(id.value).orElse(null)?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun rulesOf(ownerUserId: UUID): List<PersonalIntakeRule> = rules.findByOwnerUserId(ownerUserId).map(::toDomain)

    override fun deleteRule(id: IntakeRuleId) {
        proposals.deleteByRule(id.value)
        rules.deleteById(id.value)
    }

    @Transactional(readOnly = true)
    override fun dueRules(now: Instant): List<PersonalIntakeRule> = rules.findDue(now).map(::toDomain)

    @Transactional(readOnly = true)
    override fun knownKeys(ruleId: IntakeRuleId): Set<String> = proposals.issueKeys(ruleId.value).toSet()

    override fun insertProposalIfAbsent(proposal: IntakeProposal): Boolean = proposals.insertIfAbsent(
        proposal.id.value, proposal.ruleId.value, proposal.ownerUserId, proposal.issueKey, proposal.summary, proposal.issueType,
        proposal.status, proposal.priority, proposal.state.name, proposal.detectedAt,
    ) == 1

    @Transactional(readOnly = true)
    override fun findProposal(id: IntakeProposalId): IntakeProposal? = proposals.findById(id.value).orElse(null)?.let(::toDomain)

    override fun saveProposal(proposal: IntakeProposal): IntakeProposal = toDomain(proposals.save(toEntity(proposal)))

    @Transactional(readOnly = true)
    override fun proposalsOf(ownerUserId: UUID, state: ProposalState, limit: Int): List<IntakeProposal> =
        proposals.findByOwnerUserIdAndStateOrderByDetectedAtDesc(ownerUserId, state.name, PageRequest.of(0, limit)).map(::toDomain)

    @Transactional(readOnly = true)
    override fun newCounts(ownerUserId: UUID): Map<IntakeRuleId, Int> =
        proposals.newCounts(ownerUserId).associate { IntakeRuleId(it[0] as UUID) to (it[1] as Number).toInt() }

    private fun toEntity(r: PersonalIntakeRule) = PersonalIntakeRuleEntity(
        id = r.id.value, ownerUserId = r.ownerUserId, ownerName = r.ownerName, name = r.name, connectionId = r.connectionId.value,
        jql = r.jql, pollIntervalMinutes = r.pollIntervalMinutes, workflowId = r.workflowId.value, enabled = r.enabled,
        includeExisting = r.includeExisting, initialized = r.initialized, nextPollAt = r.nextPollAt, lastPollAt = r.lastPollAt,
        lastSuccessAt = r.lastSuccessAt, lastError = r.lastError, consecutiveFailures = r.consecutiveFailures, createdAt = r.createdAt,
        updatedAt = r.updatedAt,
    )

    private fun toDomain(e: PersonalIntakeRuleEntity) = PersonalIntakeRule(
        id = IntakeRuleId(e.id), ownerUserId = e.ownerUserId, ownerName = e.ownerName, name = e.name, connectionId = ConnectionId(e.connectionId),
        jql = e.jql, pollIntervalMinutes = e.pollIntervalMinutes, workflowId = WorkflowDefinitionId(e.workflowId), enabled = e.enabled,
        includeExisting = e.includeExisting, initialized = e.initialized, nextPollAt = e.nextPollAt, lastPollAt = e.lastPollAt,
        lastSuccessAt = e.lastSuccessAt, lastError = e.lastError, consecutiveFailures = e.consecutiveFailures, createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    private fun toEntity(p: IntakeProposal) = IntakeProposalEntity(
        id = p.id.value, ruleId = p.ruleId.value, ownerUserId = p.ownerUserId, issueKey = p.issueKey, summary = p.summary,
        issueType = p.issueType, status = p.status, priority = p.priority, state = p.state.name, detectedAt = p.detectedAt,
        decidedAt = p.decidedAt, workflowId = p.workflowId?.value, runId = p.runId?.value,
    )

    private fun toDomain(e: IntakeProposalEntity) = IntakeProposal(
        id = IntakeProposalId(e.id), ruleId = IntakeRuleId(e.ruleId), ownerUserId = e.ownerUserId, issueKey = e.issueKey, summary = e.summary,
        issueType = e.issueType, status = e.status, priority = e.priority, state = ProposalState.valueOf(e.state), detectedAt = e.detectedAt,
        decidedAt = e.decidedAt, workflowId = e.workflowId?.let(::WorkflowDefinitionId), runId = e.runId?.let(::WorkflowRunId),
    )
}
