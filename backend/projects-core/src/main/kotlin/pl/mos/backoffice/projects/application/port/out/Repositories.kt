package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant

interface ProjectRepository {
    fun save(project: Project): Project
    fun findById(id: ProjectId): Project?
    fun findByKey(key: String): Project?
    /** Projects visible through [projects] (`ProjectFilter.All` for internal callers). */
    fun findAll(projects: ProjectFilter): List<Project>
    fun delete(id: ProjectId)
}

interface ProjectCiTokenRepository {
    fun save(token: ProjectCiToken): ProjectCiToken
    fun findById(id: ProjectCiTokenId): ProjectCiToken?
    fun findByHash(tokenHash: String): ProjectCiToken?
    fun findByProjectId(projectId: ProjectId): List<ProjectCiToken>
    fun deleteByProjectId(projectId: ProjectId)
}

interface WorkflowDefinitionRepository {
    fun save(definition: WorkflowDefinition): WorkflowDefinition
    fun findById(id: WorkflowDefinitionId): WorkflowDefinition?
    fun findByProjectId(projectId: ProjectId): List<WorkflowDefinition>
    fun countByProjectId(projectId: ProjectId): Int
    fun delete(id: WorkflowDefinitionId)
    fun deleteByProjectId(projectId: ProjectId)
}

data class RunFilter(
    val projectId: ProjectId? = null,
    val workflowId: WorkflowDefinitionId? = null,
    val statuses: Set<RunStatus> = emptySet(),
)

interface WorkflowRunRepository {
    fun save(run: WorkflowRun): WorkflowRun
    fun findById(id: WorkflowRunId): WorkflowRun?
    fun find(filter: RunFilter, projects: ProjectFilter, page: Int, size: Int): PageOf<WorkflowRun>
    fun findByStatus(statuses: Set<RunStatus>): List<WorkflowRun>
    /** `WAITING` runs of a project, newest first (used for CI signal matching and timeouts). */
    fun findWaiting(projectId: ProjectId?): List<WorkflowRun>
    fun findRecent(limit: Int, projects: ProjectFilter): List<WorkflowRun>
    fun countByStatus(statuses: Set<RunStatus>, projects: ProjectFilter): Int
    fun countWaitingForCi(projects: ProjectFilter): Int
    fun countByProjectAndStatus(projectId: ProjectId, statuses: Set<RunStatus>): Int
    fun countFinishedSince(status: RunStatus, since: Instant, projects: ProjectFilter): Int
    fun existsActiveByWorkflow(workflowId: WorkflowDefinitionId): Boolean
    fun deleteByProjectId(projectId: ProjectId)
}

interface ApprovalRequestRepository {
    fun save(request: ApprovalRequest): ApprovalRequest
    fun findById(id: ApprovalRequestId): ApprovalRequest?
    fun findByStatus(status: ApprovalStatus, projects: ProjectFilter, limit: Int? = null): List<ApprovalRequest>
    fun findOpenByRunId(runId: WorkflowRunId): ApprovalRequest?
    fun countOpen(projects: ProjectFilter): Int
    fun countOpenByProject(projectId: ProjectId): Int
    fun deleteByProjectId(projectId: ProjectId)
}

interface CiExecutionRepository {
    fun save(execution: CiExecution): CiExecution
    fun findById(id: CiExecutionId): CiExecution?
    fun findByRunId(runId: WorkflowRunId): CiExecution?
    /** Newest execution with the same idempotency key created after [since]. */
    fun findRecentByKey(idempotencyKey: String, since: Instant): CiExecution?
    fun find(projectId: ProjectId?, projects: ProjectFilter, page: Int, size: Int): PageOf<CiExecution>
    fun findRecent(limit: Int, projects: ProjectFilter): List<CiExecution>
    fun countByProjectSince(projectId: ProjectId, since: Instant): Int
    fun deleteByProjectId(projectId: ProjectId)
}

enum class LogLevel { INFO, WARN, ERROR }

data class RunLogLine(val runId: WorkflowRunId, val nodeId: String?, val at: Instant, val level: LogLevel, val message: String)

interface RunLogRepository {
    fun append(line: RunLogLine)
    fun find(runId: WorkflowRunId, nodeId: String?, after: Instant?): List<RunLogLine>
    fun deleteByRunIds(runIds: Collection<WorkflowRunId>)
}
