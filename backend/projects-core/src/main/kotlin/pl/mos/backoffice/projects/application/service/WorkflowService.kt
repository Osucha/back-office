package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.TaskConfigSchemas
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.ExpressionCheck
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.Palette
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.PaletteTask
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowRepository
import pl.mos.backoffice.projects.domain.model.LibraryLink
import pl.mos.backoffice.projects.domain.service.LibraryPortability
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.AgentAuditReport
import pl.mos.backoffice.projects.domain.service.AgentAuditor
import pl.mos.backoffice.projects.domain.service.AuditedStep
import pl.mos.backoffice.projects.domain.service.ControlNodeCatalog
import pl.mos.backoffice.projects.domain.service.ExpressionValidator
import pl.mos.backoffice.projects.domain.service.TaskPortsProvider
import pl.mos.backoffice.projects.domain.service.TaskTemplateInfo
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.application.service.tasks.JiraStepCheck
import pl.mos.backoffice.projects.application.service.tasks.ModelStepCheck
import pl.mos.backoffice.projects.application.service.tasks.RepositoryStepCheck
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.projects.domain.service.WorkflowValidationResult
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.projects.domain.service.RunInputs
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Implements [ManageWorkflows]: validated create/update (version bump), duplicate, delete guarded by
 * active runs (all `workflow.edit`, audited), the designer palette, validation (`project.view`) and
 * expression checks. TASK ports and template existence come from the `TaskCatalog` port.
 */
