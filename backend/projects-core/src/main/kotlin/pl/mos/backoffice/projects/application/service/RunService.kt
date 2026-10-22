package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.CancelRun
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.`in`.RetryRun
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.WorkflowRunStarted
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.projects.application.service.tasks.JiraStepCheck
import pl.mos.backoffice.projects.application.service.tasks.RepositoryStepCheck
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant

/**
 * Implements [StartRun] and [RetryRun] (`run.start`), [CancelRun] (`run.cancel`) and [RunQueries]
 * (`project.view`, lists filtered). The caller is stamped as `startedBy`; execution itself happens on the
 * engine's threads with system permissions.
 */
class RunService(
    private val runs: WorkflowRunRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val projects: ProjectRepository,
    private val logs: RunLogRepository,
    private val engine: WorkflowEngine,
    private val progress: RecordRunProgress,
    private val validator: WorkflowValidator,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    /** Refuses runs whose Jira steps have no usable connection (ADR 0030 amendment); null = not checked. */
    private val jiraSteps: JiraStepCheck? = null,
    /** Refuses runs whose runner steps have no usable repository (ADR 0034); null = not checked. */
    private val repositorySteps: RepositoryStepCheck? = null,
    /** Refuses runs with Spec Kit steps when Spec Kit is off for the project or the platform (ADR 0036); null = not checked. */
    private val specKitSteps: pl.mos.backoffice.projects.application.service.tasks.SpecKitStepCheck? = null,
    /** ADR 0050: refuses runs whose step has no model the starting user can use; null = not checked. */
    private val modelSteps: pl.mos.backoffice.projects.application.service.tasks.ModelStepCheck? = null,
) : StartRun, CancelRun, RetryRun, RunQueries {

    override fun start(workflowId: WorkflowDefinitionId, input: RunInput, source: TriggerSource): WorkflowRun {
        val definition = loadDefinition(workflowId)
        val actor = gate.require(ProjectsPermissions.RUN_START, definition.projectId.value).actor
        return launch(definition, RunTrigger(source), input, emptyList(), actor)
    }

    /**
     * Starts a CI-triggered run for the CI token of the workflow's project (`ci.execute`); [initialArtifacts]
     * (the uploaded diff) are attached before the engine starts. The CI token is the run's `startedBy`.
     */
    fun startForCi(workflowId: WorkflowDefinitionId, input: RunInput, executionId: CiExecutionId, initialArtifacts: List<NewArtifact>): WorkflowRun {
        val definition = loadDefinition(workflowId)
        val actor = gate.require(ProjectsPermissions.CI_EXECUTE, definition.projectId.value).actor
        return launch(definition, RunTrigger(TriggerSource.CI, executionId), input, initialArtifacts, actor)
    }

    /**
     * Starts a run for an issue found by the Jira intake watcher (ADR 0033): trigger `JIRA`, `startedBy` the watcher
     * (a system actor), audited `RUN_START` like every start. [dedupeKey] is recorded in the audit details.
     */
    @SystemOperation("Jira intake watcher on a worker; the watcher was configured by a holder of project.manage")
    fun startForJira(workflowId: WorkflowDefinitionId, issueKey: String, dedupeKey: String): WorkflowRun {
        val definition = loadDefinition(workflowId)
        return launch(definition, RunTrigger(TriggerSource.JIRA), RunInput(issueKey = issueKey), emptyList(), JIRA_INTAKE_ACTOR, mapOf("intakeDedupeKey" to dedupeKey))
    }

    private fun launch(
        definition: WorkflowDefinition,
        trigger: RunTrigger,
        input: RunInput,
        initialArtifacts: List<NewArtifact>,
        actor: Actor,
        auditDetails: Map<String, Any?> = emptyMap(),
    ): WorkflowRun {
        if (!definition.enabled) throw InvalidStateException("workflow ${definition.name} is disabled")
        if (definition.draft) throw InvalidStateException("workflow ${definition.name} is a draft with validation errors; fix them and save it before starting runs")
        requireRunnable(definition)
        val project = projects.findById(definition.projectId) ?: throw NotFoundException("Project", definition.projectId)
        specKitSteps?.runBlockers(project, definition.nodes)?.takeIf { it.isNotEmpty() }?.let { blockers ->
            throw InvalidStateException("workflow ${definition.name} cannot start: " + blockers.joinToString("; "))
        }
        jiraSteps?.runBlockers(project, definition.nodes, actor)?.takeIf { it.isNotEmpty() }?.let { blockers ->
            throw InvalidStateException(
                "workflow ${definition.name} cannot start: " + blockers.joinToString("; ") +
                    " (manual: ${JiraStepCheck.CONNECTIONS_GUIDE})",
            )
        }
        repositorySteps?.runBlockers(project, definition.nodes)?.takeIf { it.isNotEmpty() }?.let { blockers ->
            throw InvalidStateException("workflow ${definition.name} cannot start: " + blockers.joinToString("; ") + " (manual: $REPOSITORIES_GUIDE)")
        }
        modelSteps?.runBlockers(project, definition.nodes, actor.userId)?.takeIf { it.isNotEmpty() }?.let { blockers ->
            throw InvalidStateException("workflow ${definition.name} cannot start: " + blockers.joinToString("; ") + " (manual: $MODELS_GUIDE)")
        }
        val now = clock.now()
        var run = WorkflowRun.create(project, definition, trigger, input, now, startedBy = actor).start(now)
        val startNodeId = definition.startNode()?.id ?: throw InvalidStateException("workflow ${definition.name} has no START node")
        initialArtifacts.forEach { a ->
            run = run.addArtifact(Artifact(ArtifactId.new(), startNodeId, a.name, a.kind, a.mediaType, a.content, createdAt = now))
        }
        val saved = runs.save(run)
        audit.record(
            AuditEntry(
                "RUN_START", ENTITY, saved.id.toString(), projectId = saved.projectId.value,
                details = mapOf(
                    "workflowId" to definition.id.toString(), "workflowName" to definition.name, "workflowVersion" to definition.version,
                    "trigger" to trigger.type.name, "issueKey" to saved.issueKey, "ciExecutionId" to trigger.ciExecutionId?.toString(),
                ) + auditDetails,
            ),
        )
        events.publish(WorkflowRunStarted(saved.id, saved.projectId, saved.workflowName, trigger.type.name, saved.issueKey, now))
        engine.start(saved)
        return saved
    }

    override fun cancel(runId: WorkflowRunId): WorkflowRun {
        val run = load(runId)
        gate.require(ProjectsPermissions.RUN_CANCEL, run.projectId.value)
        if (run.status.terminal) throw InvalidStateException("run $runId is already ${run.status}")
        engine.cancel(runId)
        val cancelled = progress.finished(runId, RunStatus.CANCELLED)
        audit.record(AuditEntry("RUN_CANCEL", ENTITY, runId.toString(), projectId = run.projectId.value, details = mapOf("previousStatus" to run.status.name)))
        return cancelled
    }

    override fun retry(runId: WorkflowRunId): WorkflowRun {
        val run = load(runId)
        gate.require(ProjectsPermissions.RUN_START, run.projectId.value)
        if (run.status != RunStatus.FAILED) throw InvalidStateException("run $runId is ${run.status}; only FAILED runs can be retried")
        val fromNode = run.retryNodeId() ?: throw InvalidStateException("run $runId has no node to retry from")
        val retried = runs.save(run.retry(clock.now()))
        audit.record(AuditEntry("RUN_RETRY", ENTITY, runId.toString(), projectId = run.projectId.value, details = mapOf("fromNode" to fromNode)))
        engine.retry(retried, fromNode)
        return retried
    }

    override fun get(runId: WorkflowRunId): WorkflowRun {
        val run = load(runId)
        gate.require(ProjectsPermissions.PROJECT_VIEW, run.projectId.value)
        return run
    }

    @PublicOperation("lists only runs of projects the caller holds project.view on")
    override fun list(filter: RunFilter, page: Int, size: Int): PageOf<WorkflowRun> =
        runs.find(filter, gate.projectFilter(ProjectsPermissions.PROJECT_VIEW), page, size)

    override fun artifacts(runId: WorkflowRunId): List<Artifact> {
        val run = load(runId)
        gate.require(ProjectsPermissions.PROJECT_VIEW, run.projectId.value)
        return run.artifacts.sortedBy { it.createdAt }
    }

    override fun artifact(runId: WorkflowRunId, artifactId: ArtifactId): Artifact {
        val run = load(runId)
        gate.require(ProjectsPermissions.PROJECT_VIEW, run.projectId.value)
        return run.artifact(artifactId) ?: throw NotFoundException("Artifact", artifactId)
    }

    override fun logs(runId: WorkflowRunId, nodeId: String?, after: Instant?): List<RunLogLine> {
        val run = load(runId)
        gate.require(ProjectsPermissions.PROJECT_VIEW, run.projectId.value)
        return logs.find(runId, nodeId, after)
    }

    private fun load(runId: WorkflowRunId): WorkflowRun = runs.findById(runId) ?: throw NotFoundException("Run", runId)

    private fun loadDefinition(workflowId: WorkflowDefinitionId): WorkflowDefinition =
        workflows.findById(workflowId) ?: throw NotFoundException("Workflow", workflowId)

    /** Structural sanity check before running (ports/expressions are checked at design time). */
    private fun requireRunnable(definition: WorkflowDefinition) {
        val result = validator.validate(definition.name, definition.nodes, definition.edges)
        if (!result.valid) throw ValidationException(result.errors.map { ValidationException.FieldError(it.nodeId ?: it.edgeId, it.message) })
    }

    companion object {
        const val ENTITY = "WorkflowRun"

        /** `startedBy` of runs started by the Jira intake watcher (ADR 0033). */
        val JIRA_INTAKE_ACTOR = Actor(PrincipalKind.SYSTEM, null, "Jira intake watcher")

        /** Manual section on project repositories and step repositories (ADR 0034). */
        const val REPOSITORIES_GUIDE = "workflows#step-repositories"
        const val MODELS_GUIDE = "evaluation#model-availability"
    }
}
