package pl.mos.backoffice.projects.adapter.`in`.rest

import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.projects.application.port.`in`.WriteIdentityQuery
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.adapter.support.WorkflowJson
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.IntegrationsStatus
import pl.mos.backoffice.projects.application.port.`in`.ManageCiTokens
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowVersion
import pl.mos.backoffice.projects.application.port.`in`.SignalRun
import pl.mos.backoffice.projects.application.port.`in`.StartCiExecution
import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiMergeRequest
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.CiProvider
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.EditedArtifact
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.IssueTrackerBinding
import pl.mos.backoffice.projects.domain.model.NodeExecution
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.CiSnippet
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.projects.domain.service.WorkflowValidationResult
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import java.util.UUID

/** Maps between REST DTOs and domain / use-case types. Enum parsing errors become 400 validation problems. */
@Component
class RestMapper {

    private inline fun <reified E : Enum<E>> enum(value: String?, field: String, default: E? = null): E {
        if (value.isNullOrBlank()) return default ?: throw ValidationException("is required", field)
        return enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw ValidationException("must be one of ${enumValues<E>().joinToString()}", field)
    }

    fun money(m: Money) = MoneyDto(m.amount, m.currency)
    fun actor(a: Actor?): ActorRefDto? = a?.let { ActorRefDto(it.kind.name, it.userId, it.displayName) }
    fun usage(u: TokenUsage) = TokenUsageDto(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens)
    fun modelRef(m: ModelRef) = ModelRefDto(m.provider.name, m.modelId, ModelOptionsDto(m.options.temperature, m.options.maxOutputTokens, m.options.reasoning.name, m.options.extra))

    // ---- projects

    fun toCommand(dto: ProjectInputDto) = ManageProjects.ProjectCommand(
        key = dto.key?.trim()?.ifEmpty { null },
        name = dto.name.trim(),
        description = dto.description,
        repositories = repositories(dto),
        issueTracker = IssueTrackerBinding(dto.issueTracker?.jiraProjectKey?.takeIf { it.isNotBlank() }, dto.issueTracker?.jql?.takeIf { it.isNotBlank() }),
        constitution = dto.constitution,
        defaults = ProjectDefaults(dto.defaults.modelProfileId?.let(::ModelProfileId), dto.defaults.reviewModelProfileId?.let(::ModelProfileId)),
        specKit = dto.specKit,
        personal = dto.personal,
    )

    /** `repositories` wins; the deprecated `repository` (ADR 0034) becomes the single primary repository `main`. */
    private fun repositories(dto: ProjectInputDto): List<GitRepository> = when {
        dto.repositories != null -> dto.repositories.mapIndexed { i, r ->
            GitRepository(
                key = r.key.trim(),
                url = r.url.trim(),
                defaultBranch = r.defaultBranch?.trim()?.takeIf { it.isNotBlank() } ?: GitRepository.DEFAULT_BRANCH,
                purpose = enum(r.purpose, "repositories[$i].purpose", RepositoryPurpose.SOURCE),
                description = r.description?.trim()?.takeIf { it.isNotBlank() },
                primary = r.primary,
            )
        }
        dto.repository != null && dto.repository.url.isNotBlank() -> listOf(GitRepository.legacy(dto.repository.url, dto.repository.defaultBranch))
        else -> emptyList()
    }

    fun toDto(r: GitRepository) = ProjectRepositoryDto(r.key, r.url, r.defaultBranch, r.purpose.name, r.description, r.primary)

    fun toDto(r: RepositoryResult) = RepositoryResultDto(
        key = r.key, url = r.url, purpose = r.purpose?.name, access = r.access.name, primary = r.primary, branch = r.branch, commitSha = r.commitSha,
        diffStat = r.diffStat?.let { DiffStatDto(it.files, it.insertions, it.deletions) }, mergeRequestUrl = r.mergeRequestUrl, pushed = r.pushed, error = r.error,
    )

