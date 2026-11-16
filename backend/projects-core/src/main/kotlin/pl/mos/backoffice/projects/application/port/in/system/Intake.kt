package pl.mos.backoffice.projects.application.port.`in`.system

import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.ProjectId

/** System use case (ADR 0033): polling, run by the scheduler and the `JIRA_INTAKE_POLL` work handler on workers. */
interface PollJiraIntake {
    /** Projects whose watcher is due now. */
    fun due(): List<ProjectId>

    /** One poll of [projectId]'s watcher; returns how many runs it started (0 when disabled or failing). */
    fun poll(projectId: ProjectId): Int
}

/** System use case (ADR 0038): polling of personal intake rules by the scheduler and the work handler. */
interface PollPersonalIntake {
    fun due(): List<IntakeRuleId>

    /** One poll of the rule; returns how many proposals it created. */
    fun poll(id: IntakeRuleId): Int
}
