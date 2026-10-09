package pl.mos.backoffice.projects.adapter.out.persistence

import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.adapter.support.CanvasJson
import pl.mos.backoffice.projects.adapter.support.ChecklistItemJson
import pl.mos.backoffice.projects.adapter.support.GateApproversJson
import pl.mos.backoffice.projects.adapter.support.TriggerJson
import pl.mos.backoffice.projects.adapter.support.WorkflowEdgeJson
import pl.mos.backoffice.projects.adapter.support.WorkflowJson
import pl.mos.backoffice.projects.adapter.support.WorkflowNodeJson
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.ApprovalContext
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.ApprovalAssignment
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.CiExecutionStatus
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.EditedArtifact
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.DiffStat
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.projects.domain.model.IssueTrackerBinding
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.NodeExecution
import pl.mos.backoffice.projects.domain.model.NodeExecutionId
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WaitingOn
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/* JSON documents stored in JSONB columns. */

data class CiEntrypointDoc(
    val key: String,
    val workflowId: String,
    val mode: String = "BLOCKING",
    val description: String? = null,
    val uploadDiff: Boolean = true,
    val repository: String? = null,
)
data class DefinitionDoc(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String = "",
    val version: Int,
    val nodes: List<WorkflowNodeJson>,
    val edges: List<WorkflowEdgeJson>,
    val triggers: List<TriggerJson>,
    val canvas: CanvasJson? = null,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorDoc? = null,
)
data class RunInputDoc(val issueKey: String? = null, val parameters: Map<String, Any?> = emptyMap(), val ci: Map<String, Any?>? = null)
data class TokenUsageDoc(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0)
data class MoneyDoc(val amount: BigDecimal = BigDecimal.ZERO, val currency: String = "USD")
data class NodeExecutionDoc(
    val id: String,
    val nodeId: String,
    val nodeName: String,
    val kind: String,
    val templateKey: String? = null,
    val attempt: Int,
    val status: String,
    val port: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val summary: String? = null,
    val confidence: Double? = null,
    val runnerJobId: String? = null,
    val usage: TokenUsageDoc = TokenUsageDoc(),
    val cost: MoneyDoc = MoneyDoc(),
    val error: String? = null,
    val artifactIds: List<String> = emptyList(),
    val repositories: List<RepositoryResultDoc> = emptyList(),
)
/** ADR 0034: one entry of `NodeExecutionDoc.repositories`. */
data class RepositoryResultDoc(
    val key: String,
    val url: String? = null,
    val purpose: String? = null,
    val access: String = "READ",
    val primary: Boolean = false,
    val branch: String? = null,
    val commitSha: String? = null,
    val diffStat: DiffStatDoc? = null,
    val mergeRequestUrl: String? = null,
    val pushed: Boolean = false,
    val error: String? = null,
)
data class DiffStatDoc(val files: Int = 0, val insertions: Int = 0, val deletions: Int = 0)
/** ADR 0034: one entry of `project.repositories`. */
data class ProjectRepositoryDoc(
    val key: String,
    val url: String,
    val defaultBranch: String = "main",
    val purpose: String = "SOURCE",
    val description: String? = null,
    val primary: Boolean = false,
)
data class RunFailureDoc(val nodeId: String? = null, val message: String, val retryable: Boolean = true)
data class ApprovalContextDoc(val summary: String? = null, val confidence: Double? = null, val artifactIds: List<String> = emptyList(), val mergeRequestUrl: String? = null)
data class EditedArtifactDoc(val artifactId: String, val content: String)
data class ActorDoc(val kind: String = "SYSTEM", val userId: String? = null, val displayName: String = "")
data class DecisionDoc(
    val option: String,
    val comment: String? = null,
    val editedArtifacts: List<EditedArtifactDoc> = emptyList(),
    val decidedAt: Instant,
    val checkedItems: List<String> = emptyList(),
    val decidedBy: ActorDoc? = null,
    /** ADR 0035: SYSTEM / PERSONAL for write confirmations. */
    val writeAs: String? = null,
)
data class ApprovalAssignmentDoc(val userIds: List<String>, val assignedBy: ActorDoc? = null, val assignedAt: Instant, val comment: String? = null)
data class FindingDoc(val severity: String, val file: String, val line: Int? = null, val title: String, val detail: String, val suggestion: String? = null)
data class ModelRefDoc(val provider: String, val modelId: String, val temperature: Double? = null, val maxOutputTokens: Int? = null, val reasoning: String = "MEDIUM", val extra: Map<String, Any?> = emptyMap())
data class ReviewReportDoc(
    val verdict: String,
    val score: Int,
    val summary: String,
    val findings: List<FindingDoc> = emptyList(),
    val modelRef: ModelRefDoc? = null,
    val usage: TokenUsageDoc = TokenUsageDoc(),
    val cost: MoneyDoc = MoneyDoc(),
)
data class CiSignalDoc(val signal: String, val status: String, val message: String? = null, val context: Map<String, Any?> = emptyMap(), val receivedAt: Instant)