    fun toDto(
        p: Project,
        stats: ManageProjects.ProjectStats? = null,
        workflowNames: Map<WorkflowDefinitionId, String> = emptyMap(),
        myRoles: Collection<String> = emptyList(),
        myPermissions: Collection<String> = emptyList(),
    ): ProjectDto = ProjectDto(
        id = p.id.value, key = p.key, name = p.name, description = p.description,
        repositories = p.repositories.map(::toDto),
        repository = p.primaryRepository?.let { RepositoryDto(it.url, it.defaultBranch) },
        issueTracker = IssueTrackerDto(p.issueTracker.jiraProjectKey, p.issueTracker.jql),
        constitution = p.constitution,
        defaults = ProjectDefaultsDto(p.defaults.modelProfileId?.value, p.defaults.reviewModelProfileId?.value),
        ciEntrypoints = p.ciEntrypoints.map { toDto(it, workflowNames[it.workflowId]) },
        createdAt = p.createdAt, updatedAt = p.updatedAt,
        stats = stats?.let { ProjectStatsDto(it.workflows, it.activeRuns, it.openApprovals, it.ciExecutionsLast7Days, money(it.costLast30Days)) },
        createdBy = actor(p.createdBy), myRoles = myRoles.sorted(), myPermissions = myPermissions.sorted(),
        specKit = p.specKit,
        personal = p.personal,
    )

    fun toDto(e: CiEntrypoint, workflowName: String?) = CiEntrypointDto(e.key, e.workflowId.value, workflowName, e.mode.name, e.description, e.uploadDiff, e.repository)

    fun toDomain(dto: CiEntrypointDto) = CiEntrypoint(
        key = dto.key.trim(),
        workflowId = WorkflowDefinitionId(dto.workflowId ?: throw ValidationException("is required", "ciEntrypoints.${dto.key}.workflowId")),
        mode = enum(dto.mode, "ciEntrypoints.${dto.key}.mode", CiMode.BLOCKING),
        description = dto.description?.takeIf { it.isNotBlank() },
        uploadDiff = dto.uploadDiff,
        repository = dto.repository?.trim()?.takeIf { it.isNotBlank() },
    )

    fun toDto(s: CiSnippet) = CiSnippetDto(s.gitlabCi, s.scriptUrl, s.variables)

    fun toDto(t: ProjectCiToken) = CiTokenDto(t.id.value, t.name, t.prefix, t.createdAt, t.lastUsedAt, t.revokedAt, actor(t.createdBy))

    fun toDto(c: ManageCiTokens.CreatedToken) =
        CiTokenCreatedDto(c.token.id.value, c.token.name, c.token.prefix, c.token.createdAt, c.token.lastUsedAt, c.token.revokedAt, actor(c.token.createdBy), c.plaintext)

    // ---- workflows

    fun toCommand(dto: WorkflowDefinitionInputDto) = ManageWorkflows.WorkflowCommand(
        name = dto.name.trim(),
        description = dto.description,
        nodes = dto.nodes.map(WorkflowJson::toDomain),
        edges = dto.edges.map(WorkflowJson::toDomain),
        triggers = dto.triggers.map(WorkflowJson::toDomain).ifEmpty { listOf(pl.mos.backoffice.projects.domain.model.Trigger(pl.mos.backoffice.projects.domain.model.TriggerType.MANUAL)) },
        enabled = dto.enabled,
        canvas = WorkflowJson.toDomain(dto.canvas),
    )

