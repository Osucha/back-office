package pl.mos.backoffice.projects.adapter.out.persistence

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowHeader
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowRepository
import pl.mos.backoffice.projects.application.port.out.LinkedWorkflow
import pl.mos.backoffice.projects.application.port.out.LogLevel
import pl.mos.backoffice.projects.domain.model.LibraryLink
import pl.mos.backoffice.projects.domain.model.LibraryWorkflow
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowId
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
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
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.SortSpec
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Criteria helpers for list queries filtered by the caller's projects (ADR 0017): `ProjectFilter.All`
 * adds nothing, `Only(ids)` becomes `project_id IN (ids)`; an empty `Only` short-circuits to an empty
 * result without touching the database.
 */
internal object ProjectSpecs {
    fun <T : Any> visibleTo(filter: ProjectFilter): Specification<T> = when (filter) {
        ProjectFilter.All -> Specification.unrestricted()
        is ProjectFilter.Only -> Specification { root, _, _ -> root.get<UUID>("projectId").`in`(filter.projectIds) }
    }

    fun <T : Any> eq(field: String, value: Any?): Specification<T> = Specification { root, _, cb -> cb.equal(root.get<Any>(field), value) }

    fun <T : Any> within(field: String, values: Collection<Any>): Specification<T> = Specification { root, _, _ -> root.get<Any>(field).`in`(values) }

    fun <T : Any> after(field: String, since: Instant): Specification<T> = Specification { root, _, cb -> cb.greaterThan(root.get(field), since) }

    fun nothingVisible(filter: ProjectFilter): Boolean = filter is ProjectFilter.Only && filter.projectIds.isEmpty()

    /**
     * Column sorting of a list (ADR 0046) inside the query, so JSON attributes can be sorted too: [key] in the asked
     * direction with empty values (null, and '' for [text] keys) last, then newest first and the id as a stable
     * tiebreaker. Use it with an unsorted page request: Spring Data keeps the specification's order then and drops
     * it from the count query.
     */
    fun <T : Any> sorted(sort: SortSpec, text: Boolean, key: (Root<T>, CriteriaBuilder) -> Expression<*>): Specification<T> =
        Specification { root, query, cb ->
            val value = key(root, cb)
            val empty = if (text) cb.or(cb.isNull(value), cb.equal(value, "")) else cb.isNull(value)
            query.orderBy(
                cb.asc(cb.selectCase<Int>().`when`(empty, 1).otherwise(0)),
                if (sort.descending) cb.desc(value) else cb.asc(value),
                cb.desc(root.get<Instant>("createdAt")),
                cb.asc(root.get<UUID>("id")),
            )
            null
        }

    /** A text attribute of a `jsonb` column (`column->>'name'`), lower-cased for sorting. */
    fun <T : Any> jsonText(root: Root<T>, cb: CriteriaBuilder, column: String, name: String): Expression<String> =
        cb.lower(cb.function("jsonb_extract_path_text", String::class.java, root.get<String>(column), cb.literal(name)))

    /** A text attribute lower-cased for sorting. */
    fun <T : Any> lowerText(root: Root<T>, cb: CriteriaBuilder, attribute: String): Expression<String> = cb.lower(root.get<String>(attribute))
}

@Component
@Transactional
class ProjectPersistenceAdapter(private val repo: ProjectJpaRepository, private val mapper: PersistenceMapper) : ProjectRepository {
    override fun save(project: Project): Project = mapper.toDomain(repo.save(mapper.toEntity(project)))
    override fun findById(id: ProjectId): Project? = repo.findById(id.value).orElse(null)?.let(mapper::toDomain)
    override fun findByKey(key: String): Project? = repo.findByKey(key)?.let(mapper::toDomain)
    override fun findAll(projects: ProjectFilter): List<Project> = when (projects) {
        ProjectFilter.All -> repo.findAll()
        is ProjectFilter.Only -> if (projects.projectIds.isEmpty()) emptyList() else repo.findAllById(projects.projectIds)
    }.map(mapper::toDomain)
    override fun delete(id: ProjectId) = repo.deleteById(id.value)
}