/** Maps domain aggregates to entities and JSONB documents and back. */
@Component
class PersistenceMapper(private val json: JsonMapper) {

    private val stringList = object : TypeReference<List<String>>() {}
    private val stringMap = object : TypeReference<Map<String, String>>() {}
    private val anyMap = object : TypeReference<Map<String, Any?>>() {}
    private val entrypointList = object : TypeReference<List<CiEntrypointDoc>>() {}
    private val nodeList = object : TypeReference<List<WorkflowNodeJson>>() {}
    private val edgeList = object : TypeReference<List<WorkflowEdgeJson>>() {}
    private val triggerList = object : TypeReference<List<TriggerJson>>() {}
    private val executionList = object : TypeReference<List<NodeExecutionDoc>>() {}
    private val repositoryList = object : TypeReference<List<ProjectRepositoryDoc>>() {}
    private val checklistList = object : TypeReference<List<ChecklistItemJson>>() {}

    // ---- Actor

    fun toDoc(a: Actor) = ActorDoc(a.kind.name, a.userId?.toString(), a.displayName)
    fun toDomain(d: ActorDoc): Actor = Actor(PrincipalKind.entries.firstOrNull { it.name == d.kind } ?: PrincipalKind.SYSTEM, d.userId?.let(UUID::fromString), d.displayName)
    private fun writeActor(a: Actor?): String? = a?.let { write(toDoc(it)) }
    private fun readActor(text: String?): Actor? = text?.let { toDomain(read<ActorDoc>(it)) }

    fun <T> write(value: T): String = json.writeValueAsString(value)
    private inline fun <reified T> read(text: String): T = json.readValue(text, T::class.java)

    // ---- Project

    fun toEntity(p: Project) = ProjectEntity(
        id = p.id.value, key = p.key, name = p.name, description = p.description,
        repositories = write(p.repositories.map { ProjectRepositoryDoc(it.key, it.url, it.defaultBranch, it.purpose.name, it.description, it.primary) }),
        repoUrl = p.primaryRepository?.url, repoDefaultBranch = p.primaryRepository?.defaultBranch,
        jiraProjectKey = p.issueTracker.jiraProjectKey, jiraJql = p.issueTracker.jql, constitution = p.constitution,
        defaultModelProfileId = p.defaults.modelProfileId?.value, reviewModelProfileId = p.defaults.reviewModelProfileId?.value,
        ciEntrypoints = write(p.ciEntrypoints.map { CiEntrypointDoc(it.key, it.workflowId.toString(), it.mode.name, it.description, it.uploadDiff, it.repository) }),
        createdAt = p.createdAt, updatedAt = p.updatedAt, createdBy = writeActor(p.createdBy),
        specKitEnabled = p.specKit,
        jiraSystemConnectionId = p.jiraConnections.systemConnectionId?.value,
        jiraConnectionIds = write(p.jiraConnections.additionalConnectionIds.map { it.toString() }),
    )

