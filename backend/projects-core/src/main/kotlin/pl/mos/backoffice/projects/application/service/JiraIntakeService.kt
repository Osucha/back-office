package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake
import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake.IntakeCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake.JiraIntakeView
import pl.mos.backoffice.projects.application.port.`in`.PollJiraIntake
import pl.mos.backoffice.projects.application.port.out.IssueTrackerUnavailableException
import pl.mos.backoffice.projects.application.port.out.JiraIntakeRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import pl.mos.backoffice.projects.domain.service.JiraIntakePlanner
import pl.mos.backoffice.projects.domain.service.SelectionStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.SystemOperation
import java.time.Duration

/**
 * Implements [ManageJiraIntake] and [PollJiraIntake] (ADR 0033). A poll:
 *
 * 1. refuses (error on the watcher, back-off) when the workflow is missing, disabled or has no `JIRA_TRANSITION`
 *    trigger, or when no connection with `ISSUE_READ` qualifies; an over-privileged connection is used with a warning;
 * 2. takes a baseline on the first poll after enabling (cursor = now, nothing starts);
 * 3. otherwise queries `project = KEY AND (JQL) AND <window>` through a client narrowed to ISSUE_READ, reads only
 *    key / status / updated / created / status changes, and starts at most [MAX_STARTS_PER_POLL] runs, each guarded by
 *    a claimed dedupe key (an issue starts at most one run per transition);
 * 4. on HTTP 429 / 5xx or other Jira errors records the error and backs off exponentially (honouring `Retry-After`).
 */
