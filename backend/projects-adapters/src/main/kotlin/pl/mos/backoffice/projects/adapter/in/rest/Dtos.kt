package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import pl.mos.backoffice.projects.adapter.support.CanvasJson
import pl.mos.backoffice.projects.adapter.support.ChecklistItemJson
import pl.mos.backoffice.projects.adapter.support.GateApproversJson
import pl.mos.backoffice.projects.adapter.support.TriggerJson
import pl.mos.backoffice.projects.adapter.support.WorkflowEdgeJson
import pl.mos.backoffice.projects.adapter.support.WorkflowNodeJson
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/* Request and response bodies exactly as in docs/api/openapi.yaml. */

data class MoneyDto(val amount: BigDecimal, val currency: String = "USD")
data class TokenUsageDto(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0)
data class ModelOptionsDto(val temperature: Double? = null, val maxOutputTokens: Int? = null, val reasoning: String = "MEDIUM", val extra: Map<String, Any?> = emptyMap())
data class ModelRefDto(val provider: String, val modelId: String, val options: ModelOptionsDto = ModelOptionsDto())
data class PageDto<T>(val content: List<T>, val page: Int, val size: Int, val totalElements: Long)

/** Who did something (`ActorRef`): denormalised display name at the time of the action. */
data class ActorRefDto(val kind: String, val userId: UUID?, val displayName: String)

// ---- projects

/** Deprecated single repository (ADR 0034): response = the primary repository; request = used only without `repositories`. */
data class RepositoryDto(@field:NotBlank val url: String = "", val defaultBranch: String = "main")

/** openapi `ProjectRepository` (ADR 0034). */
data class ProjectRepositoryDto(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]{0,30}$") val key: String = "",
    @field:NotBlank val url: String = "",
    val defaultBranch: String? = "main",
    val purpose: String? = "SOURCE",
    val description: String? = null,
    val primary: Boolean = false,
)

/** openapi `RepositoryResult` (ADR 0034). */
data class RepositoryResultDto(
    val key: String,
    val url: String?,
    val purpose: String?,
    val access: String,
    val primary: Boolean,
    val branch: String?,
    val commitSha: String?,
    val diffStat: DiffStatDto?,
    val mergeRequestUrl: String?,
    val pushed: Boolean,
    val error: String?,
)
data class DiffStatDto(val files: Int, val insertions: Int, val deletions: Int)
data class IssueTrackerDto(val jiraProjectKey: String? = null, val jql: String? = null)
data class ProjectDefaultsDto(val modelProfileId: UUID? = null, val reviewModelProfileId: UUID? = null)
data class ProjectInputDto(
    /** Optional: generated from the name on create; on update a new key renames the project (null = unchanged). */
    @field:Pattern(regexp = "^([A-Z][A-Z0-9_]{1,15})?$") val key: String? = null,
    @field:NotBlank val name: String = "",
    val description: String = "",
    @field:Valid val repositories: List<ProjectRepositoryDto>? = null,
    /** Deprecated (ADR 0034): used only when [repositories] is absent. */
    @field:Valid val repository: RepositoryDto? = null,
    val issueTracker: IssueTrackerDto? = IssueTrackerDto(),
    val constitution: String = "",
    val defaults: ProjectDefaultsDto = ProjectDefaultsDto(),
    /** ADR 0038: create a private project (only on create). */
    val personal: Boolean = false,
    /** ADR 0036: Spec Kit for this project; null = off on create, unchanged on update. */
    val specKit: Boolean? = null,
)
data class CiEntrypointDto(
    @field:NotBlank val key: String = "",
    @field:NotNull val workflowId: UUID? = null,
    val workflowName: String? = null,
    val mode: String = "BLOCKING",
    val description: String? = null,
    val uploadDiff: Boolean = true,
    /** Optional repository key restriction (ADR 0034). */
    val repository: String? = null,
)
data class ProjectStatsDto(val workflows: Int, val activeRuns: Int, val openApprovals: Int, val ciExecutionsLast7Days: Int, val costLast30Days: MoneyDto)
data class ProjectDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val repositories: List<ProjectRepositoryDto>,
    /** Deprecated (ADR 0034): the primary repository, null when there is none. */
    val repository: RepositoryDto?,
    val issueTracker: IssueTrackerDto,
    val constitution: String,
    val defaults: ProjectDefaultsDto,
    val ciEntrypoints: List<CiEntrypointDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val stats: ProjectStatsDto? = null,
    val createdBy: ActorRefDto? = null,
    /** The caller's project roles on this project. */
    val myRoles: List<String> = emptyList(),
    /** The caller's effective project permissions on this project (the UI hides what they cannot do). */
    val myPermissions: List<String> = emptyList(),
    /** ADR 0036: Spec Kit is on for this project (it is used only when the platform allows it too). */
    val specKit: Boolean = false,
    /** ADR 0038: a private project of its creator only. */
    val personal: Boolean = false,
)
/** ADR 0036: the project's Spec Kit setting, the platform switch and the seeded Spec Kit workflows the project lacks. */
data class SpecKitStatusDto(val enabled: Boolean, val allowedByPlatform: Boolean, val effective: Boolean, val missingWorkflows: List<String>)
data class CiSnippetDto(val gitlabCi: String, val scriptUrl: String, val variables: List<String>)

