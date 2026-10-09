package pl.mos.backoffice.projects.adapter.out.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ProjectJpaRepository : JpaRepository<ProjectEntity, UUID> {
    fun findByKey(key: String): ProjectEntity?
}

interface ProjectCiTokenJpaRepository : JpaRepository<ProjectCiTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): ProjectCiTokenEntity?
    fun findByProjectIdOrderByCreatedAtDesc(projectId: UUID): List<ProjectCiTokenEntity>
    fun deleteByProjectId(projectId: UUID)
}

interface WorkflowDefinitionJpaRepository : JpaRepository<WorkflowDefinitionEntity, UUID> {
    fun findByProjectId(projectId: UUID): List<WorkflowDefinitionEntity>
    fun countByProjectId(projectId: UUID): Long
    fun deleteByProjectId(projectId: UUID)

    /** Linked copies (ADR 0037) without their JSON graphs. */
    @Query("$LINKED_ROW where w.libraryWorkflowId is not null")
    fun findLinked(): List<LinkedWorkflowRow>

    @Query("$LINKED_ROW where w.libraryWorkflowId = :libraryWorkflowId")
    fun findLinked(libraryWorkflowId: UUID): List<LinkedWorkflowRow>
}

private const val LINKED_ROW =
    "select new pl.mos.backoffice.projects.adapter.out.persistence.LinkedWorkflowRow(w.id, w.projectId, w.name, w.enabled, w.libraryWorkflowId, " +
        "w.libraryKey, w.libraryVersion, w.libraryModified) from WorkflowDefinitionEntity w"

/** Projection of a linked workflow definition row. */
data class LinkedWorkflowRow(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val enabled: Boolean,
    val libraryWorkflowId: UUID,
    val libraryKey: String?,
    val libraryVersion: Int?,
    val libraryModified: Boolean,
)

interface LibraryWorkflowJpaRepository : JpaRepository<LibraryWorkflowEntity, UUID> {
    fun findByKey(key: String): LibraryWorkflowEntity?
}

interface LibraryWorkflowVersionJpaRepository : JpaRepository<LibraryWorkflowVersionEntity, LibraryWorkflowVersionKey> {
    fun findByLibraryWorkflowId(libraryWorkflowId: UUID): List<LibraryWorkflowVersionEntity>
    fun findByLibraryWorkflowIdIn(libraryWorkflowIds: Collection<UUID>): List<LibraryWorkflowVersionEntity>
    fun deleteByLibraryWorkflowId(libraryWorkflowId: UUID)

    /** `[libraryWorkflowId, max(version)]` per entry. */
    @Query("select v.libraryWorkflowId, max(v.version) from LibraryWorkflowVersionEntity v where v.libraryWorkflowId in :ids group by v.libraryWorkflowId")
    fun latestVersions(ids: Collection<UUID>): List<Array<Any>>
}

interface WorkflowRunJpaRepository : JpaRepository<WorkflowRunEntity, UUID>, JpaSpecificationExecutor<WorkflowRunEntity> {
    fun findByProjectId(projectId: UUID, pageable: Pageable): Page<WorkflowRunEntity>
    fun findByWorkflowId(workflowId: UUID, pageable: Pageable): Page<WorkflowRunEntity>
    fun findByStatusIn(statuses: Collection<String>, pageable: Pageable): Page<WorkflowRunEntity>
    fun findByProjectIdAndStatusIn(projectId: UUID, statuses: Collection<String>, pageable: Pageable): Page<WorkflowRunEntity>
    fun findByWorkflowIdAndStatusIn(workflowId: UUID, statuses: Collection<String>, pageable: Pageable): Page<WorkflowRunEntity>
    fun findByStatusIn(statuses: Collection<String>): List<WorkflowRunEntity>
    fun findByStatusOrderByCreatedAtDesc(status: String): List<WorkflowRunEntity>
    fun findByProjectIdAndStatusOrderByCreatedAtDesc(projectId: UUID, status: String): List<WorkflowRunEntity>
    fun countByStatusIn(statuses: Collection<String>): Long
    fun countByStatusAndWaitingKind(status: String, waitingKind: String): Long
    fun countByProjectIdAndStatusIn(projectId: UUID, statuses: Collection<String>): Long
    fun countByStatusAndFinishedAtAfter(status: String, since: Instant): Long
    fun existsByWorkflowIdAndStatusIn(workflowId: UUID, statuses: Collection<String>): Boolean

    @Query("select r.id from WorkflowRunEntity r where r.projectId = :projectId")
    fun idsByProjectId(projectId: UUID): List<UUID>

    @Modifying
    @Query("delete from WorkflowRunEntity r where r.projectId = :projectId")
    fun deleteByProjectId(projectId: UUID)
}

interface ArtifactJpaRepository : JpaRepository<ArtifactEntity, UUID> {
    fun findByRunIdOrderByCreatedAtAsc(runId: UUID): List<ArtifactEntity>

    @Modifying
    @Query("delete from ArtifactEntity a where a.runId in :runIds")
    fun deleteByRunIdIn(runIds: Collection<UUID>)
}

interface ApprovalRequestJpaRepository : JpaRepository<ApprovalRequestEntity, UUID>, JpaSpecificationExecutor<ApprovalRequestEntity> {
    fun findByStatusOrderByCreatedAtDesc(status: String, pageable: Pageable): List<ApprovalRequestEntity>
    fun findByStatusOrderByCreatedAtDesc(status: String): List<ApprovalRequestEntity>
    fun findFirstByRunIdAndStatus(runId: UUID, status: String): ApprovalRequestEntity?
    fun countByStatus(status: String): Long
    fun countByProjectIdAndStatus(projectId: UUID, status: String): Long
    fun deleteByProjectId(projectId: UUID)
}

interface CiExecutionJpaRepository : JpaRepository<CiExecutionEntity, UUID>, JpaSpecificationExecutor<CiExecutionEntity> {
    fun findFirstByRunId(runId: UUID): CiExecutionEntity?
    fun findFirstByIdempotencyKeyAndCreatedAtAfterOrderByCreatedAtDesc(key: String, since: Instant): CiExecutionEntity?
    fun findByProjectId(projectId: UUID, pageable: Pageable): Page<CiExecutionEntity>
    fun countByProjectIdAndCreatedAtAfter(projectId: UUID, since: Instant): Long
    fun deleteByProjectId(projectId: UUID)
}

interface RunLogJpaRepository : JpaRepository<RunLogEntity, Long> {
    fun findByRunIdOrderByAtAscIdAsc(runId: UUID): List<RunLogEntity>
    fun findByRunIdAndNodeIdOrderByAtAscIdAsc(runId: UUID, nodeId: String): List<RunLogEntity>
    fun findByRunIdAndAtAfterOrderByAtAscIdAsc(runId: UUID, after: Instant): List<RunLogEntity>
    fun findByRunIdAndNodeIdAndAtAfterOrderByAtAscIdAsc(runId: UUID, nodeId: String, after: Instant): List<RunLogEntity>

    @Modifying
    @Query("delete from RunLogEntity l where l.runId in :runIds")
    fun deleteByRunIdIn(runIds: Collection<UUID>)
}
