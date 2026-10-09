package pl.mos.backoffice.projects.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/*
 * JPA entities of the Projects & Workflows context (V101__workflows_and_ci.sql, V102__actor_stamps_and_gate_rules.sql). JSONB columns are kept
 * as JSON strings and mapped to documents by [PersistenceMapper] so the domain model stays free of
 * persistence concerns.
 */

@Entity
@Table(name = "project")
class ProjectEntity(
    @Id var id: UUID,
    @Column(nullable = false, unique = true) var key: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var description: String = "",
    /** ADR 0034: the repositories (JSON list); the legacy columns below mirror the primary one for one release. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var repositories: String = "[]",
    @Column(name = "repo_url") var repoUrl: String? = null,
    @Column(name = "repo_default_branch") var repoDefaultBranch: String? = null,
    @Column(name = "jira_project_key") var jiraProjectKey: String? = null,
    @Column(name = "jira_jql") var jiraJql: String? = null,
    @Column(nullable = false) var constitution: String = "",
    @Column(name = "default_model_profile_id") var defaultModelProfileId: UUID? = null,
    @Column(name = "review_model_profile_id") var reviewModelProfileId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "ci_entrypoints", nullable = false, columnDefinition = "jsonb") var ciEntrypoints: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "created_by", columnDefinition = "jsonb") var createdBy: String? = null,
    /** V110 (ADR 0036): Spec Kit is on for the project. */
    @Column(name = "spec_kit_enabled", nullable = false) var specKitEnabled: Boolean = false,
    /** V110 (ADR 0035): the system connection of the project's Jira steps. */
    @Column(name = "jira_system_connection_id") var jiraSystemConnectionId: UUID? = null,
    /** V110 (ADR 0035): ordered ids of the further connections of the project's Jira steps. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "jira_connection_ids", nullable = false, columnDefinition = "jsonb") var jiraConnectionIds: String = "[]",
)

@Entity
@Table(name = "project_ci_token")
class ProjectCiTokenEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "token_hash", nullable = false, unique = true) var tokenHash: String,
    @Column(nullable = false) var prefix: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "last_used_at") var lastUsedAt: Instant? = null,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "created_by", columnDefinition = "jsonb") var createdBy: String? = null,
)

@Entity
@Table(name = "workflow_definition")
class WorkflowDefinitionEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var description: String = "",
    @Column(nullable = false) var version: Int,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var nodes: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var edges: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var triggers: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var canvas: String? = null,
    @Column(nullable = false) var enabled: Boolean,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
    /** V111 (ADR 0037): the library entry and version this workflow is a linked copy of. */
    @Column(name = "library_workflow_id") var libraryWorkflowId: UUID? = null,
    @Column(name = "library_key") var libraryKey: String? = null,
    @Column(name = "library_version") var libraryVersion: Int? = null,
    @Column(name = "library_modified", nullable = false) var libraryModified: Boolean = false,
)

/** V111 (ADR 0037): a library workflow; its versions are [LibraryWorkflowVersionEntity] rows. */
@Entity
@Table(name = "library_workflow")
class LibraryWorkflowEntity(
    @Id var id: UUID,
    @Column(nullable = false, unique = true) var key: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var description: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var tags: String = "[]",
    @Column(nullable = false) var deprecated: Boolean = false,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
)

/** Composite key of [LibraryWorkflowVersionEntity]. */
data class LibraryWorkflowVersionKey(var libraryWorkflowId: UUID? = null, var version: Int? = null) : java.io.Serializable

@Entity
@Table(name = "library_workflow_version")
@IdClass(LibraryWorkflowVersionKey::class)
class LibraryWorkflowVersionEntity(
    @Id @Column(name = "library_workflow_id") var libraryWorkflowId: UUID,
    @Id var version: Int,
    @Column(name = "change_note", nullable = false) var changeNote: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var nodes: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var edges: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var triggers: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var canvas: String? = null,
    @Column(name = "published_at", nullable = false) var publishedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "published_by", columnDefinition = "jsonb") var publishedBy: String? = null,
    @Column(name = "source_project_id") var sourceProjectId: UUID? = null,
    @Column(name = "source_project_key") var sourceProjectKey: String? = null,
    @Column(name = "source_workflow_id") var sourceWorkflowId: UUID? = null,
    @Column(name = "source_workflow_version") var sourceWorkflowVersion: Int? = null,
)

