package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.WatchPipelines
import pl.mos.backoffice.projects.application.port.out.FailedJob
import pl.mos.backoffice.projects.application.port.out.PipelineReport
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.application.port.out.PipelineStatusPort
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnectionSelector
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * ADR 0052: runs waiting at a `WAIT_FOR_CI` node with `watchPipeline` are resumed by Back Office itself. The sweep reads
 * the latest pipeline of the run's branch (the last pushed branch of the watched repository) with the project's
 * CI_READ token and delivers the node's signal when it finished: SUCCESS, or FAIL with the failed jobs and their log
 * tails, which the node passes to the next step as feedback. A pipeline still running, none yet, a missing token or an
 * unreachable SCM keep the run waiting (the node's timeout still applies).
 */
class PipelineWatchService(
    private val runs: WorkflowRunRepository,
    private val projects: ProjectRepository,
    private val connections: ScmConnectionRepository,
    private val pipelines: PipelineStatusPort,
    private val engine: WorkflowEngine,
    private val clock: Clock,
) : WatchPipelines {

    @SystemOperation("scheduled pipeline watch sweep (ADR 0052)")
    override fun sweep(): Int = runs.findWaiting(null).count { watch(it) }

    /** True when [run] was resumed. */
    @SystemOperation("scheduled pipeline watch, one run")
    fun watch(run: WorkflowRun): Boolean {
        val waiting = run.waitingOn?.takeIf { it.kind == WaitKind.WAIT_FOR_CI } ?: return false
        val node = run.definitionSnapshot.node(waiting.nodeId) as? WorkflowNode.WaitForCi ?: return false
        if (!node.ci.watchPipeline) return false
        val project = projects.findById(run.projectId) ?: return false
        val repository = (node.ci.repository?.let { key -> project.repositories.firstOrNull { it.key == key } } ?: project.primaryRepository) ?: return false
        val pushed = run.nodes.asReversed().asSequence().flatMap { it.repositories.asSequence() }
            .firstOrNull { it.key == repository.key && it.branch != null && it.pushed }
        val branch = pushed?.branch ?: run.mergeRequest?.branch ?: return false
        val token = ScmConnectionSelector.select(connections.findByProject(project.id), repository.key, setOf(ScmCapability.CI_READ)) ?: return false
        val report = pipelines.latest(repository, token, branch, pushed?.commitSha)
        val status = when (report.state) {
            PipelineState.SUCCESS -> CiSignalStatus.SUCCESS
            PipelineState.FAILED -> CiSignalStatus.FAIL
            PipelineState.NONE, PipelineState.RUNNING -> return false
        }
        val now = clock.now()
        val signal = CiSignal(node.ci.signal, status, message(report, branch), receivedAt = now)
        val resumed = runs.save(run.resumedWithSignal(signal, now))
        engine.resumeWithSignal(resumed, signal)
        return true
    }

    companion object {
        const val MAX_MESSAGE = 12_000

        /** What the next step and the owner read: the pipeline, then each failed job with the end of its log. */
        fun message(report: PipelineReport, branch: String): String = buildString {
            append("Pipeline ${report.id?.let { "#$it " } ?: ""}of $branch: ${report.status ?: report.state.name.lowercase()}")
            report.url?.let { append(" ($it)") }
            if (report.state == PipelineState.FAILED) {
                if (report.failedJobs.isEmpty()) append("\nNo failed job that may not fail was found; see the pipeline.")
                report.failedJobs.forEach { job: FailedJob ->
                    append("\n\n### Failed job ${job.name} (stage ${job.stage})")
                    job.url?.let { append(" - $it") }
                    append("\n```\n").append(job.logTail.trimEnd()).append("\n```")
                }
            }
        }.take(MAX_MESSAGE)
    }
}