@Component
@Transactional
class ProjectCiTokenPersistenceAdapter(private val repo: ProjectCiTokenJpaRepository, private val mapper: PersistenceMapper) : ProjectCiTokenRepository {
    override fun save(token: ProjectCiToken): ProjectCiToken = mapper.toDomain(repo.save(mapper.toEntity(token)))
    override fun findById(id: ProjectCiTokenId): ProjectCiToken? = repo.findById(id.value).orElse(null)?.let(mapper::toDomain)
    override fun findByHash(tokenHash: String): ProjectCiToken? = repo.findByTokenHash(tokenHash)?.let(mapper::toDomain)
    override fun findByProjectId(projectId: ProjectId): List<ProjectCiToken> = repo.findByProjectIdOrderByCreatedAtDesc(projectId.value).map(mapper::toDomain)
    override fun deleteByProjectId(projectId: ProjectId) = repo.deleteByProjectId(projectId.value)
}

@Component
@Transactional
class WorkflowDefinitionPersistenceAdapter(
    private val repo: WorkflowDefinitionJpaRepository,
    private val mapper: PersistenceMapper,
) : WorkflowDefinitionRepository {
    override fun save(definition: WorkflowDefinition): WorkflowDefinition = mapper.toDomain(repo.save(mapper.toEntity(definition)))
    override fun findById(id: WorkflowDefinitionId): WorkflowDefinition? = repo.findById(id.value).orElse(null)?.let(mapper::toDomain)
    override fun findByProjectId(projectId: ProjectId): List<WorkflowDefinition> = repo.findByProjectId(projectId.value).map(mapper::toDomain)
    override fun countByProjectId(projectId: ProjectId): Int = repo.countByProjectId(projectId.value).toInt()
    override fun delete(id: WorkflowDefinitionId) = repo.deleteById(id.value)
    override fun deleteByProjectId(projectId: ProjectId) = repo.deleteByProjectId(projectId.value)

    @Transactional(readOnly = true)
    override fun findLinkedToLibrary(libraryWorkflowId: LibraryWorkflowId?): List<LinkedWorkflow> =
        (if (libraryWorkflowId == null) repo.findLinked() else repo.findLinked(libraryWorkflowId.value)).mapNotNull { row ->
            val key = row.libraryKey ?: return@mapNotNull null
            val version = row.libraryVersion ?: return@mapNotNull null
            LinkedWorkflow(
                WorkflowDefinitionId(row.id), ProjectId(row.projectId), row.name, row.enabled,
                LibraryLink(LibraryWorkflowId(row.libraryWorkflowId), key, version, row.libraryModified),
            )
        }
}

/** Library workflows (ADR 0037): one row per entry plus one row per immutable version; saving inserts new versions only. */
@Component
@Transactional
class LibraryWorkflowPersistenceAdapter(
    private val repo: LibraryWorkflowJpaRepository,
    private val versions: LibraryWorkflowVersionJpaRepository,
    private val mapper: PersistenceMapper,
) : LibraryWorkflowRepository {
    override fun save(workflow: LibraryWorkflow): LibraryWorkflow {
        repo.save(mapper.toEntity(workflow))
        val stored = versions.findByLibraryWorkflowId(workflow.id.value).map { it.version }.toSet()
        workflow.versions.filter { it.version !in stored }.forEach { versions.save(mapper.toEntity(workflow.id, it)) }
        return findById(workflow.id)!!
    }

    @Transactional(readOnly = true)
    override fun findById(id: LibraryWorkflowId): LibraryWorkflow? =
        repo.findById(id.value).orElse(null)?.let { mapper.toDomain(it, versions.findByLibraryWorkflowId(it.id)) }

    @Transactional(readOnly = true)
    override fun findByKey(key: String): LibraryWorkflow? = repo.findByKey(key)?.let { mapper.toDomain(it, versions.findByLibraryWorkflowId(it.id)) }

    @Transactional(readOnly = true)
    override fun findAll(): List<LibraryWorkflow> {
        val entries = repo.findAll()
        if (entries.isEmpty()) return emptyList()
        val byEntry = versions.findByLibraryWorkflowIdIn(entries.map { it.id }).groupBy { it.libraryWorkflowId }
        return entries.map { mapper.toDomain(it, byEntry[it.id].orEmpty()) }
    }

    override fun delete(id: LibraryWorkflowId) {
        versions.deleteByLibraryWorkflowId(id.value)
        repo.deleteById(id.value)
    }

    @Transactional(readOnly = true)
    override fun findHeaders(ids: Collection<LibraryWorkflowId>): List<LibraryWorkflowHeader> {
        if (ids.isEmpty()) return emptyList()
        val uuids = ids.map { it.value }
        val latest = versions.latestVersions(uuids).associate { (it[0] as UUID) to (it[1] as Number).toInt() }
        return repo.findAllById(uuids).mapNotNull { e ->
            latest[e.id]?.let { LibraryWorkflowHeader(LibraryWorkflowId(e.id), e.key, e.name, it, e.deprecated) }
        }
    }
}