// ---- CI tokens

data class CiTokenInputDto(@field:NotBlank val name: String = "")
data class CiTokenDto(val id: UUID, val name: String, val prefix: String, val createdAt: Instant, val lastUsedAt: Instant?, val revokedAt: Instant?, val createdBy: ActorRefDto?)
data class CiTokenCreatedDto(
    val id: UUID,
    val name: String,
    val prefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val createdBy: ActorRefDto?,
    val token: String,
)

// ---- workflows

data class WorkflowDefinitionInputDto(
    @field:NotBlank val name: String = "",
    val description: String = "",
    val nodes: List<WorkflowNodeJson> = emptyList(),
    val edges: List<WorkflowEdgeJson> = emptyList(),
    val triggers: List<TriggerJson> = listOf(TriggerJson()),
    val enabled: Boolean = true,
    val canvas: CanvasJson? = null,
)
data class WorkflowDefinitionDto(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val description: String,
    val version: Int,
    val nodes: List<WorkflowNodeJson>,
    val edges: List<WorkflowEdgeJson>,
    val triggers: List<TriggerJson>,
    val enabled: Boolean,
    val canvas: CanvasJson?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto?,
    val library: LibraryLinkDto? = null,
    /** Saved with validation errors: no runs start from it until it is saved without errors. */
    val draft: Boolean = false,
)
data class LibraryLinkDto(
    val libraryWorkflowId: UUID,
    val key: String,
    val name: String,
    val version: Int,
    val latestVersion: Int,
    val modified: Boolean,
    val deprecated: Boolean,
)

// ---- workflow library (ADR 0037)

data class LibraryWorkflowSourceDto(val projectId: UUID?, val projectKey: String?, val workflowId: UUID?, val workflowVersion: Int?)
data class LibraryWorkflowVersionSummaryDto(
    val version: Int,
    val changeNote: String,
    val nodeCount: Int,
    val publishedAt: Instant,
    val publishedBy: ActorRefDto?,
    val source: LibraryWorkflowSourceDto,
)
data class LibraryUsageDto(val projects: Int, val workflows: Int)
data class LibraryWorkflowAdoptionDto(
    val projectId: UUID,
    val projectKey: String,
    val workflowId: UUID,
    val workflowName: String,
    val version: Int,
    val modified: Boolean,
    val enabled: Boolean,
)
data class LibraryWorkflowDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val latestVersion: Int,
    val nodeCount: Int,
    val deprecated: Boolean,
    val usage: LibraryUsageDto,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto?,
    val latestPublishedAt: Instant,
    /** Only in the detail response; null in lists. */
    val versions: List<LibraryWorkflowVersionSummaryDto>? = null,
    val adoptions: List<LibraryWorkflowAdoptionDto>? = null,
)
data class LibraryWorkflowVersionDetailDto(
    val libraryWorkflowId: UUID,
    val key: String,
    val name: String,
    val description: String,
    val version: Int,
    val changeNote: String,
    val nodeCount: Int,
    val publishedAt: Instant,
    val publishedBy: ActorRefDto?,
    val source: LibraryWorkflowSourceDto,
    val nodes: List<WorkflowNodeJson>,
    val edges: List<WorkflowEdgeJson>,
    val triggers: List<TriggerJson>,
    val canvas: CanvasJson?,
)
data class PublishWorkflowRequestDto(
    val libraryWorkflowId: UUID? = null,
    val key: String? = null,
    val name: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val changeNote: String? = null,
)
data class LibraryWorkflowUpdateDto(
    @field:NotBlank val name: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val deprecated: Boolean = false,
)
data class AdoptLibraryWorkflowRequestDto(val libraryWorkflowId: UUID? = null, val version: Int? = null, val name: String? = null)
data class LibraryUpdateRequestDto(val version: Int? = null, val discardLocalChanges: Boolean = false)

