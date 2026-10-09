package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import java.time.Instant

/** Watchers (ADR 0033) and the dedupe keys of the runs they started. */
interface JiraIntakeRepository {
    fun find(projectId: ProjectId): JiraIntake?
    fun save(intake: JiraIntake): JiraIntake

    /** Enabled watchers whose next poll is due at [now]. */
    fun findDue(now: Instant): List<JiraIntake>

    /**
     * Records [dedupeKey] for [projectId] unless it is already there; true when this call recorded it (the caller may
     * start the run). Atomic across replicas (primary key).
     */
    fun claim(projectId: ProjectId, dedupeKey: String, issueKey: String, at: Instant): Boolean

    fun attachRun(projectId: ProjectId, dedupeKey: String, runId: WorkflowRunId)

    /** Forgets a claim whose run could not be started (the next poll tries again). */
    fun release(projectId: ProjectId, dedupeKey: String)

    /** Drops dedupe keys older than [before] (they are outside every poll window). Returns the count. */
    fun purgeClaims(before: Instant): Int
}