    /**
     * [status] is the live state of the linked library entry (latest version, deprecation); without it (run snapshots)
     * the link reports the version it recorded as the latest.
     */
    fun toDto(d: WorkflowDefinition, status: ManageWorkflowLibrary.LinkStatus? = null): WorkflowDefinitionDto = WorkflowDefinitionDto(
        id = d.id.value, projectId = d.projectId.value, name = d.name, description = d.description, version = d.version,
        nodes = d.nodes.map(WorkflowJson::toJson), edges = d.edges.map(WorkflowJson::toJson), triggers = d.triggers.map(WorkflowJson::toJson),
        enabled = d.enabled, canvas = WorkflowJson.toJson(d.canvas), createdAt = d.createdAt, updatedAt = d.updatedAt, updatedBy = actor(d.updatedBy),
        library = d.library?.let {
            LibraryLinkDto(
                it.libraryWorkflowId.value, it.key, status?.name ?: it.key, it.version, maxOf(status?.latestVersion ?: it.version, it.version),
                it.modified, status?.deprecated ?: false,
            )
        },
        draft = d.draft,
    )

    // ---- workflow library (ADR 0037)

    fun toDto(v: ManageWorkflowLibrary.LibraryWorkflowView, detail: Boolean = false): LibraryWorkflowDto {
        val w = v.workflow
        val latest = w.latest
        return LibraryWorkflowDto(
            id = w.id.value, key = w.key, name = w.name, description = w.description, tags = w.tags, latestVersion = latest.version,
            nodeCount = latest.nodeCount, deprecated = w.deprecated, usage = LibraryUsageDto(v.projectCount, v.adoptions.size),
            createdAt = w.createdAt, updatedAt = w.updatedAt, updatedBy = actor(w.updatedBy), latestPublishedAt = latest.publishedAt,
            versions = if (detail) w.versions.sortedByDescending { it.version }.map(::versionSummary) else null,
            adoptions = if (detail) {
                v.adoptions.map { LibraryWorkflowAdoptionDto(it.projectId.value, it.projectKey, it.workflowId.value, it.workflowName, it.version, it.modified, it.enabled) }
            } else {
                null
            },
        )
    }

    fun toDto(v: ManageWorkflowLibrary.LibraryVersionView): LibraryWorkflowVersionDetailDto {
        val s = versionSummary(v.version)
        return LibraryWorkflowVersionDetailDto(
            libraryWorkflowId = v.workflow.id.value, key = v.workflow.key, name = v.workflow.name, description = v.workflow.description,
            version = s.version, changeNote = s.changeNote, nodeCount = s.nodeCount, publishedAt = s.publishedAt, publishedBy = s.publishedBy, source = s.source,
            nodes = v.version.nodes.map(WorkflowJson::toJson), edges = v.version.edges.map(WorkflowJson::toJson),
            triggers = v.version.triggers.map(WorkflowJson::toJson), canvas = WorkflowJson.toJson(v.version.canvas),
        )
    }

    private fun versionSummary(v: LibraryWorkflowVersion) = LibraryWorkflowVersionSummaryDto(
        v.version, v.changeNote, v.nodeCount, v.publishedAt, actor(v.publishedBy),
        LibraryWorkflowSourceDto(v.source.projectId?.value, v.source.projectKey, v.source.workflowId?.value, v.source.workflowVersion),
    )

    fun toDto(r: WorkflowValidationResult): ValidationResultDto = ValidationResultDto(
        valid = r.valid,
        errors = r.errors.map { ValidationIssueDto(it.nodeId, it.edgeId, it.field, it.message) },
        warnings = r.warnings.map { ValidationIssueDto(it.nodeId, it.edgeId, it.field, it.message) },
    )

    fun toDto(p: ManageWorkflows.Palette): WorkflowPaletteDto = WorkflowPaletteDto(
        controlNodes = p.controlNodes.map { ControlNodeDescriptorDto(it.kind.name, it.title, it.description, it.ports, it.configSchema) },
        tasks = p.tasks.map { PaletteTaskDto(it.key, it.name, it.description, it.executor.name, it.interfaces, it.ports, it.projectScoped, it.configSchema, it.requiredCapabilities.map { c -> c.name }, it.externalWrite) },
        stateFields = p.stateFields.map { StateFieldDto(it.path, it.description) },
    )

    fun toDto(c: ManageWorkflows.ExpressionCheck) = ExpressionCheckDto(c.valid, c.message)