class WorkflowService(
    private val workflows: WorkflowDefinitionRepository,
    private val projects: ProjectRepository,
    private val runs: WorkflowRunRepository,
    private val validator: WorkflowValidator,
    private val taskCatalog: TaskCatalog,
    private val expressions: ExpressionEvaluator,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    /** Checks that Jira steps have a connection (ADR 0030); null = not checked (tests without connections). */
    jira: JiraConnectionAccess? = null,
    /** ADR 0037: recomputes the `modified` flag of linked copies on save; null = links are kept as they are (tests). */
    private val library: LibraryWorkflowRepository? = null,
    /** ADR 0043: library sources steps may attach; null = none (tests). */
    librarySources: LibrarySourceRepository? = null,
    /** ADR 0050: which models the caller may use; null = models are not checked (tests). */
    private val availability: ModelAvailability? = null,
    /** ADR 0050 step 2: the default models after the project defaults; null = none. */
    defaults: ModelDefaults? = null,
    /** ADR 0064: connectors the agent audit checks agents' keys against; null = not checked (tests). */
    private val connectors: pl.mos.backoffice.projects.application.port.out.ConnectorRepository? = null,
) : ManageWorkflows {

    private val jiraSteps = jira?.let { JiraStepCheck(it) }
    private val repositorySteps = RepositoryStepCheck(taskCatalog, librarySources)
    private val modelSteps = availability?.let { ModelStepCheck(taskCatalog, it, defaults) }

    override fun create(projectId: ProjectId, command: WorkflowCommand): WorkflowDefinition {
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, projectId.value).actor
        projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        // a workflow with errors is kept as a draft (no runs, no publishing) instead of being refused
        val draft = !check(projectId, command).valid
        val now = clock.now()
        val saved = workflows.save(
            WorkflowDefinition(
                id = WorkflowDefinitionId.new(),
                projectId = projectId,
                name = command.name,
                description = command.description,
                version = 1,
                nodes = command.nodes,
                edges = command.edges,
                triggers = command.triggers,
                enabled = command.enabled,
                canvas = command.canvas,
                setupCommand = command.setupCommand?.takeIf { it.isNotBlank() },
                sources = command.sources,
                createdAt = now,
                updatedAt = now,
                updatedBy = actor,
                draft = draft,
            ),
        )
        audit.record(
            AuditEntry("WORKFLOW_CREATE", ENTITY, saved.id.toString(), projectId = projectId.value, details = mapOf("name" to saved.name, "nodes" to saved.nodes.size, "draft" to draft)),
        )
        return saved
    }

    override fun update(id: WorkflowDefinitionId, command: WorkflowCommand): WorkflowDefinition {
        val existing = load(id)
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, existing.projectId.value).actor
        val draft = !check(existing.projectId, command).valid
        val next = existing.newVersion(command.name, command.description, command.nodes, command.edges, command.triggers, command.enabled, command.canvas, clock.now(), actor, command.setupCommand, command.sources)
            .copy(draft = draft)
        val saved = workflows.save(next.withLibrary(refreshedLink(next)))
        audit.record(
            AuditEntry(
                "WORKFLOW_UPDATE", ENTITY, id.toString(), projectId = existing.projectId.value,
                details = AuditableChanges.changed(
                    "name" to (existing.name to saved.name),
                    "enabled" to (existing.enabled to saved.enabled),
                    "version" to (existing.version to saved.version),
                    "nodes" to (existing.nodes.size to saved.nodes.size),
                    "setupCommand" to (existing.setupCommand to saved.setupCommand),
                    "sources" to (existing.sources.map { it.key } to saved.sources.map { it.key }),
                    "draft" to (existing.draft to saved.draft),
                ),
            ),
        )
        return saved
    }

    override fun delete(id: WorkflowDefinitionId) {
        val existing = load(id)
        gate.require(ProjectsPermissions.WORKFLOW_EDIT, existing.projectId.value)
        if (runs.existsActiveByWorkflow(id)) throw InvalidStateException("workflow $id has active runs; cancel them first")
        workflows.delete(id)
        audit.record(AuditEntry("WORKFLOW_DELETE", ENTITY, id.toString(), projectId = existing.projectId.value, details = mapOf("name" to existing.name, "version" to existing.version)))
    }

    override fun get(id: WorkflowDefinitionId): WorkflowDefinition {
        val definition = load(id)
        gate.require(ProjectsPermissions.PROJECT_VIEW, definition.projectId.value)
        return definition
    }

    override fun listByProject(projectId: ProjectId): List<WorkflowDefinition> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        return workflows.findByProjectId(projectId).sortedBy { it.name }
    }

    override fun duplicate(id: WorkflowDefinitionId): WorkflowDefinition {
        val source = load(id)
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, source.projectId.value).actor
        val now = clock.now()
        val names = workflows.findByProjectId(source.projectId).map { it.name }.toSet()
        var name = "${source.name} (copy)"
        var n = 2
        while (name in names) name = "${source.name} (copy $n)".also { n++ }
        val copy = workflows.save(
            source.copy(id = WorkflowDefinitionId.new(), name = name, version = 1, enabled = false, createdAt = now, updatedAt = now, updatedBy = actor),
        )
        audit.record(AuditEntry("WORKFLOW_DUPLICATE", ENTITY, copy.id.toString(), projectId = source.projectId.value, details = mapOf("sourceId" to source.id.toString(), "name" to copy.name)))
        return copy
    }

    override fun validate(projectId: ProjectId, command: WorkflowCommand): WorkflowValidationResult {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        return check(projectId, command)
    }

    override fun palette(projectId: ProjectId): Palette {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        val tasks = runCatching { taskCatalog.palette(projectId.value) }.getOrDefault(emptyList())
            .map { t ->
                PaletteTask(
                    key = t.key,
                    name = t.name,
                    description = t.description,
                    category = t.category,
                    ports = t.ports.ifEmpty { WorkflowNode.DEFAULT_TASK_PORTS },
                    projectScoped = t.projectId != null,
                    configSchema = if (t.needsCodingAgent) ControlNodeCatalog.codingAgentConfigSchema() else ControlNodeCatalog.modelAnswerConfigSchema(),
                    deprecated = t.deprecated,
                    parameters = t.parameters,
                    parameterValues = t.parameterValues,
                    needsParameters = t.needsParameters,
                    needsCodingAgent = t.needsCodingAgent,
                    requiresSkills = t.requiresSkills,
                )
            }
        return Palette(ControlNodeCatalog.controlNodes(), tasks, ControlNodeCatalog.stateFields(), ActionCatalog.all)
    }

    @PublicOperation("static JSON Schemas of the task steps' config keys; reads no project data")
    override fun taskConfigSchemas(): TaskConfigSchemas =
        TaskConfigSchemas(ControlNodeCatalog.modelAnswerConfigSchema(), ControlNodeCatalog.codingAgentConfigSchema())

    @PublicOperation("syntax check of a SpEL expression; reads no project data")
    override fun validateExpression(expression: String): ExpressionCheck {
        if (expression.isBlank()) return ExpressionCheck(false, "expression must not be blank")
        val problem = expressions.validate(expression)
        return ExpressionCheck(problem == null, problem)
    }

    /** Looks templates up through the catalog: unknown -> null, abstract/cyclic -> abstract info, catalog down -> defaults. */
    private fun taskPortsFor(projectId: ProjectId): TaskPortsProvider = TaskPortsProvider { key ->
        try {
            taskCatalog.resolve(key, projectId.value)?.let { task ->
                TaskTemplateInfo(
                    task.ports.ifEmpty { WorkflowNode.DEFAULT_TASK_PORTS },
                    parameters = task.parameters.map { it.definition }, issueKeyFrom = task.config["issueKeyFrom"]?.toString(),
                )
            }
        } catch (e: ValidationException) {
            TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, abstract = true)
        } catch (e: RuntimeException) {
            TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS)
        }
    }

    private fun check(projectId: ProjectId, command: WorkflowCommand): WorkflowValidationResult {
        val graph = validator.validate(command.name, command.nodes, command.edges, taskPortsFor(projectId), ExpressionValidator { expressions.validate(it) }, command.triggers)
        val (errors, warnings) = connectionIssues(projectId, command.nodes, command.triggers)
        val (repoErrors, repoWarnings) = repositoryIssues(projectId, command.sources, command.nodes)
        // ADR 0050: a model the current user cannot use is a warning (it may become usable); the run start refuses it
        val modelWarnings = modelSteps?.let { check -> projects.findById(projectId)?.let { check.designIssues(it, command.nodes, currentUserId()) } }.orEmpty()
        val allErrors = errors + repoErrors
        val allWarnings = warnings + repoWarnings + modelWarnings
        return if (allErrors.isEmpty() && allWarnings.isEmpty()) graph else WorkflowValidationResult(graph.errors + allErrors, graph.warnings + allWarnings)
    }

    override fun modelPlan(id: WorkflowDefinitionId): List<ManageWorkflows.StepModelPlan> {
        val definition = workflows.findById(id) ?: throw NotFoundException("WorkflowDefinition", id)
        val user = gate.require(ProjectsPermissions.PROJECT_VIEW, definition.projectId.value)
        val project = projects.findById(definition.projectId) ?: throw NotFoundException("Project", definition.projectId)
        val check = modelSteps ?: return emptyList()
        return check.plan(project, definition.nodes, user.userId?.value).map {
            ManageWorkflows.StepModelPlan(it.nodeId, it.nodeName, it.stepKind, it.harness, it.source.name, it.profileId, it.model, it.problem, it.model?.note)
        }
    }

    override fun runInputs(id: WorkflowDefinitionId): List<RunInputField> {
        val definition = workflows.findById(id) ?: throw NotFoundException("WorkflowDefinition", id)
        gate.require(ProjectsPermissions.PROJECT_VIEW, definition.projectId.value)
        return RunInputs.effective(definition, taskCatalog)
    }

    override fun modelOptions(projectId: ProjectId, stepKind: StepKind, harness: AgentHarness?): List<ModelOption> {
        val user = gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val project = projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        return availability?.options(ModelUse(stepKind, harness, project.personal, user.userId?.value)).orEmpty()
    }

    override fun auditAgents(projectId: ProjectId, nodes: List<WorkflowNode>): AgentAuditReport {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val project = projectId.value
        // a step whose template is unknown or whose chain is broken is the validator's finding, not the audit's
        val steps = nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            runCatching { taskCatalog.stepNeeds(node.task.templateKey, project, node.task.overrides) }.getOrNull()?.let { AuditedStep(node.id, it) }
        }
        val profiles = steps.map { it.needs.agentKey }.distinct().associateWith { taskCatalog.agentProfile(it, project) }
        val known = connectors?.let { c -> (c.findByProject(projectId) + c.findPlatform()).map { it.key }.toSet() }
        return AgentAuditor.audit(steps, profiles, known)
    }

    /** The user the designer validates for; null outside a request (system work). */
    private fun currentUserId() = runCatching { gate.currentUser().userId?.value }.getOrNull()

    /** ADR 0063: the workflow's sources, the coding steps and the deliveries ([RepositoryStepCheck]). */
    private fun repositoryIssues(projectId: ProjectId, sources: List<pl.mos.backoffice.projects.domain.model.WorkflowSource>, nodes: List<WorkflowNode>): Pair<List<ValidationIssue>, List<ValidationIssue>> {
        val project = projects.findById(projectId) ?: return emptyList<ValidationIssue>() to emptyList()
        val issues = repositorySteps.designIssues(project, sources, nodes)
        return issues.errors to issues.warnings
    }

    /**
     * ADR 0030 / 0033: the connection each Jira step would use and the write-approval setting of write steps
     * ([JiraStepCheck]). A missing connection is only a warning here; the run start refuses it.
     */
    private fun connectionIssues(
        projectId: ProjectId,
        nodes: List<WorkflowNode>,
        triggers: List<pl.mos.backoffice.projects.domain.model.Trigger>,
    ): Pair<List<ValidationIssue>, List<ValidationIssue>> {
        val check = jiraSteps ?: return emptyList<ValidationIssue>() to emptyList()
        val project = projects.findById(projectId) ?: return emptyList<ValidationIssue>() to emptyList()
        val issues = check.designIssues(project, nodes, triggers)
        return issues.errors to issues.warnings
    }

    /** The library link of [definition] with `modified` recomputed against the version it follows (ADR 0037). */
    private fun refreshedLink(definition: WorkflowDefinition): LibraryLink? {
        val link = definition.library ?: return null
        val entry = library?.findById(link.libraryWorkflowId) ?: return link
        val followed = entry.versions.firstOrNull { it.version == link.version } ?: return link.copy(modified = true)
        return link.copy(modified = !LibraryPortability.sameGraph(definition.nodes, definition.edges, followed.nodes, followed.edges))
    }

    /** Graph, connection and repository checks of [command] in [projectId]; used by the library to adopt and update. */
    @SystemOperation("validation helper of WorkflowLibraryService, which checks workflow.edit before calling it")
    internal fun requireValidFor(projectId: ProjectId, command: WorkflowCommand) = requireValid(projectId, command)

    private fun requireValid(projectId: ProjectId, command: WorkflowCommand) {
        val result = check(projectId, command)
        if (!result.valid) {
            throw ValidationException(
                result.errors.map { ValidationException.FieldError(listOfNotNull(it.nodeId ?: it.edgeId, it.field).joinToString(".").ifBlank { null }, it.message) },
            )
        }
    }

    private fun load(id: WorkflowDefinitionId): WorkflowDefinition = workflows.findById(id) ?: throw NotFoundException("Workflow", id)

    companion object {
        const val ENTITY = "Workflow"
        const val FIELD_JIRA_CONNECTION = JiraStepCheck.FIELD_JIRA_CONNECTION
    }
}
