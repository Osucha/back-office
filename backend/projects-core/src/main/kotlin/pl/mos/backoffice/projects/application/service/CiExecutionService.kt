package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.CiExecutionQueries
import pl.mos.backoffice.projects.application.port.`in`.CompleteCiExecution
import pl.mos.backoffice.projects.application.port.`in`.StartCiExecution
import pl.mos.backoffice.projects.application.port.`in`.StartCiExecution.CiExecutionCommand
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.event.CiExecutionFinished
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainException
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.projects.domain.service.RepositoryUrls
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Duration

/**
 * Implements [CompleteCiExecution]: when a CI-triggered run finishes, derives status, gate result,
 * summary and report from it. Kept apart from [CiExecutionService] so the engine -> progress ->
 * completion chain does not depend on run starting (which depends on the engine).
 */
class CiExecutionCompletionService(
    private val executions: CiExecutionRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : CompleteCiExecution {

    @SystemOperation("workflow engine callback when a CI-triggered run finishes")
    override fun runFinished(run: WorkflowRun) {
        val executionId = run.trigger.ciExecutionId ?: return
        val execution = executions.findById(executionId) ?: executions.findByRunId(run.id) ?: return
        if (execution.finished) return
        val finished = executions.save(execution.finishedWith(run, clock.now()))
        events.publish(CiExecutionFinished(finished.id, finished.projectId, finished.entrypoint, finished.status.name, finished.gateResult.name, finished.runId, clock.now()))
    }
}

/**
 * Implements [StartCiExecution] (`ci.execute`, held only by the project's CI tokens) and
 * [CiExecutionQueries] (docs/architecture/05-integrations.md): entrypoint resolution, 24 h idempotency,
 * diff as artefact `diff`, run start with `trigger = CI`. Starts are audited as `CI_EXECUTION_START`.
 */
class CiExecutionService(
    private val executions: CiExecutionRepository,
    private val projects: ProjectRepository,
    private val runService: RunService,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : StartCiExecution, CiExecutionQueries {

    override fun start(projectId: ProjectId, command: CiExecutionCommand): CiExecution {
        gate.require(ProjectsPermissions.CI_EXECUTE, projectId.value)
        val project = projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        val entrypoint = project.entrypoint(command.entrypoint) ?: throw NotFoundException("CiEntrypoint", command.entrypoint)
        // ADR 0034: which project repository the CI job runs in (unknown URL -> 400 listing the repositories)
        val repository = RepositoryUrls.match(project, command.context)
        val restricted = entrypoint.repository
        if (restricted != null && repository?.key != restricted) {
            throw ValidationException(
                "entrypoint ${entrypoint.key} may only be started from repository $restricted (${project.repository(restricted)?.url ?: "unknown"}); " +
                    "this CI job runs in " + (repository?.let { "${it.key} (${it.url})" } ?: (command.context.repositoryUrl?.let(RepositoryUrls::redact) ?: "an unidentified repository (update backoffice-ci.sh to 2.1 or later)")),
                "entrypoint",
            )
        }
        val now = clock.now()
        val force = command.variables["force"]?.equals("true", ignoreCase = true) ?: false
        val key = CiExecution.idempotencyKey(project.id, entrypoint.key, command.context.commitSha, command.context.mergeRequest?.iid)
        if (!force) executions.findRecentByKey(key, now.minus(IDEMPOTENCY_WINDOW))?.let { return it }

        var execution = executions.save(
            CiExecution(
                id = CiExecutionId.new(),
                projectId = project.id,
                projectKey = project.key,
                entrypoint = entrypoint.key,
                mode = entrypoint.mode,
                context = command.context,
                variables = command.variables,
                changedFiles = command.changedFiles,
                createdAt = now,
                repositoryKey = repository?.key,
            ),
        )
        val diff = command.diff?.takeIf { it.isNotBlank() }?.let { truncate(it) }
        val input = RunInput(
            issueKey = command.variables["issueKey"]?.takeIf { it.isNotBlank() },
            parameters = command.variables + mapOf("entrypoint" to entrypoint.key, "changedFiles" to command.changedFiles) +
                (repository?.let { mapOf("repository" to it.key) } ?: emptyMap()),
            ci = command.context,
        )
        execution = try {
            val run = runService.startForCi(
                entrypoint.workflowId, input, execution.id,
                listOfNotNull(diff?.let { NewArtifact("diff", ArtifactKind.DIFF, "text/x-diff", it) }),
            )
            executions.save(execution.running(run.id, run.artifactNamed("diff")?.id))
        } catch (e: DomainException) {
            val failed = executions.save(execution.failed(e.message ?: e.javaClass.simpleName, clock.now()))
            events.publish(CiExecutionFinished(failed.id, failed.projectId, failed.entrypoint, failed.status.name, failed.gateResult.name, null, clock.now()))
            failed
        }
        audit.record(
            AuditEntry(
                "CI_EXECUTION_START", ENTITY, execution.id.toString(), projectId = project.id.value,
                details = mapOf(
                    "entrypoint" to entrypoint.key, "status" to execution.status.name, "runId" to execution.runId?.toString(),
                    "commitSha" to command.context.commitSha, "mergeRequest" to command.context.mergeRequest?.iid, "forced" to force,
                    "repository" to repository?.key,
                ),
            ),
        )
        return execution
    }

    override fun get(id: CiExecutionId): CiExecution {
        val execution = executions.findById(id) ?: throw NotFoundException("CiExecution", id)
        // the CI job polls with its token (ci.execute on its own project); people need project.view
        val permission = if (gate.currentUser().kind == PrincipalKind.CI_TOKEN) ProjectsPermissions.CI_EXECUTE else ProjectsPermissions.PROJECT_VIEW
        gate.require(permission, execution.projectId.value)
        return execution
    }

    @PublicOperation("lists only CI executions of projects the caller holds project.view on")
    override fun list(projectId: ProjectId?, page: Int, size: Int): PageOf<CiExecution> =
        executions.find(projectId, gate.projectFilter(ProjectsPermissions.PROJECT_VIEW), page, size)

    private fun truncate(diff: String): String {
        val bytes = diff.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_DIFF_BYTES) return diff
        return String(bytes, 0, MAX_DIFF_BYTES, Charsets.UTF_8) + "\n\n[diff truncated by Back Office at $MAX_DIFF_BYTES bytes]"
    }

    companion object {
        const val ENTITY = "CiExecution"
        val IDEMPOTENCY_WINDOW: Duration = Duration.ofHours(24)
        const val MAX_DIFF_BYTES = 2 * 1024 * 1024 - 4096
    }
}