    fun toDomain(e: ProjectEntity): Project = Project(
        id = ProjectId(e.id), key = e.key, name = e.name, description = e.description,
        repositories = json.readValue(e.repositories, repositoryList).map {
            GitRepository(
                it.key, it.url, it.defaultBranch, RepositoryPurpose.entries.firstOrNull { p -> p.name == it.purpose } ?: RepositoryPurpose.OTHER,
                it.description, it.primary,
            )
        },
        issueTracker = IssueTrackerBinding(e.jiraProjectKey, e.jiraJql),
        constitution = e.constitution,
        defaults = ProjectDefaults(e.defaultModelProfileId?.let(::ModelProfileId), e.reviewModelProfileId?.let(::ModelProfileId)),
        ciEntrypoints = json.readValue(e.ciEntrypoints, entrypointList).map { CiEntrypoint(it.key, WorkflowDefinitionId.of(it.workflowId), CiMode.valueOf(it.mode), it.description, it.uploadDiff, it.repository) },
        createdAt = e.createdAt, updatedAt = e.updatedAt, createdBy = readActor(e.createdBy),
        specKit = e.specKitEnabled,
        jiraConnections = run {
            val system = e.jiraSystemConnectionId?.let(::ConnectionId)
            // stale or duplicate ids (a connection deleted by an older replica) are dropped instead of failing the read
            val additional = json.readValue(e.jiraConnectionIds, stringList).mapNotNull { runCatching { ConnectionId.of(it) }.getOrNull() }
                .distinct().filterNot { it == system }.take(ProjectJiraConnections.MAX_ADDITIONAL)
            ProjectJiraConnections(system, additional)
        },
    )

    fun toEntity(t: ProjectCiToken) = ProjectCiTokenEntity(t.id.value, t.projectId.value, t.name, t.tokenHash, t.prefix, t.createdAt, t.lastUsedAt, t.revokedAt, writeActor(t.createdBy))

    fun toDomain(e: ProjectCiTokenEntity): ProjectCiToken =
        ProjectCiToken(ProjectCiTokenId(e.id), ProjectId(e.projectId), e.name, e.tokenHash, e.prefix, e.createdAt, e.lastUsedAt, e.revokedAt, readActor(e.createdBy))

    // ---- WorkflowDefinition

    fun toEntity(d: WorkflowDefinition) = WorkflowDefinitionEntity(
        id = d.id.value, projectId = d.projectId.value, name = d.name, description = d.description, version = d.version,
        nodes = write(d.nodes.map(WorkflowJson::toJson)), edges = write(d.edges.map(WorkflowJson::toJson)), triggers = write(d.triggers.map(WorkflowJson::toJson)),
        canvas = WorkflowJson.toJson(d.canvas)?.let { write(it) }, enabled = d.enabled, createdAt = d.createdAt, updatedAt = d.updatedAt,
        updatedBy = writeActor(d.updatedBy),
    )