class JiraIntakeService(
    private val intakes: JiraIntakeRepository,
    private val projects: ProjectRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val runs: RunService,
    private val jira: JiraConnectionAccess,
    private val planner: JiraIntakePlanner,
    private val queue: WorkQueue,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageJiraIntake, PollJiraIntake {

    override fun get(projectId: ProjectId): JiraIntakeView {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val project = loadProject(projectId)
        return view(project, intakes.find(projectId) ?: JiraIntake(projectId))
    }

    override fun update(projectId: ProjectId, command: IntakeCommand): JiraIntakeView {
        val actor = gate.require(ProjectsPermissions.PROJECT_MANAGE, projectId.value).actor
        val project = loadProject(projectId)
        if (command.workflowId != null) {
            val workflow = workflows.findById(command.workflowId)
            if (workflow == null || workflow.projectId != projectId) throw ValidationException("workflow not found in this project", "workflowId")
            if (command.enabled && jiraTrigger(workflow) == null) {
                throw ValidationException(
                    "workflow ${workflow.name} has no JIRA_TRANSITION trigger; add one in the designer (with the status that starts a run, or empty for new issues)",
                    "workflowId",
                )
            }
        }
        val existing = intakes.find(projectId) ?: JiraIntake(projectId)
        val now = clock.now()
        val saved = intakes.save(existing.configure(command.enabled, command.pollIntervalMinutes, command.workflowId, actor, now))
        val changes = AuditableChanges.changed(
            "enabled" to (existing.enabled to saved.enabled),
            "pollIntervalMinutes" to (existing.pollIntervalMinutes to saved.pollIntervalMinutes),
            "workflowId" to (existing.workflowId?.toString() to saved.workflowId?.toString()),
        )
        if (changes.isNotEmpty()) audit.record(AuditEntry(UPDATE, ENTITY, projectId.toString(), projectId = projectId.value, details = changes))
        if (saved.enabled) enqueue(projectId)
        return view(project, saved)
    }

    override fun pollNow(projectId: ProjectId): JiraIntakeView {
        gate.require(ProjectsPermissions.PROJECT_MANAGE, projectId.value)
        val project = loadProject(projectId)
        val intake = intakes.find(projectId)?.takeIf { it.enabled } ?: throw ValidationException("the Jira intake watcher is not enabled", "enabled")
        val saved = intakes.save(intake.pollNow(clock.now()))
        audit.record(AuditEntry(POLL_NOW, ENTITY, projectId.toString(), projectId = projectId.value))
        enqueue(projectId)
        return view(project, saved)
    }

    @SystemOperation("scheduler of the Jira intake watcher (ShedLock singleton)")
    override fun due(): List<ProjectId> = intakes.findDue(clock.now()).map { it.projectId }

    @SystemOperation("JIRA_INTAKE_POLL work item on a worker; reads Jira with an ISSUE_READ connection only")
    override fun poll(projectId: ProjectId): Int {
        val intake = intakes.find(projectId)?.takeIf { it.enabled } ?: return 0
        val project = projects.findById(projectId) ?: return 0
        val now = clock.now()
        val workflow = intake.workflowId?.let(workflows::findById)?.takeIf { it.projectId == projectId }
        val problem = when {
            workflow == null -> "the workflow to start no longer exists; choose another one"
            !workflow.enabled -> "workflow ${workflow.name} is disabled"
            jiraTrigger(workflow) == null -> "workflow ${workflow.name} has no JIRA_TRANSITION trigger"
            else -> null
        }
        if (problem != null || workflow == null) {
            intakes.save(intake.failed(now, problem ?: "no workflow"))
            return 0
        }
        val (choice, tracker) = jira.openReader(project)
        if (tracker == null || choice.connection == null) {
            // least privilege: without an ISSUE_READ connection the watcher does not run at all
            intakes.save(intake.failed(now, "refused: ${choice.message}", connection = null))
            return 0
        }
        val connectionName = choice.connection.name
        val warning = choice.message.takeIf { choice.status == SelectionStatus.OVER_PRIVILEGED }
        if (watchesEverything(project)) {
            intakes.save(intake.failed(now, "refused: $NO_JIRA_SCOPE", connection = connectionName))
            return 0
        }
        val cursor = intake.cursor
        if (cursor == null) {
            intakes.save(intake.succeeded(now, 0, advanceCursor = true, connection = connectionName, warning = warning))
            return 0
        }
        val status = jiraTrigger(workflow)?.status
        return try {
            val jql = planner.jql(project.jiraProjectKey, project.issueTracker.jql, status, planner.windowMinutes(cursor, now))
            val events = planner.events(tracker.watch(jql, MAX_RESULTS), status)
            var started = 0
            events.take(MAX_STARTS_PER_POLL).forEach { event ->
                if (start(projectId, workflow.id, event.issueKey, event.dedupeKey, now)) started++
            }
            intakes.purgeClaims(now.minus(CLAIM_RETENTION))
            intakes.save(intake.succeeded(now, started, advanceCursor = events.size <= MAX_STARTS_PER_POLL, connection = connectionName, warning = warning))
            started
        } catch (e: IssueTrackerUnavailableException) {
            intakes.save(intake.failed(now, "Jira answered HTTP ${e.status}: ${e.message}", e.retryAfter, connectionName))
            0
        } catch (e: DomainException) {
            intakes.save(intake.failed(now, e.message ?: e.javaClass.simpleName, connection = connectionName))
            0
        } catch (e: RuntimeException) {
            intakes.save(intake.failed(now, "${e.javaClass.simpleName}: ${e.message}", connection = connectionName))
            0
        }
    }

    /** Claims the dedupe key, then starts the run; a failed start releases the claim so the next poll retries. */
    private fun start(projectId: ProjectId, workflowId: WorkflowDefinitionId, issueKey: String, dedupeKey: String, now: java.time.Instant): Boolean {
        if (!intakes.claim(projectId, dedupeKey, issueKey, now)) return false
        return try {
            val run = runs.startForJira(workflowId, issueKey, dedupeKey)
            intakes.attachRun(projectId, dedupeKey, run.id)
            true
        } catch (e: DomainException) {
            intakes.release(projectId, dedupeKey)
            throw e
        }
    }

    /** Neither a Jira project key nor a JQL filter: the watcher would see every project of the site. */
    private fun watchesEverything(project: Project): Boolean = project.jiraProjectKey == null && project.issueTracker.jql.isNullOrBlank()

    private fun enqueue(projectId: ProjectId) =
        queue.enqueue(WorkRequest(WorkKinds.JIRA_INTAKE_POLL, mapOf(PROJECT_ID to projectId.toString()), dedupeKey = dedupeKey(projectId), maxAttempts = 1))

    private fun view(project: Project, intake: JiraIntake): JiraIntakeView {
        val workflow = intake.workflowId?.let(workflows::findById)?.takeIf { it.projectId == project.id }
        val trigger = workflow?.let(::jiraTrigger)
        val status = trigger?.status?.takeIf { it.isNotBlank() }
        val choice: ConnectionChoice = jira.choose(project, setOf(ConnectionCapability.ISSUE_READ), pinned = null)
        val problems = buildList {
            if (intake.workflowId != null && workflow == null) add("the workflow to start no longer exists")
            if (workflow != null && trigger == null) add("workflow ${workflow.name} has no JIRA_TRANSITION trigger")
            if (workflow != null && !workflow.enabled) add("workflow ${workflow.name} is disabled")
            if (!choice.usable) add("no connection with ISSUE_READ: the watcher refuses to poll (${choice.message})")
            if (workflow != null && workflow.draft) add("workflow ${workflow.name} is a draft with validation errors")
            if (watchesEverything(project)) add(NO_JIRA_SCOPE)
        }
        return JiraIntakeView(
            intake = intake,
            workflowName = workflow?.name,
            triggerStatus = status,
            mode = trigger?.let { planner.mode(status) },
            jql = planner.jql(project.jiraProjectKey, project.issueTracker.jql, status, intake.pollIntervalMinutes + JiraIntakePlanner.OVERLAP.toMinutes()),
            connection = choice,
            problems = problems,
        )
    }

    private fun jiraTrigger(workflow: WorkflowDefinition) = workflow.triggers.firstOrNull { it.type == TriggerType.JIRA_TRANSITION }

    private fun loadProject(projectId: ProjectId): Project = projects.findById(projectId) ?: throw NotFoundException("Project", projectId)

    companion object {
        const val NO_JIRA_SCOPE = "set the Jira project key (or a JQL filter) in the project settings: without it the watcher would read every Jira project"
        const val ENTITY = "JiraIntake"
        const val UPDATE = "JIRA_INTAKE_UPDATE"
        const val POLL_NOW = "JIRA_INTAKE_POLL_NOW"
        const val PROJECT_ID = "projectId"
        const val MAX_RESULTS = 100
        const val MAX_STARTS_PER_POLL = 20
        val CLAIM_RETENTION: Duration = Duration.ofDays(30)

        fun dedupeKey(projectId: ProjectId): String = "${WorkKinds.JIRA_INTAKE_POLL}:$projectId"
    }
}
