package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.usage.PageOf

/**
 * Use case: `backoffice-ci run <entrypoint>`. Resolves the entrypoint, applies the idempotency rule
 * (same project/entrypoint/commit/MR within 24 h returns the existing execution unless
 * `variables.force == "true"`), stores the diff as artefact `diff`, starts the workflow with
 * `trigger = CI` and the CI context as input, and marks the execution RUNNING.
 */
interface StartCiExecution {
    fun start(projectId: ProjectId, command: CiExecutionCommand): CiExecution

    data class CiExecutionCommand(
        val entrypoint: String,
        val context: CiContext,
        val diff: String? = null,
        val changedFiles: List<String> = emptyList(),
        val variables: Map<String, String> = emptyMap(),
    )
}

/**
 * Queries over CI executions. `get` needs `project.view` on the execution's project or a CI token of that
 * project; `list` returns only executions of projects the caller may view.
 */
interface CiExecutionQueries {
    fun get(id: CiExecutionId): CiExecution
    fun list(projectId: ProjectId?, page: Int, size: Int): PageOf<CiExecution>
}

/** Internal use case: called when a CI-triggered run finishes to derive status, gate result and report. */
interface CompleteCiExecution {
    fun runFinished(run: WorkflowRun)
}