/**
 * Runs are stored as one row plus one row per artefact ([ArtifactStore]: content above 64 kB lives in the
 * BlobStore). `findById` loads artefacts (with content); list queries return runs without artefacts
 * because summaries do not need them.
 */
@Component
@Transactional
class WorkflowRunPersistenceAdapter(
    private val runs: WorkflowRunJpaRepository,
    private val artifacts: ArtifactStore,
    private val logs: RunLogJpaRepository,
    private val mapper: PersistenceMapper,
) : WorkflowRunRepository {

    override fun save(run: WorkflowRun): WorkflowRun {
        runs.save(mapper.toEntity(run))
        artifacts.sync(run)
        return run
    }

    override fun findById(id: WorkflowRunId): WorkflowRun? =
        runs.findById(id.value).orElse(null)?.let { mapper.toDomain(it, artifacts.load(it.id)) }

    override fun find(filter: RunFilter, projects: ProjectFilter, page: Int, size: Int): PageOf<WorkflowRun> {
        if (ProjectSpecs.nothingVisible(projects)) return PageOf(emptyList(), page, size, 0)
        val sort = filter.sort
        val pageable = if (sort == null) PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")) else PageRequest.of(page, size)
        var spec = ProjectSpecs.visibleTo<WorkflowRunEntity>(projects)
        filter.projectId?.let { spec = spec.and(ProjectSpecs.eq("projectId", it.value)) }
        filter.workflowId?.let { spec = spec.and(ProjectSpecs.eq("workflowId", it.value)) }
        if (filter.statuses.isNotEmpty()) spec = spec.and(ProjectSpecs.within("status", filter.statuses.map { it.name }))
        if (sort != null) spec = spec.and(runOrder(sort))
        val result = runs.findAll(spec, pageable)
        return PageOf(result.content.map { mapper.toDomain(it, emptyList()) }, page, size, result.totalElements)
    }

    /** [RunFilter.SORT_FIELDS] to columns: workflow name, project key and the starter's display name as shown in the table. */
    private fun runOrder(sort: SortSpec): Specification<WorkflowRunEntity> = when (sort.field) {
        "status" -> ProjectSpecs.sorted(sort, text = true) { root, _ -> root.get<String>("status") }
        "workflow" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.lowerText(root, cb, "workflowName") }
        "project" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.lowerText(root, cb, "projectKey") }
        "startedBy" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.jsonText(root, cb, "startedBy", "displayName") }
        "cost" -> ProjectSpecs.sorted(sort, text = false) { root, _ -> root.get<BigDecimal>("costAmount") }
        else -> ProjectSpecs.sorted(sort, text = false) { root, _ -> root.get<Instant>("createdAt") }
    }

    override fun findByStatus(statuses: Set<RunStatus>): List<WorkflowRun> =
        runs.findByStatusIn(statuses.map { it.name }).map { mapper.toDomain(it, emptyList()) }

    override fun findWaiting(projectId: ProjectId?): List<WorkflowRun> =
        (if (projectId == null) runs.findByStatusOrderByCreatedAtDesc(RunStatus.WAITING.name) else runs.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId.value, RunStatus.WAITING.name))
            .map { mapper.toDomain(it, artifacts.load(it.id)) }

    override fun findRecent(limit: Int, projects: ProjectFilter): List<WorkflowRun> {
        if (ProjectSpecs.nothingVisible(projects)) return emptyList()
        return runs.findAll(ProjectSpecs.visibleTo(projects), PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).content.map { mapper.toDomain(it, emptyList()) }
    }

    override fun countByStatus(statuses: Set<RunStatus>, projects: ProjectFilter): Int {
        if (ProjectSpecs.nothingVisible(projects)) return 0
        return runs.count(ProjectSpecs.visibleTo<WorkflowRunEntity>(projects).and(ProjectSpecs.within("status", statuses.map { it.name }))).toInt()
    }

    override fun countWaitingForCi(projects: ProjectFilter): Int {
        if (ProjectSpecs.nothingVisible(projects)) return 0
        return runs.count(
            ProjectSpecs.visibleTo<WorkflowRunEntity>(projects)
                .and(ProjectSpecs.eq("status", RunStatus.WAITING.name))
                .and(ProjectSpecs.eq("waitingKind", WaitKind.WAIT_FOR_CI.name)),
        ).toInt()
    }

    override fun countByProjectAndStatus(projectId: ProjectId, statuses: Set<RunStatus>): Int =
        runs.countByProjectIdAndStatusIn(projectId.value, statuses.map { it.name }).toInt()

    override fun countFinishedSince(status: RunStatus, since: Instant, projects: ProjectFilter): Int {
        if (ProjectSpecs.nothingVisible(projects)) return 0
        return runs.count(
            ProjectSpecs.visibleTo<WorkflowRunEntity>(projects).and(ProjectSpecs.eq("status", status.name)).and(ProjectSpecs.after("finishedAt", since)),
        ).toInt()
    }

    override fun existsActiveByWorkflow(workflowId: WorkflowDefinitionId): Boolean =
        runs.existsByWorkflowIdAndStatusIn(workflowId.value, listOf(RunStatus.PENDING.name, RunStatus.RUNNING.name, RunStatus.WAITING.name))

    override fun deleteByProjectId(projectId: ProjectId) {
        val ids = runs.idsByProjectId(projectId.value)
        if (ids.isEmpty()) return
        logs.deleteByRunIdIn(ids)
        artifacts.deleteByRunIds(ids)
        runs.deleteByProjectId(projectId.value)
    }
}