    // ---- runs

    fun toInput(dto: StartRunRequestDto) = RunInput(dto.issueKey?.trim()?.takeIf { it.isNotBlank() }, dto.parameters)

    fun toDomain(dto: CiContextDto?): CiContext = CiContext(
        provider = enum(dto?.provider, "context.provider", CiProvider.OTHER),
        projectPath = dto?.projectPath, pipelineId = dto?.pipelineId, jobId = dto?.jobId, jobUrl = dto?.jobUrl, ref = dto?.ref,
        commitSha = dto?.commitSha, defaultBranch = dto?.defaultBranch, actor = dto?.actor,
        mergeRequest = dto?.mergeRequest?.let { CiMergeRequest(it.iid, it.title, it.description, it.sourceBranch, it.targetBranch, it.url, it.author) },
        repositoryUrl = dto?.repositoryUrl?.trim()?.takeIf { it.isNotBlank() },
    )

    fun toDto(c: CiContext) = CiContextDto(
        c.provider.name, c.projectPath, c.pipelineId, c.jobId, c.jobUrl, c.ref, c.commitSha, c.defaultBranch, c.actor,
        c.mergeRequest?.let { CiMergeRequestDto(it.iid, it.title, it.description, it.sourceBranch, it.targetBranch, it.url, it.author) },
        c.repositoryUrl,
    )

    fun toSummary(r: WorkflowRun): WorkflowRunSummaryDto = WorkflowRunSummaryDto(
        id = r.id.value, projectId = r.projectId.value, projectKey = r.projectKey, workflowId = r.workflowId.value, workflowName = r.workflowName,
        workflowVersion = r.workflowVersion, trigger = RunTriggerDto(r.trigger.type.name, r.trigger.ciExecutionId?.value), issueKey = r.issueKey,
        status = r.status.name, outcome = r.outcome?.name, currentNodeId = r.currentNodeId,
        waitingOn = r.waitingOn?.let { WaitingOnDto(it.kind.name, it.nodeId, it.approvalId?.value, it.signal) },
        cost = money(r.cost), createdAt = r.createdAt, startedAt = r.startedAt, finishedAt = r.finishedAt, startedBy = actor(r.startedBy),
    )

    fun toDto(r: WorkflowRun): WorkflowRunDto = WorkflowRunDto(
        id = r.id.value, projectId = r.projectId.value, projectKey = r.projectKey, workflowId = r.workflowId.value, workflowName = r.workflowName,
        workflowVersion = r.workflowVersion, trigger = RunTriggerDto(r.trigger.type.name, r.trigger.ciExecutionId?.value), issueKey = r.issueKey,
        status = r.status.name, outcome = r.outcome?.name, currentNodeId = r.currentNodeId,
        waitingOn = r.waitingOn?.let { WaitingOnDto(it.kind.name, it.nodeId, it.approvalId?.value, it.signal) },
        cost = money(r.cost), createdAt = r.createdAt, startedAt = r.startedAt, finishedAt = r.finishedAt, startedBy = actor(r.startedBy),
        input = RunInputDto(r.input.issueKey, r.input.parameters, r.input.ci?.let(::toDto)),
        definitionSnapshot = toDto(r.definitionSnapshot),
        nodes = r.nodes.map(::toDto),
        mergeRequest = r.mergeRequest?.let { MergeRequestDto(it.url, it.branch, it.targetBranch) },
        review = r.review?.let(::toDto),
        failure = r.failure?.let { RunFailureDto(it.nodeId, it.message, it.retryable) },
        repositories = r.repositoryResults.map(::toDto),
    )

    fun toDto(s: NodeExecution): NodeExecutionDto = NodeExecutionDto(
        id = s.id.value, nodeId = s.nodeId, nodeName = s.nodeName, kind = s.kind.name, templateKey = s.templateKey, attempt = s.attempt, status = s.status.name,
        port = s.port, startedAt = s.startedAt, finishedAt = s.finishedAt, summary = s.summary, confidence = s.confidence, runnerJobId = s.runnerJobId?.value,
        usage = usage(s.usage), cost = money(s.cost), error = s.error, artifactIds = s.artifactIds.map { it.value },
        repositories = s.repositories.map(::toDto),
    )