data class ValidationIssueDto(val nodeId: String?, val edgeId: String?, val field: String?, val message: String)
data class ValidationResultDto(val valid: Boolean, val errors: List<ValidationIssueDto>, val warnings: List<ValidationIssueDto>)
data class ControlNodeDescriptorDto(val kind: String, val title: String, val description: String, val ports: List<String>, val configSchema: Map<String, Any?>)
data class PaletteTaskDto(
    val key: String,
    val name: String,
    val description: String,
    val executor: String,
    val interfaces: List<String>,
    val ports: List<String>,
    val projectScoped: Boolean,
    val configSchema: Map<String, Any?>,
    val requiredCapabilities: List<String> = emptyList(),
    val externalWrite: Boolean = false,
)
data class StateFieldDto(val path: String, val description: String)
data class WorkflowPaletteDto(
    val controlNodes: List<ControlNodeDescriptorDto>,
    val tasks: List<PaletteTaskDto>,
    val interfaces: List<Any> = emptyList(),
    val stateFields: List<StateFieldDto>,
)
data class ExpressionInputDto(@field:NotNull val expression: String? = null)
data class ExpressionCheckDto(val valid: Boolean, val message: String?)

// ---- runs

data class CiMergeRequestDto(
    val iid: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
    val url: String? = null,
    val author: String? = null,
)
data class CiContextDto(
    val provider: String = "OTHER",
    val projectPath: String? = null,
    val pipelineId: String? = null,
    val jobId: String? = null,
    val jobUrl: String? = null,
    val ref: String? = null,
    val commitSha: String? = null,
    val defaultBranch: String? = null,
    val actor: String? = null,
    val mergeRequest: CiMergeRequestDto? = null,
    val repositoryUrl: String? = null,
)
data class StartRunRequestDto(val issueKey: String? = null, val parameters: Map<String, Any?> = emptyMap())
data class RunInputDto(val issueKey: String?, val parameters: Map<String, Any?>, val ci: CiContextDto?)
data class RunTriggerDto(val type: String, val ciExecutionId: UUID?)
data class WaitingOnDto(val kind: String, val nodeId: String, val approvalId: UUID?, val signal: String?)
data class NodeExecutionDto(
    val id: UUID,
    val nodeId: String,
    val nodeName: String,
    val kind: String,
    val templateKey: String?,
    val attempt: Int,
    val status: String,
    val port: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val summary: String?,
    val confidence: Double?,
    val runnerJobId: UUID?,
    val usage: TokenUsageDto,
    val cost: MoneyDto,
    /** ADR 0042: the cost at API list prices (equals cost unless a subscription paid). */
    val apiCost: MoneyDto = MoneyDto(BigDecimal.ZERO),
    val error: String?,
    val artifactIds: List<UUID>,
    val repositories: List<RepositoryResultDto> = emptyList(),
)
data class MergeRequestDto(val url: String?, val branch: String, val targetBranch: String)
data class RunFailureDto(val nodeId: String?, val message: String, val retryable: Boolean)
data class FindingDto(val severity: String, val file: String, val line: Int?, val title: String, val detail: String, val suggestion: String?)
data class ReviewReportDto(
    val verdict: String,
    val score: Int,
    val summary: String,
    val findings: List<FindingDto>,
    val modelRef: ModelRefDto?,
    val usage: TokenUsageDto,
    val cost: MoneyDto,
)
data class WorkflowRunSummaryDto(
    val id: UUID,
    val projectId: UUID,
    val projectKey: String,
    val workflowId: UUID,
    val workflowName: String,
    val workflowVersion: Int,
    val trigger: RunTriggerDto,
    val issueKey: String?,
    val status: String,
    val outcome: String?,
    val currentNodeId: String?,
    val waitingOn: WaitingOnDto?,
    val cost: MoneyDto,
    /** ADR 0042: the cost at API list prices (equals cost unless a subscription paid). */
    val apiCost: MoneyDto = MoneyDto(BigDecimal.ZERO),
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val startedBy: ActorRefDto?,
)
data class WorkflowRunDto(
    val id: UUID,
    val projectId: UUID,
    val projectKey: String,
    val workflowId: UUID,
    val workflowName: String,
    val workflowVersion: Int,
    val trigger: RunTriggerDto,
    val issueKey: String?,
    val status: String,
    val outcome: String?,
    val currentNodeId: String?,
    val waitingOn: WaitingOnDto?,
    val cost: MoneyDto,
    /** ADR 0042: the cost at API list prices (equals cost unless a subscription paid). */
    val apiCost: MoneyDto = MoneyDto(BigDecimal.ZERO),
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val startedBy: ActorRefDto?,
    val input: RunInputDto,
    val definitionSnapshot: WorkflowDefinitionDto,
    val nodes: List<NodeExecutionDto>,
    val mergeRequest: MergeRequestDto?,
    val review: ReviewReportDto?,
    val failure: RunFailureDto?,
    val repositories: List<RepositoryResultDto> = emptyList(),
)
data class ManualSignalDto(@field:NotBlank val signal: String = "", @field:NotBlank val status: String = "", val message: String? = null)
data class ArtifactSummaryDto(val id: UUID, val nodeId: String, val name: String, val kind: String, val mediaType: String, val sizeBytes: Long, val createdAt: Instant)
data class ArtifactDto(val id: UUID, val nodeId: String, val name: String, val kind: String, val mediaType: String, val sizeBytes: Long, val createdAt: Instant, val content: String)
data class LogLineDto(val at: Instant, val nodeId: String?, val level: String, val message: String)