@Entity
@Table(name = "workflow_run")
class WorkflowRunEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(name = "project_key", nullable = false) var projectKey: String,
    @Column(name = "workflow_id", nullable = false) var workflowId: UUID,
    @Column(name = "workflow_version", nullable = false) var workflowVersion: Int,
    @Column(name = "workflow_name", nullable = false) var workflowName: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "definition_snapshot", nullable = false, columnDefinition = "jsonb") var definitionSnapshot: String,
    @Column(name = "trigger_type", nullable = false) var triggerType: String,
    @Column(name = "ci_execution_id") var ciExecutionId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var input: String,
    @Column(name = "issue_key") var issueKey: String? = null,
    @Column(nullable = false) var status: String,
    var outcome: String? = null,
    @Column(name = "current_node_id") var currentNodeId: String? = null,
    @Column(name = "waiting_kind") var waitingKind: String? = null,
    @Column(name = "waiting_signal") var waitingSignal: String? = null,
    @Column(name = "open_approval_id") var openApprovalId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var nodes: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var failure: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "merge_request", columnDefinition = "jsonb") var mergeRequest: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var review: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "last_signal", columnDefinition = "jsonb") var lastSignal: String? = null,
    @Column(name = "cost_amount", nullable = false, precision = 18, scale = 6) var costAmount: BigDecimal,
    @Column(name = "cost_currency", nullable = false) var costCurrency: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "started_by", columnDefinition = "jsonb") var startedBy: String? = null,
    /** V111 (ADR 0037): the library version the run executed, copied from the definition snapshot (workflow metrics). */
    @Column(name = "library_workflow_id") var libraryWorkflowId: UUID? = null,
    @Column(name = "library_version") var libraryVersion: Int? = null,
    @Column(name = "library_modified", nullable = false) var libraryModified: Boolean = false,
)

@Entity
@Table(name = "artifact")
class ArtifactEntity(
    @Id var id: UUID,
    @Column(name = "run_id", nullable = false) var runId: UUID,
    @Column(name = "node_id", nullable = false) var nodeId: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var kind: String,
    @Column(name = "media_type", nullable = false) var mediaType: String,
    @Column(nullable = false) var content: String,
    @Column(name = "storage_ref") var storageRef: String? = null,
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    /** Set when the content lives in the BlobStore (> inline limit); [content] is then empty (V103). */
    @Column(name = "blob_key") var blobKey: String? = null,
    @Column(name = "sha256") var sha256: String? = null,
    /** Set when the retention job removed the blob content of an old run. */
    @Column(name = "content_expired_at") var contentExpiredAt: Instant? = null,
)

@Entity
@Table(name = "approval_request")
class ApprovalRequestEntity(
    @Id var id: UUID,
    @Column(name = "run_id", nullable = false) var runId: UUID,
    @Column(name = "node_id", nullable = false) var nodeId: String,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(name = "project_key", nullable = false) var projectKey: String,
    @Column(name = "workflow_name", nullable = false) var workflowName: String,
    @Column(name = "issue_key") var issueKey: String? = null,
    @Column(nullable = false) var question: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var options: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var context: String,
    @Column(nullable = false) var status: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var decision: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var approvers: String = "{}",
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var checklist: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "started_by", columnDefinition = "jsonb") var startedBy: String? = null,
    /** Run-time assignment (ADR 0028, V106): `{userIds, assignedBy, assignedAt, comment}`; null = the gate's own rule. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var assignment: String? = null,
    /** `GATE` or `WRITE_CONFIRMATION` (ADR 0033, V108). */
    @Column(nullable = false) var kind: String = "GATE",
    /** The exact external write of a write confirmation (ADR 0033, V108): `{action, issueKey, comment, toStatus, connectionId, connectionName}`. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "write_preview", columnDefinition = "jsonb") var writePreview: String? = null,
)

@Entity
@Table(name = "ci_execution")
class CiExecutionEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(name = "project_key", nullable = false) var projectKey: String,
    @Column(nullable = false) var entrypoint: String,
    @Column(nullable = false) var mode: String,
    @Column(nullable = false) var status: String,
    @Column(name = "gate_result", nullable = false) var gateResult: String,
    @Column(name = "idempotency_key", nullable = false) var idempotencyKey: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var context: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var variables: String,
    @Column(name = "diff_artifact_id") var diffArtifactId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "changed_files", nullable = false, columnDefinition = "jsonb") var changedFiles: String,
    @Column(name = "run_id") var runId: UUID? = null,
    var outcome: String? = null,
    var summary: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var report: String? = null,
    @Column(name = "cost_amount", nullable = false, precision = 18, scale = 6) var costAmount: BigDecimal,
    @Column(name = "cost_currency", nullable = false) var costCurrency: String,
    var error: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "repository_key") var repositoryKey: String? = null,
)

@Entity
@Table(name = "run_log")
class RunLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "run_id", nullable = false) var runId: UUID,
    @Column(name = "node_id") var nodeId: String? = null,
    @Column(nullable = false) var at: Instant,
    @Column(nullable = false) var level: String,
    @Column(nullable = false) var message: String,
)