    fun toDto(r: ReviewReport) = ReviewReportDto(r.verdict.name, r.score, r.summary, r.findings.map(::toDto), r.modelRef?.let(::modelRef), usage(r.usage), money(r.cost))
    fun toDto(f: Finding): FindingDto = FindingDto(f.severity.name, f.file, f.line, f.title, f.detail, f.suggestion)

    fun toSummary(a: Artifact): ArtifactSummaryDto = ArtifactSummaryDto(a.id.value, a.nodeId, a.name, a.kind.name, a.mediaType, a.sizeBytes, a.createdAt)
    fun toDto(a: Artifact): ArtifactDto = ArtifactDto(a.id.value, a.nodeId, a.name, a.kind.name, a.mediaType, a.sizeBytes, a.createdAt, a.content)
    fun toDto(l: RunLogLine): LogLineDto = LogLineDto(l.at, l.nodeId, l.level.name, l.message)

    fun signalStatus(value: String): CiSignalStatus = enum(value, "status")

    // ---- approvals

    /**
     * [check] is the caller's view of the gate rule (null: not evaluated, reported as not decidable); [names]
     * are the display names of the assignees (an unknown id is shown as itself); [canAssign] the caller's `run.assign`.
     */
    fun toDto(
        a: ApprovalRequest,
        artifacts: List<Artifact>,
        check: GateApprovalPolicy.Check? = null,
        names: Map<UUID, String> = emptyMap(),
        canAssign: Boolean = false,
    ): ApprovalRequestDto = ApprovalRequestDto(
        id = a.id.value, runId = a.runId.value, projectId = a.projectId.value, projectKey = a.projectKey, workflowName = a.workflowName,
        issueKey = a.issueKey, nodeId = a.nodeId, question = a.question, options = a.options,
        context = ApprovalContextDto(
            a.context.summary, a.context.confidence,
            a.context.artifactIds.mapNotNull { id -> artifacts.firstOrNull { it.id == id } }.map(::toSummary),
            a.context.mergeRequestUrl,
        ),
        status = a.status.name, createdAt = a.createdAt, decision = a.decision?.let(::toDto),
        approvers = WorkflowJson.toJson(a.approvers), checklist = a.checklist.map(WorkflowJson::toJson), startedBy = actor(a.startedBy),
        canDecide = check?.allowed ?: false, cannotDecideReason = if (check?.allowed == true) null else check?.reason,
        assignees = a.assignees.map { AssigneeDto(it, names[it] ?: it.toString()) },
        assignment = a.assignment?.let { ApprovalAssignmentDto(it.userIds, actor(it.assignedBy), it.assignedAt, it.comment) },
        canAssign = canAssign && a.status == ApprovalStatus.OPEN,
        kind = a.kind.name,
        write = a.write?.let {
            WritePreviewDto(
                it.action.name, it.issueKey, it.comment, it.toStatus, it.connectionName, it.digest(),
                it.connectionMode.name, it.personalAllowed, it.systemAllowed, it.defaultIdentity.name,
            )
        },
    )

    fun toDto(c: AssignApproval.AssigneeCandidate) = AssigneeCandidateDto(c.userId, c.displayName, c.projectRoles.sorted(), c.assigned)

    fun toCommand(dto: AssignApprovalDto) = AssignApproval.AssignCommand(dto.userIds ?: emptyList(), dto.comment?.takeIf { it.isNotBlank() })

    fun toDto(d: Decision): DecisionDto =
        DecisionDto(d.option, d.comment, d.writeAs?.name, d.editedArtifacts.map { EditedArtifactDto(it.artifactId.value, it.content) }, d.decidedAt, d.checkedItems, actor(d.decidedBy))