    fun toDomain(e: WorkflowDefinitionEntity): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId(e.id), projectId = ProjectId(e.projectId), name = e.name, description = e.description, version = e.version,
        nodes = json.readValue(e.nodes, nodeList).map(WorkflowJson::toDomain), edges = json.readValue(e.edges, edgeList).map(WorkflowJson::toDomain),
        triggers = json.readValue(e.triggers, triggerList).map(WorkflowJson::toDomain), canvas = e.canvas?.let { WorkflowJson.toDomain(read<CanvasJson>(it)) },
        enabled = e.enabled, createdAt = e.createdAt, updatedAt = e.updatedAt, updatedBy = readActor(e.updatedBy),
    )

    fun toDoc(d: WorkflowDefinition) = DefinitionDoc(
        d.id.toString(), d.projectId.toString(), d.name, d.description, d.version, d.nodes.map(WorkflowJson::toJson), d.edges.map(WorkflowJson::toJson),
        d.triggers.map(WorkflowJson::toJson), WorkflowJson.toJson(d.canvas), d.enabled, d.createdAt, d.updatedAt, d.updatedBy?.let(::toDoc),
    )

    fun toDomain(d: DefinitionDoc): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId.of(d.id), projectId = ProjectId.of(d.projectId), name = d.name, description = d.description, version = d.version,
        nodes = d.nodes.map(WorkflowJson::toDomain), edges = d.edges.map(WorkflowJson::toDomain), triggers = d.triggers.map(WorkflowJson::toDomain),
        canvas = WorkflowJson.toDomain(d.canvas), enabled = d.enabled, createdAt = d.createdAt, updatedAt = d.updatedAt, updatedBy = d.updatedBy?.let(::toDomain),
    )

    // ---- WorkflowRun

    fun toDoc(u: TokenUsage) = TokenUsageDoc(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens)
    fun toDomain(u: TokenUsageDoc): TokenUsage = TokenUsage(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens)
    fun toDoc(m: Money) = MoneyDoc(m.amount, m.currency)
    fun toDomain(m: MoneyDoc): Money = Money(m.amount, m.currency)

    fun toDoc(s: NodeExecution) = NodeExecutionDoc(
        id = s.id.toString(), nodeId = s.nodeId, nodeName = s.nodeName, kind = s.kind.name, templateKey = s.templateKey, attempt = s.attempt, status = s.status.name,
        port = s.port, startedAt = s.startedAt, finishedAt = s.finishedAt, summary = s.summary, confidence = s.confidence, runnerJobId = s.runnerJobId?.toString(),
        usage = toDoc(s.usage), cost = toDoc(s.cost), error = s.error, artifactIds = s.artifactIds.map { it.toString() },
        repositories = s.repositories.map {
            RepositoryResultDoc(
                it.key, it.url, it.purpose?.name, it.access.name, it.primary, it.branch, it.commitSha,
                it.diffStat?.let { d -> DiffStatDoc(d.files, d.insertions, d.deletions) }, it.mergeRequestUrl, it.pushed, it.error,
            )
        },
    )

    fun toDomain(d: NodeExecutionDoc): NodeExecution = NodeExecution(
        id = NodeExecutionId.of(d.id), nodeId = d.nodeId, nodeName = d.nodeName, kind = NodeKind.valueOf(d.kind), templateKey = d.templateKey, attempt = d.attempt,
        status = NodeExecutionStatus.valueOf(d.status), port = d.port, startedAt = d.startedAt, finishedAt = d.finishedAt, summary = d.summary, confidence = d.confidence,
        runnerJobId = d.runnerJobId?.let(RunnerJobId::of), usage = toDomain(d.usage), cost = toDomain(d.cost), error = d.error,
        artifactIds = d.artifactIds.map(ArtifactId::of),
        repositories = d.repositories.map {
            RepositoryResult(
                key = it.key, url = it.url, purpose = it.purpose?.let { p -> RepositoryPurpose.entries.firstOrNull { e -> e.name == p } },
                access = GitAccess.entries.firstOrNull { a -> a.name == it.access } ?: GitAccess.READ, primary = it.primary, branch = it.branch,
                commitSha = it.commitSha, diffStat = it.diffStat?.let { d -> DiffStat(d.files, d.insertions, d.deletions) },
                mergeRequestUrl = it.mergeRequestUrl, pushed = it.pushed, error = it.error,
            )
        },
    )

    fun toDoc(f: Finding) = FindingDoc(f.severity.name, f.file, f.line, f.title, f.detail, f.suggestion)
    fun toDomain(f: FindingDoc): Finding = Finding(Severity.valueOf(f.severity), f.file, f.line, f.title, f.detail, f.suggestion)
    fun toDoc(m: ModelRef) = ModelRefDoc(m.provider.name, m.modelId, m.options.temperature, m.options.maxOutputTokens, m.options.reasoning.name, m.options.extra)
    fun toDomain(m: ModelRefDoc): ModelRef = ModelRef(Provider.valueOf(m.provider), m.modelId, ModelOptions(m.temperature, m.maxOutputTokens, Reasoning.valueOf(m.reasoning), m.extra))

    fun toDoc(r: ReviewReport) = ReviewReportDoc(r.verdict.name, r.score, r.summary, r.findings.map(::toDoc), r.modelRef?.let(::toDoc), toDoc(r.usage), toDoc(r.cost))
    fun toDomain(r: ReviewReportDoc): ReviewReport = ReviewReport(Verdict.valueOf(r.verdict), r.score, r.summary, r.findings.map(::toDomain), r.modelRef?.let(::toDomain), toDomain(r.usage), toDomain(r.cost))

    fun toDoc(s: CiSignal) = CiSignalDoc(s.signal, s.status.name, s.message, s.context.toMap(), s.receivedAt)
    fun toDomain(s: CiSignalDoc): CiSignal = CiSignal(s.signal, CiSignalStatus.valueOf(s.status), s.message, CiContext.fromMap(s.context), s.receivedAt)

    fun toEntity(r: WorkflowRun) = WorkflowRunEntity(
        id = r.id.value, projectId = r.projectId.value, projectKey = r.projectKey, workflowId = r.workflowId.value, workflowVersion = r.workflowVersion,
        workflowName = r.workflowName, definitionSnapshot = write(toDoc(r.definitionSnapshot)),
        triggerType = r.trigger.type.name, ciExecutionId = r.trigger.ciExecutionId?.value,
        input = write(RunInputDoc(r.input.issueKey, r.input.parameters, r.input.ci?.toMap())), issueKey = r.input.issueKey,
        status = r.status.name, outcome = r.outcome?.name, currentNodeId = r.currentNodeId,
        waitingKind = r.waitingOn?.kind?.name, waitingSignal = r.waitingOn?.signal, openApprovalId = r.waitingOn?.approvalId?.value,
        nodes = write(r.nodes.map(::toDoc)),
        failure = r.failure?.let { write(RunFailureDoc(it.nodeId, it.message, it.retryable)) },
        mergeRequest = r.mergeRequest?.let { write(it.toMap()) },
        review = r.review?.let { write(toDoc(it)) },
        lastSignal = r.lastSignal?.let { write(toDoc(it)) },
        costAmount = r.cost.amount, costCurrency = r.cost.currency, createdAt = r.createdAt, startedAt = r.startedAt, finishedAt = r.finishedAt,
        startedBy = writeActor(r.startedBy),
    )

    fun toDomain(e: WorkflowRunEntity, artifacts: List<Artifact>): WorkflowRun {
        val input = read<RunInputDoc>(e.input)
        val waitingKind = e.waitingKind?.let(WaitKind::valueOf)
        return WorkflowRun(
            id = WorkflowRunId(e.id), projectId = ProjectId(e.projectId), projectKey = e.projectKey,
            workflowId = WorkflowDefinitionId(e.workflowId), workflowVersion = e.workflowVersion,
            definitionSnapshot = toDomain(read<DefinitionDoc>(e.definitionSnapshot)),
            trigger = RunTrigger(TriggerSource.valueOf(e.triggerType), e.ciExecutionId?.let(::CiExecutionId)),
            input = RunInput(input.issueKey, input.parameters, input.ci?.let(CiContext::fromMap)),
            status = RunStatus.valueOf(e.status), outcome = e.outcome?.let(RunOutcome::valueOf), currentNodeId = e.currentNodeId,
            waitingOn = if (waitingKind != null && e.currentNodeId != null) WaitingOn(waitingKind, e.currentNodeId!!, e.openApprovalId?.let(::ApprovalRequestId), e.waitingSignal) else null,
            nodes = json.readValue(e.nodes, executionList).map(::toDomain), artifacts = artifacts,
            cost = Money(e.costAmount, e.costCurrency).rounded(),
            mergeRequest = e.mergeRequest?.let { MergeRequestSnapshot.fromMap(json.readValue(it, anyMap)) },
            review = e.review?.let { toDomain(read<ReviewReportDoc>(it)) },
            lastSignal = e.lastSignal?.let { toDomain(read<CiSignalDoc>(it)) },
            failure = e.failure?.let { read<RunFailureDoc>(it) }?.let { RunFailure(it.nodeId, it.message, it.retryable) },
            createdAt = e.createdAt, startedAt = e.startedAt, finishedAt = e.finishedAt, startedBy = readActor(e.startedBy),
        )
    }

    fun toEntity(a: Artifact, runId: WorkflowRunId) = ArtifactEntity(
        id = a.id.value, runId = runId.value, nodeId = a.nodeId, name = a.name, kind = a.kind.name, mediaType = a.mediaType,
        content = a.content, storageRef = a.storageRef, sizeBytes = a.sizeBytes, createdAt = a.createdAt,
    )

    fun toDomain(e: ArtifactEntity, content: String = e.content): Artifact = Artifact(
        id = ArtifactId(e.id), nodeId = e.nodeId, name = e.name, kind = ArtifactKind.valueOf(e.kind), mediaType = e.mediaType,
        content = content, storageRef = e.storageRef, createdAt = e.createdAt,
    )

    // ---- ApprovalRequest

    fun toEntity(a: ApprovalRequest) = ApprovalRequestEntity(
        id = a.id.value, runId = a.runId.value, nodeId = a.nodeId, projectId = a.projectId.value, projectKey = a.projectKey,
        workflowName = a.workflowName, issueKey = a.issueKey, question = a.question, options = write(a.options),
        context = write(ApprovalContextDoc(a.context.summary, a.context.confidence, a.context.artifactIds.map { it.toString() }, a.context.mergeRequestUrl)),
        status = a.status.name,
        decision = a.decision?.let {
            write(DecisionDoc(it.option, it.comment, it.editedArtifacts.map { e -> EditedArtifactDoc(e.artifactId.toString(), e.content) }, it.decidedAt, it.checkedItems, it.decidedBy?.let(::toDoc), it.writeAs?.name))
        },
        createdAt = a.createdAt,
        approvers = write(WorkflowJson.toJson(a.approvers)),
        checklist = write(a.checklist.map(WorkflowJson::toJson)),
        startedBy = writeActor(a.startedBy),
        assignment = a.assignment?.let { write(ApprovalAssignmentDoc(it.userIds.map(UUID::toString), it.assignedBy?.let(::toDoc), it.assignedAt, it.comment)) },
        kind = a.kind.name,
        writePreview = a.write?.let { write(it.toStateMap()) },
    )

    fun toDomain(e: ApprovalRequestEntity): ApprovalRequest {
        val ctx = read<ApprovalContextDoc>(e.context)
        return ApprovalRequest(
            id = ApprovalRequestId(e.id), runId = WorkflowRunId(e.runId), nodeId = e.nodeId, projectId = ProjectId(e.projectId), projectKey = e.projectKey,
            workflowName = e.workflowName, issueKey = e.issueKey, question = e.question, options = json.readValue(e.options, stringList),
            context = ApprovalContext(ctx.summary, ctx.confidence, ctx.artifactIds.map(ArtifactId::of), ctx.mergeRequestUrl),
            status = ApprovalStatus.valueOf(e.status),
            decision = e.decision?.let { read<DecisionDoc>(it) }?.let { d ->
                Decision(d.option, d.comment, d.editedArtifacts.map { EditedArtifact(ArtifactId.of(it.artifactId), it.content) }, d.decidedAt, d.checkedItems, d.decidedBy?.let(::toDomain),
                    d.writeAs?.let { w -> WriteIdentity.entries.firstOrNull { it.name == w } })
            },
            createdAt = e.createdAt,
            approvers = WorkflowJson.toDomain(read<GateApproversJson>(e.approvers)),
            checklist = json.readValue(e.checklist, checklistList).map(WorkflowJson::toDomain),
            startedBy = readActor(e.startedBy),
            assignment = e.assignment?.let { read<ApprovalAssignmentDoc>(it) }?.let { d ->
                ApprovalAssignment(d.userIds.map(UUID::fromString), d.assignedBy?.let(::toDomain), d.assignedAt, d.comment)
            },
            kind = ApprovalKind.valueOf(e.kind),
            write = e.writePreview?.let { PendingWrite.fromStateMap(json.readValue(it, anyMap)) },
        )
    }

    // ---- CiExecution

    fun toEntity(c: CiExecution) = CiExecutionEntity(
        id = c.id.value, projectId = c.projectId.value, projectKey = c.projectKey, entrypoint = c.entrypoint, mode = c.mode.name, status = c.status.name,
        gateResult = c.gateResult.name, idempotencyKey = c.idempotencyKey, context = write(c.context.toMap()), variables = write(c.variables),
        diffArtifactId = c.diffArtifactId?.value, changedFiles = write(c.changedFiles), runId = c.runId?.value, outcome = c.outcome?.name, summary = c.summary,
        report = c.report?.let { write(toDoc(it)) }, costAmount = c.cost.amount, costCurrency = c.cost.currency, error = c.error,
        createdAt = c.createdAt, finishedAt = c.finishedAt, repositoryKey = c.repositoryKey,
    )

    fun toDomain(e: CiExecutionEntity): CiExecution = CiExecution(
        id = CiExecutionId(e.id), projectId = ProjectId(e.projectId), projectKey = e.projectKey, entrypoint = e.entrypoint, mode = CiMode.valueOf(e.mode),
        status = CiExecutionStatus.valueOf(e.status), context = CiContext.fromMap(json.readValue(e.context, anyMap)), variables = json.readValue(e.variables, stringMap),
        diffArtifactId = e.diffArtifactId?.let(::ArtifactId), changedFiles = json.readValue(e.changedFiles, stringList), runId = e.runId?.let(::WorkflowRunId),
        outcome = e.outcome?.let(RunOutcome::valueOf), summary = e.summary, report = e.report?.let { toDomain(read<ReviewReportDoc>(it)) },
        cost = Money(e.costAmount, e.costCurrency).rounded(), error = e.error, createdAt = e.createdAt, finishedAt = e.finishedAt,
        repositoryKey = e.repositoryKey,
    )
}