// ---- approvals

data class ApprovalContextDto(val summary: String?, val confidence: Double?, val artifacts: List<ArtifactSummaryDto>, val mergeRequestUrl: String?)
data class EditedArtifactDto(@field:NotNull val artifactId: UUID? = null, @field:NotNull val content: String? = null)
data class DecisionDto(
    @field:NotBlank val option: String = "",
    val comment: String? = null,
    /** ADR 0035, write confirmations: SYSTEM or PERSONAL; null = the step's default. */
    val writeAs: String? = null,
    @field:Valid val editedArtifacts: List<EditedArtifactDto> = emptyList(),
    val decidedAt: Instant? = null,
    /** Ids of the gate checklist items the approver ticked. */
    val checkedItems: List<String> = emptyList(),
    /** Read-only: who decided. Ignored on input. */
    val decidedBy: ActorRefDto? = null,
)
data class ApprovalRequestDto(
    val id: UUID,
    val runId: UUID,
    val projectId: UUID,
    val projectKey: String,
    val workflowName: String,
    val issueKey: String?,
    val nodeId: String,
    val question: String,
    val options: List<String>,
    val context: ApprovalContextDto,
    val status: String,
    val createdAt: Instant,
    val decision: DecisionDto?,
    val approvers: GateApproversJson,
    val checklist: List<ChecklistItemJson>,
    val startedBy: ActorRefDto?,
    /** Whether the caller may decide this gate (permission and approver rule). */
    val canDecide: Boolean,
    val cannotDecideReason: String?,
    /** Named users who decide: the run-time assignment, else the gate's `approvers.users` (empty = by role). */
    val assignees: List<AssigneeDto> = emptyList(),
    /** The run-time assignment (ADR 0028), null when the gate's own rule applies. */
    val assignment: ApprovalAssignmentDto? = null,
    /** Whether the caller may assign this open approval (`run.assign`). */
    val canAssign: Boolean = false,
    /** `GATE` or `WRITE_CONFIRMATION` (ADR 0033). */
    val kind: String = "GATE",
    /** The exact external write of a write confirmation; performed as shown after approval. */
    val write: WritePreviewDto? = null,
)
data class WritePreviewDto(
    val action: String,
    val issueKey: String,
    val comment: String?,
    val toStatus: String?,
    val connectionName: String?,
    val contentSha256: String,
    /** ADR 0035: SYSTEM, SPECIFIC or ACTING_USER (the step's `jiraConnection` setting). */
    val connectionMode: String = "SYSTEM",
    /** The approver may write with their own personal connection. */
    val personalAllowed: Boolean = false,
    /** The system (or pinned) connection may write, and one is usable. */
    val systemAllowed: Boolean = true,
    /** What approve does without a choice: SYSTEM or PERSONAL. */
    val defaultIdentity: String = "SYSTEM",
)
/** ADR 0035: what the CALLER could write a waiting Jira write as (write-confirmation dialog). */
data class WriteIdentityDto(
    val defaultIdentity: String,
    val systemAllowed: Boolean,
    val systemConnectionName: String?,
    val personalAllowed: Boolean,
    val personalConnectionName: String?,
    val personalProblem: String?,
    val canApprove: Boolean,
)
data class AssigneeDto(val userId: UUID, val displayName: String)
data class ApprovalAssignmentDto(val userIds: List<UUID>, val assignedBy: ActorRefDto?, val assignedAt: Instant, val comment: String?)
data class AssignApprovalDto(
    /** Users who decide this approval from now on; empty = back to the gate's own rule. */
    @field:NotNull @field:Size(max = 20) val userIds: List<UUID>? = null,
    @field:Size(max = 2000) val comment: String? = null,
)
data class AssigneeCandidateDto(val userId: UUID, val displayName: String, val projectRoles: List<String>, val assigned: Boolean)