    fun toCommand(dto: DecisionDto) = DecideApproval.DecisionCommand(
        option = dto.option.trim(),
        comment = dto.comment?.takeIf { it.isNotBlank() },
        editedArtifacts = dto.editedArtifacts.map { EditedArtifact(ArtifactId(it.artifactId!!), it.content ?: "") },
        checkedItems = dto.checkedItems.map { it.trim() }.filter { it.isNotEmpty() },
        writeAs = dto.writeAs?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { w ->
            WriteIdentity.entries.firstOrNull { it.name == w } ?: throw ValidationException("must be one of ${WriteIdentity.entries}", "writeAs")
        },
    )

    fun toDto(o: WriteIdentityQuery.WriteIdentityOptions) = WriteIdentityDto(
        o.defaultIdentity.name, o.systemAllowed, o.systemConnectionName, o.personalAllowed, o.personalConnectionName, o.personalProblem, o.canApprove,
    )

    // ---- CI

    fun toCommand(dto: CiExecutionRequestDto) = StartCiExecution.CiExecutionCommand(
        entrypoint = dto.entrypoint.trim(),
        context = toDomain(dto.context),
        diff = dto.diff,
        changedFiles = dto.changedFiles,
        variables = dto.variables,
    )

    fun toCommand(projectId: ProjectId, dto: CiSignalRequestDto) = SignalRun.SignalCommand(
        projectId = projectId,
        signal = dto.signal.trim(),
        status = signalStatus(dto.status),
        runId = dto.runId?.let(::WorkflowRunId),
        branch = dto.branch?.takeIf { it.isNotBlank() },
        commitSha = dto.commitSha,
        message = dto.message?.takeIf { it.isNotBlank() },
        context = toDomain(dto.context),
    )

    fun toDto(r: SignalRun.SignalResult) = CiSignalResultDto(r.runId?.value, r.resumed, r.message)

    fun toSummary(c: CiExecution): CiExecutionSummaryDto = CiExecutionSummaryDto(
        id = c.id.value, projectId = c.projectId.value, projectKey = c.projectKey, entrypoint = c.entrypoint, mode = c.mode.name, status = c.status.name,
        gateResult = c.gateResult.name, runId = c.runId?.value, context = toDto(c.context), cost = money(c.cost), createdAt = c.createdAt, finishedAt = c.finishedAt,
        repositoryKey = c.repositoryKey,
    )

    fun toDto(c: CiExecution): CiExecutionDto = CiExecutionDto(
        id = c.id.value, projectId = c.projectId.value, projectKey = c.projectKey, entrypoint = c.entrypoint, mode = c.mode.name, status = c.status.name,
        gateResult = c.gateResult.name, runId = c.runId?.value, context = toDto(c.context), cost = money(c.cost), createdAt = c.createdAt, finishedAt = c.finishedAt,
        summary = c.summary, report = c.report?.let(::toDto), diffArtifactId = c.diffArtifactId?.value, changedFiles = c.changedFiles, variables = c.variables, error = c.error,
        repositoryKey = c.repositoryKey,
    )

    // ---- settings

    fun toDto(s: IntegrationsStatus.Status): IntegrationsStatusDto = IntegrationsStatusDto(
        jira = EndpointStatusDto(s.jira.configured, s.jira.url, s.jira.legacy, s.jira.platformConnections),
        runner = RunnerStatusDto(s.runner.mode, s.runner.image, s.runner.namespace),
        langfuse = LangfuseStatusDto(s.langfuse.enabled, s.langfuse.url),
        ci = CiStatusDto(s.ci.scriptUrl, s.ci.version),
        providers = s.providers.map { ProviderStatusDto(it.provider.name, it.configured, it.baseUrl) },
    )

    fun toDto(o: IntegrationTestOutcome): IntegrationTestResultDto = IntegrationTestResultDto(o.ok, o.message, o.latencyMs)
}
