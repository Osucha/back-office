package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId

/**
 * Pure matching rule for CI signals (docs/architecture/03-pipelines-and-agents.md): an explicit run id
 * (from the `Back-Office-Run` trailer) wins; otherwise the newest waiting run of the project with the same
 * signal and merge-request branch; otherwise the only candidate.
 */
object CiSignalMatcher {

    data class Match(val run: WorkflowRun?, val message: String)

    /**
     * [byId] is the run named by [runId] (null when not found or no id was given); [waiting] are the
     * project's `WAITING` runs.
     */
    fun match(projectId: ProjectId, signal: String, runId: WorkflowRunId?, branch: String?, byId: WorkflowRun?, waiting: List<WorkflowRun>): Match {
        if (runId != null) {
            val run = byId ?: return Match(null, "run $runId not found")
            if (run.projectId != projectId) return Match(null, "run $runId does not belong to this project")
            if (!run.waitsFor(signal)) return Match(null, "run $runId is ${run.status} and not waiting for signal $signal")
            return Match(run, "matched by run id")
        }
        val candidates = waiting.filter { it.projectId == projectId && it.waitsFor(signal) }.sortedByDescending { it.createdAt }
        if (candidates.isEmpty()) return Match(null, "no run is waiting for signal $signal")
        val wanted = branch?.takeIf { it.isNotBlank() }
        if (wanted != null) {
            candidates.firstOrNull { it.mergeRequest?.branch == wanted }?.let { return Match(it, "matched by branch $wanted") }
        }
        if (candidates.size == 1) return Match(candidates.single(), "matched the only waiting run")
        return Match(null, "${candidates.size} runs are waiting for signal $signal; pass --run <id> or a matching branch")
    }

    private fun WorkflowRun.waitsFor(signal: String): Boolean =
        status == RunStatus.WAITING && waitingOn?.kind == WaitKind.WAIT_FOR_CI && waitingOn?.signal.equals(signal, ignoreCase = true)
}