// ---- CI

data class CiExecutionRequestDto(
    @field:NotBlank val entrypoint: String = "",
    @field:NotNull val context: CiContextDto? = CiContextDto(),
    val diff: String? = null,
    val changedFiles: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)
data class CiExecutionSummaryDto(
    val id: UUID,
    val projectId: UUID,
    val projectKey: String,
    val entrypoint: String,
    val mode: String,
    val status: String,
    val gateResult: String,
    val runId: UUID?,
    val context: CiContextDto,
    val cost: MoneyDto,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val repositoryKey: String? = null,
)
data class CiExecutionDto(
    val id: UUID,
    val projectId: UUID,
    val projectKey: String,
    val entrypoint: String,
    val mode: String,
    val status: String,
    val gateResult: String,
    val runId: UUID?,
    val context: CiContextDto,
    val cost: MoneyDto,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val summary: String?,
    val report: ReviewReportDto?,
    val diffArtifactId: UUID?,
    val changedFiles: List<String>,
    val variables: Map<String, String>,
    val error: String?,
    val repositoryKey: String? = null,
)
data class CiSignalRequestDto(
    @field:NotBlank val signal: String = "",
    @field:NotBlank val status: String = "",
    val runId: UUID? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val message: String? = null,
    val context: CiContextDto? = CiContextDto(),
)
data class CiSignalResultDto(val runId: UUID?, val resumed: Boolean, val message: String)

// ---- settings

data class EndpointStatusDto(val configured: Boolean, val url: String?, val legacy: Boolean = false, val platformConnections: Int = 0)
data class RunnerStatusDto(val mode: String, val image: String?, val namespace: String?)
data class LangfuseStatusDto(val enabled: Boolean, val url: String?)
data class CiStatusDto(val scriptUrl: String, val version: String)
data class ProviderStatusDto(val provider: String, val configured: Boolean, val baseUrl: String? = null)
data class IntegrationsStatusDto(
    val jira: EndpointStatusDto,
    val runner: RunnerStatusDto,
    val langfuse: LangfuseStatusDto,
    val ci: CiStatusDto,
    val providers: List<ProviderStatusDto>,
)
data class IntegrationTestResultDto(val ok: Boolean, val message: String, val latencyMs: Long?)

// ---- events

data class DomainEventEnvelopeDto(val id: String, val type: String, val occurredAt: Instant, val payload: Map<String, Any?>)
