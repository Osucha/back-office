package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.`in`.StartScheduledRuns
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.SystemOperation
import java.time.Duration

/**
 * ADR 0052: a workflow with a `SCHEDULE` trigger is started every `intervalMinutes` after its latest run began; never
 * while one of its runs is still active (a slow watcher does not pile up runs) and never when it is disabled. The run
 * gets the parameter `since`: when the previous run started, so a watcher knows what is new.
 */
class ScheduledRunService(
    private val projects: ProjectRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val runs: WorkflowRunRepository,
    private val startRun: () -> StartRun,
    private val clock: Clock,
) : StartScheduledRuns {

    @SystemOperation("scheduled workflow starts (ADR 0052)")
    override fun sweep(): Int = projects.findAll(ProjectFilter.All).asSequence()
        .flatMap { workflows.findByProjectId(it.id).asSequence() }
        .count { start(it) }

    private fun start(workflow: WorkflowDefinition): Boolean {
        if (!workflow.enabled) return false
        val interval = workflow.triggers.firstOrNull { it.type == TriggerType.SCHEDULE }?.intervalMinutes ?: return false
        if (runs.existsActiveByWorkflow(workflow.id)) return false
        val latest = runs.find(RunFilter(workflowId = workflow.id), ProjectFilter.All, 0, 1).content.firstOrNull()
        val now = clock.now()
        if (latest != null && now.isBefore(latest.createdAt.plus(Duration.ofMinutes(interval.toLong())))) return false
        val parameters = mapOf("since" to (latest?.createdAt ?: now.minus(Duration.ofMinutes(interval.toLong()))).toString())
        startRun().start(workflow.id, RunInput(null, parameters), TriggerSource.SCHEDULE)
        return true
    }
}