@Component
@Transactional
class ApprovalRequestPersistenceAdapter(
    private val repo: ApprovalRequestJpaRepository,
    private val mapper: PersistenceMapper,
) : ApprovalRequestRepository {
    override fun save(request: ApprovalRequest): ApprovalRequest = mapper.toDomain(repo.save(mapper.toEntity(request)))
    override fun findById(id: ApprovalRequestId): ApprovalRequest? = repo.findById(id.value).orElse(null)?.let(mapper::toDomain)
    override fun findByStatus(status: ApprovalStatus, projects: ProjectFilter, limit: Int?): List<ApprovalRequest> {
        if (ProjectSpecs.nothingVisible(projects)) return emptyList()
        val spec = ProjectSpecs.visibleTo<ApprovalRequestEntity>(projects).and(ProjectSpecs.eq("status", status.name))
        val sort = Sort.by(Sort.Direction.DESC, "createdAt")
        val rows = if (limit == null) repo.findAll(spec, sort) else repo.findAll(spec, PageRequest.of(0, limit, sort)).content
        return rows.map(mapper::toDomain)
    }
    override fun findOpenByRunId(runId: WorkflowRunId): ApprovalRequest? = repo.findFirstByRunIdAndStatus(runId.value, ApprovalStatus.OPEN.name)?.let(mapper::toDomain)
    override fun countOpen(projects: ProjectFilter): Int {
        if (ProjectSpecs.nothingVisible(projects)) return 0
        return repo.count(ProjectSpecs.visibleTo<ApprovalRequestEntity>(projects).and(ProjectSpecs.eq("status", ApprovalStatus.OPEN.name))).toInt()
    }
    override fun countOpenByProject(projectId: ProjectId): Int = repo.countByProjectIdAndStatus(projectId.value, ApprovalStatus.OPEN.name).toInt()
    override fun deleteByProjectId(projectId: ProjectId) = repo.deleteByProjectId(projectId.value)
}

