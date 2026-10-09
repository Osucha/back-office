package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.IntakeMode
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.service.ConnectionChoice

/**
 * Use case (ADR 0033): the project's Jira intake watcher. Reading needs `project.view`; configuring and "Poll now"
 * need `project.manage` (owners, maintainers). Changes are audited (`JIRA_INTAKE_UPDATE`, `JIRA_INTAKE_POLL_NOW`).
 */
interface ManageJiraIntake {
    fun get(projectId: ProjectId): JiraIntakeView
    fun update(projectId: ProjectId, command: IntakeCommand): JiraIntakeView

    /** Schedules a poll right away (a worker performs it); returns the view with the new next-poll time. */
    fun pollNow(projectId: ProjectId): JiraIntakeView

    data class IntakeCommand(val enabled: Boolean, val pollIntervalMinutes: Int, val workflowId: WorkflowDefinitionId?)

    /**
     * The watcher as the project page shows it: configuration and state, the workflow and its trigger, the mode, the
     * JQL it sends (window shown as `-<n>m`), and the read connection it would use now ([connection]: NONE = it refuses
     * to poll; OVER_PRIVILEGED = it polls and warns).
     */
    data class JiraIntakeView(
        val intake: JiraIntake,
        val workflowName: String?,
        val triggerStatus: String?,
        val mode: IntakeMode?,
        val jql: String?,
        val connection: ConnectionChoice,
        val problems: List<String>,
    )
}

/** System use case (ADR 0033): polling, run by the scheduler and the `JIRA_INTAKE_POLL` work handler on workers. */
interface PollJiraIntake {
    /** Projects whose watcher is due now. */
    fun due(): List<ProjectId>

    /** One poll of [projectId]'s watcher; returns how many runs it started (0 when disabled or failing). */
    fun poll(projectId: ProjectId): Int
}
