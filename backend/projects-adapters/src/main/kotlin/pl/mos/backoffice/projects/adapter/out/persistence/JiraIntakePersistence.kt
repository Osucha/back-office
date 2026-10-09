package pl.mos.backoffice.projects.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.JiraIntakeRepository
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** `jira_intake` (V108, ADR 0033): one watcher per project. */
@Entity
@Table(name = "jira_intake")
class JiraIntakeEntity(
    @Id @Column(name = "project_id") var projectId: UUID,
    @Column(nullable = false) var enabled: Boolean = false,
    @Column(name = "poll_interval_minutes", nullable = false) var pollIntervalMinutes: Int = JiraIntake.DEFAULT_POLL_MINUTES,
    @Column(name = "workflow_id") var workflowId: UUID? = null,
    @Column(name = "cursor_at") var cursorAt: Instant? = null,
    @Column(name = "last_poll_at") var lastPollAt: Instant? = null,
    @Column(name = "last_success_at") var lastSuccessAt: Instant? = null,
    @Column(name = "last_error") var lastError: String? = null,
    @Column(name = "last_error_at") var lastErrorAt: Instant? = null,
    @Column(name = "consecutive_failures", nullable = false) var consecutiveFailures: Int = 0,
    @Column(name = "next_poll_at") var nextPollAt: Instant? = null,
    @Column(name = "issues_started", nullable = false) var issuesStarted: Long = 0,
    @Column(name = "last_connection") var lastConnection: String? = null,
    @Column(name = "last_warning") var lastWarning: String? = null,
    @Column(name = "updated_at") var updatedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
)

interface JiraIntakeJpaRepository : JpaRepository<JiraIntakeEntity, UUID> {
    @Query("select i from JiraIntakeEntity i where i.enabled = true and (i.nextPollAt is null or i.nextPollAt <= :now)")
    fun findDue(@Param("now") now: Instant): List<JiraIntakeEntity>

    @Modifying
    @Query(
        value = "INSERT INTO jira_intake_claim (project_id, dedupe_key, issue_key, claimed_at) VALUES (:projectId, :dedupeKey, :issueKey, :at) " +
            "ON CONFLICT (project_id, dedupe_key) DO NOTHING",
        nativeQuery = true,
    )
    fun claim(@Param("projectId") projectId: UUID, @Param("dedupeKey") dedupeKey: String, @Param("issueKey") issueKey: String, @Param("at") at: Instant): Int

    @Modifying
    @Query(value = "UPDATE jira_intake_claim SET run_id = :runId WHERE project_id = :projectId AND dedupe_key = :dedupeKey", nativeQuery = true)
    fun attachRun(@Param("projectId") projectId: UUID, @Param("dedupeKey") dedupeKey: String, @Param("runId") runId: UUID): Int

    @Modifying
    @Query(value = "DELETE FROM jira_intake_claim WHERE project_id = :projectId AND dedupe_key = :dedupeKey", nativeQuery = true)
    fun release(@Param("projectId") projectId: UUID, @Param("dedupeKey") dedupeKey: String): Int

    @Modifying
    @Query(value = "DELETE FROM jira_intake_claim WHERE claimed_at < :before", nativeQuery = true)
    fun purge(@Param("before") before: Instant): Int
}

/** [JiraIntakeRepository] on `jira_intake` / `jira_intake_claim`; claims are an `INSERT ... ON CONFLICT DO NOTHING`. */
@Component
@Transactional
class JiraIntakePersistenceAdapter(
    private val repo: JiraIntakeJpaRepository,
    private val json: JsonMapper,
    private val mapper: PersistenceMapper,
) : JiraIntakeRepository {

    @Transactional(readOnly = true)
    override fun find(projectId: ProjectId): JiraIntake? = repo.findById(projectId.value).orElse(null)?.let(::toDomain)

    override fun save(intake: JiraIntake): JiraIntake = toDomain(repo.save(toEntity(intake)))

    @Transactional(readOnly = true)
    override fun findDue(now: Instant): List<JiraIntake> = repo.findDue(now).map(::toDomain)

    override fun claim(projectId: ProjectId, dedupeKey: String, issueKey: String, at: Instant): Boolean =
        repo.claim(projectId.value, dedupeKey, issueKey, at) == 1

    override fun attachRun(projectId: ProjectId, dedupeKey: String, runId: WorkflowRunId) {
        repo.attachRun(projectId.value, dedupeKey, runId.value)
    }

    override fun release(projectId: ProjectId, dedupeKey: String) {
        repo.release(projectId.value, dedupeKey)
    }

    override fun purgeClaims(before: Instant): Int = repo.purge(before)

    private fun toEntity(i: JiraIntake) = JiraIntakeEntity(
        projectId = i.projectId.value, enabled = i.enabled, pollIntervalMinutes = i.pollIntervalMinutes, workflowId = i.workflowId?.value,
        cursorAt = i.cursor, lastPollAt = i.lastPollAt, lastSuccessAt = i.lastSuccessAt, lastError = i.lastError, lastErrorAt = i.lastErrorAt,
        consecutiveFailures = i.consecutiveFailures, nextPollAt = i.nextPollAt, issuesStarted = i.issuesStarted,
        lastConnection = i.lastConnection, lastWarning = i.lastWarning, updatedAt = i.updatedAt,
        updatedBy = i.updatedBy?.let { json.writeValueAsString(mapper.toDoc(it)) },
    )

    private fun toDomain(e: JiraIntakeEntity) = JiraIntake(
        projectId = ProjectId(e.projectId), enabled = e.enabled, pollIntervalMinutes = e.pollIntervalMinutes,
        workflowId = e.workflowId?.let(::WorkflowDefinitionId), cursor = e.cursorAt, lastPollAt = e.lastPollAt, lastSuccessAt = e.lastSuccessAt,
        lastError = e.lastError, lastErrorAt = e.lastErrorAt, consecutiveFailures = e.consecutiveFailures, nextPollAt = e.nextPollAt,
        issuesStarted = e.issuesStarted, lastConnection = e.lastConnection, lastWarning = e.lastWarning, updatedAt = e.updatedAt,
        updatedBy = e.updatedBy?.let { mapper.toDomain(json.readValue(it, ActorDoc::class.java)) },
    )
}