@Component
@Transactional
class CiExecutionPersistenceAdapter(private val repo: CiExecutionJpaRepository, private val mapper: PersistenceMapper) : CiExecutionRepository {
    override fun save(execution: CiExecution): CiExecution = mapper.toDomain(repo.save(mapper.toEntity(execution)))
    override fun findById(id: CiExecutionId): CiExecution? = repo.findById(id.value).orElse(null)?.let(mapper::toDomain)
    override fun findByRunId(runId: WorkflowRunId): CiExecution? = repo.findFirstByRunId(runId.value)?.let(mapper::toDomain)
    override fun findRecentByKey(idempotencyKey: String, since: Instant): CiExecution? =
        repo.findFirstByIdempotencyKeyAndCreatedAtAfterOrderByCreatedAtDesc(idempotencyKey, since)?.let(mapper::toDomain)
    override fun find(projectId: ProjectId?, projects: ProjectFilter, page: Int, size: Int, sort: SortSpec?): PageOf<CiExecution> {
        if (ProjectSpecs.nothingVisible(projects)) return PageOf(emptyList(), page, size, 0)
        val pageable = if (sort == null) PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")) else PageRequest.of(page, size)
        var spec = ProjectSpecs.visibleTo<CiExecutionEntity>(projects)
        projectId?.let { spec = spec.and(ProjectSpecs.eq("projectId", it.value)) }
        if (sort != null) spec = spec.and(executionOrder(sort))
        val result = repo.findAll(spec, pageable)
        return PageOf(result.content.map(mapper::toDomain), page, size, result.totalElements)
    }

    /** `CiExecutionQueries.SORT_FIELDS` to columns: `ref` is the `ref` of the stored CI context (`context->>'ref'`). */
    private fun executionOrder(sort: SortSpec): Specification<CiExecutionEntity> = when (sort.field) {
        "status" -> ProjectSpecs.sorted(sort, text = true) { root, _ -> root.get<String>("status") }
        "entrypoint" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.lowerText(root, cb, "entrypoint") }
        "ref" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.jsonText(root, cb, "context", "ref") }
        "project" -> ProjectSpecs.sorted(sort, text = true) { root, cb -> ProjectSpecs.lowerText(root, cb, "projectKey") }
        "cost" -> ProjectSpecs.sorted(sort, text = false) { root, _ -> root.get<BigDecimal>("costAmount") }
        else -> ProjectSpecs.sorted(sort, text = false) { root, _ -> root.get<Instant>("createdAt") }
    }
    override fun findRecent(limit: Int, projects: ProjectFilter): List<CiExecution> {
        if (ProjectSpecs.nothingVisible(projects)) return emptyList()
        return repo.findAll(ProjectSpecs.visibleTo(projects), PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).content.map(mapper::toDomain)
    }
    override fun countByProjectSince(projectId: ProjectId, since: Instant): Int = repo.countByProjectIdAndCreatedAtAfter(projectId.value, since).toInt()
    override fun deleteByProjectId(projectId: ProjectId) = repo.deleteByProjectId(projectId.value)
}

@Component
@Transactional
class RunLogPersistenceAdapter(private val repo: RunLogJpaRepository) : RunLogRepository {
    override fun append(line: RunLogLine) {
        repo.save(RunLogEntity(runId = line.runId.value, nodeId = line.nodeId, at = line.at, level = line.level.name, message = line.message))
    }

    override fun find(runId: WorkflowRunId, nodeId: String?, after: Instant?): List<RunLogLine> {
        val rows = when {
            nodeId != null && after != null -> repo.findByRunIdAndNodeIdAndAtAfterOrderByAtAscIdAsc(runId.value, nodeId, after)
            nodeId != null -> repo.findByRunIdAndNodeIdOrderByAtAscIdAsc(runId.value, nodeId)
            after != null -> repo.findByRunIdAndAtAfterOrderByAtAscIdAsc(runId.value, after)
            else -> repo.findByRunIdOrderByAtAscIdAsc(runId.value)
        }
        return rows.map { RunLogLine(WorkflowRunId(it.runId), it.nodeId, it.at, LogLevel.valueOf(it.level), it.message) }
    }

    override fun deleteByRunIds(runIds: Collection<WorkflowRunId>) {
        if (runIds.isNotEmpty()) repo.deleteByRunIdIn(runIds.map { it.value })
    }
}
