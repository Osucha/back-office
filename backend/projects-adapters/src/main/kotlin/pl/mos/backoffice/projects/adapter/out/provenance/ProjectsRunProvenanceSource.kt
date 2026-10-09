package pl.mos.backoffice.projects.adapter.out.provenance

import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.adapter.out.persistence.ApprovalRequestEntity
import pl.mos.backoffice.projects.adapter.out.persistence.ApprovalRequestJpaRepository
import pl.mos.backoffice.projects.adapter.out.persistence.PersistenceMapper
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.shared.provenance.ProvenanceRepository
import pl.mos.backoffice.shared.provenance.ProvenanceRepositoryResult
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.provenance.ProvenanceArtifact
import pl.mos.backoffice.shared.provenance.ProvenanceGateDecision
import pl.mos.backoffice.shared.provenance.ProvenanceNode
import pl.mos.backoffice.shared.provenance.RunProvenance
import pl.mos.backoffice.shared.provenance.RunProvenanceSource
import java.security.MessageDigest
import java.util.UUID

/**
 * Implements the shared [RunProvenanceSource] port for the Governance context (ADR 0025): the run, its node
 * executions, decided gates and artefact digests. A system read without permission checks; the governance
 * use cases check `project.view`.
 */
@Component
@Transactional(readOnly = true)
class ProjectsRunProvenanceSource(
    private val runs: WorkflowRunRepository,
    private val projects: ProjectRepository,
    private val approvals: ApprovalRequestJpaRepository,
    private val mapper: PersistenceMapper,
) : RunProvenanceSource {

    override fun find(runId: UUID): RunProvenance? {
        val run = runs.findById(WorkflowRunId(runId)) ?: return null
        val project = projects.findById(run.projectId)
        val byRun = Specification<ApprovalRequestEntity> { root, _, cb -> cb.equal(root.get<UUID>("runId"), runId) }
        val decisions = approvals.findAll(byRun)
            .map(mapper::toDomain)
            .filter { it.status == ApprovalStatus.DECIDED && it.decision != null }
            .map {
                val d = it.decision!!
                ProvenanceGateDecision(it.id.value, it.nodeId, d.option, d.comment, d.checkedItems, d.decidedBy, d.decidedAt)
            }
            .sortedBy { it.decidedAt }
        return toProvenance(run, project, decisions)
    }

    private fun toProvenance(run: WorkflowRun, project: Project?, decisions: List<ProvenanceGateDecision>) = RunProvenance(
        runId = run.id.value,
        projectId = run.projectId.value,
        projectKey = run.projectKey,
        repositoryUrl = project?.primaryRepository?.url,
        repositories = project?.repositories.orEmpty().map { ProvenanceRepository(it.key, it.url, it.purpose.name, it.primary) },
        workflowId = run.workflowId.value,
        workflowName = run.workflowName,
        workflowVersion = run.workflowVersion,
        issueKey = run.issueKey,
        status = run.status.name,
        outcome = run.outcome?.name,
        terminal = run.status.terminal,
        startedBy = run.startedBy,
        createdAt = run.createdAt,
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        cost = run.cost,
        mergeRequestUrl = run.mergeRequest?.url,
        branch = run.mergeRequest?.branch,
        nodes = run.nodes.map {
            ProvenanceNode(
                executionId = it.id.value, nodeId = it.nodeId, nodeName = it.nodeName, kind = it.kind.name, templateKey = it.templateKey,
                attempt = it.attempt, status = it.status.name, port = it.port, startedAt = it.startedAt, finishedAt = it.finishedAt,
                runnerJobId = it.runnerJobId?.value, usage = it.usage, cost = it.cost, error = it.error, artifactIds = it.artifactIds.map { a -> a.value },
                repositories = it.repositories.map { r ->
                    ProvenanceRepositoryResult(r.key, r.url, r.access.name, r.branch, r.commitSha, r.mergeRequestUrl, r.pushed)
                },
            )
        },
        decisions = decisions,
        artifacts = run.artifacts.map {
            val bytes = it.content.toByteArray(Charsets.UTF_8)
            ProvenanceArtifact(it.id.value, it.nodeId, it.name, it.kind.name, it.mediaType, sha256Hex(bytes), bytes.size.toLong(), it.createdAt)
        },
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
