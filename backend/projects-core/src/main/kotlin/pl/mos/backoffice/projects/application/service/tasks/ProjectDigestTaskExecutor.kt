package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.application.port.out.PipelineStatusPort
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnectionSelector
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import java.time.Duration
import java.time.Instant

/**
 * ADR 0052 step 3: `PROJECT_DIGEST` writes what happened in the project since the run parameter `since` (a scheduled
 * run gets it; default one day): every run of other workflows with its outcome, duration and cost, the steps that ran
 * more than once or failed (with their errors), the gate decisions with their comments, and the latest pipeline of
 * the configured branches with their failed jobs. Deterministic: an agent reads the `digest` artefact and proposes
 * improvements; it gets no access to Back Office or tokens for that.
 */
class ProjectDigestTaskExecutor(
    private val runs: WorkflowRunRepository,
    private val connections: ScmConnectionRepository,
    private val pipelines: PipelineStatusPort,
    private val clock: Clock,
) : TaskExecutorStrategy {

    override val executor = TaskExecutor.PROJECT_DIGEST

    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val now = clock.now()
        val since = context.run.input.parameters["since"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: now.minus(Duration.ofDays(1))
        val maxRuns = (task.config["maxRuns"] as? Number)?.toInt()?.coerceIn(1, 200) ?: DEFAULT_MAX_RUNS
        val recent = runs.find(RunFilter(projectId = context.project.id), ProjectFilter.All, 0, maxRuns * 2).content
            .filter { it.workflowId != context.run.workflowId && (it.createdAt >= since || (it.finishedAt ?: it.createdAt) >= since) }
            .take(maxRuns)
        val digest = buildString {
            append("# What happened in ${context.project.name} since $since\n\n")
            append("${recent.size} run(s) of other workflows.\n")
            recent.forEach { append(describe(it, now)) }
            append(pipelinesSection(context, task))
        }
        return NodeResult(
            port = WorkflowNode.PORT_SUCCESS,
            summary = "digest of ${recent.size} run(s) since $since",
            artifacts = listOf(NewArtifact(DIGEST, ArtifactKind.MARKDOWN, "text/markdown", digest.take(MAX_DIGEST))),
        )
    }

    private fun describe(run: WorkflowRun, now: Instant): String = buildString {
        val took = Duration.between(run.startedAt ?: run.createdAt, run.finishedAt ?: now)
        append("\n## ${run.workflowName} - run ${run.id.toString().take(8)}\n")
        append("- Status ${run.status}${run.outcome?.let { ", outcome $it" } ?: ""}; started ${run.createdAt}; took ${took.toMinutes()} min; ")
        append("cost ${run.cost.amount} ${run.cost.currency} (API equivalent ${run.apiCost.amount})\n")
        run.input.parameters["title"]?.let { append("- Title: $it\n") }
        run.failure?.let { append("- Failed at ${it.nodeId}: ${excerpt(it.message)}\n") }
        run.mergeRequest?.url?.let { append("- Merge request: $it\n") }
        val byNode = run.nodes.groupBy { it.nodeId }
        byNode.filterValues { it.size > 1 }.forEach { (node, executions) ->
            append("- Step $node ran ${executions.size} times (${executions.groupingBy { it.status }.eachCount().entries.joinToString { "${it.value} ${it.key}" }})\n")
        }
        run.nodes.filter { it.status == NodeExecutionStatus.FAILED }.forEach { append("- Step ${it.nodeId} failed: ${excerpt(it.error ?: it.summary)}\n") }
        run.nodes.filter { it.kind == NodeKind.HUMAN_GATE && it.status == NodeExecutionStatus.SUCCEEDED }
            .forEach { append("- Gate ${it.nodeId}: ${excerpt(it.summary)}\n") }
    }

    private fun pipelinesSection(context: NodeContext, task: ResolvedTask): String {
        val refs = task.config["pipelineRefs"]?.toString()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val repository = context.project.primaryRepository ?: return ""
        if (refs.isEmpty()) return ""
        val token = ScmConnectionSelector.select(connections.findByProject(context.project.id), repository.key, setOf(ScmCapability.CI_READ))
            ?: return "\n## Pipelines\nNo repository token with CI_READ, pipelines not read.\n"
        return buildString {
            append("\n## Pipelines\n")
            refs.forEach { ref ->
                val report = pipelines.latest(repository, token, ref, null)
                append("- $ref: ${report.status ?: report.state.name.lowercase()}${report.url?.let { " ($it)" } ?: ""}\n")
                if (report.state == PipelineState.FAILED) {
                    report.failedJobs.forEach { job -> append("  - failed job ${job.name} (${job.stage}):\n    ```\n${job.logTail.lines().takeLast(15).joinToString("\n") { "    $it" }}\n    ```\n") }
                }
            }
        }
    }

    private fun excerpt(text: String?): String = text.orEmpty().replace("\n", " ").trim().let { if (it.length > MAX_EXCERPT) it.take(MAX_EXCERPT) + "..." else it }

    companion object {
        const val DIGEST = "digest"
        const val DEFAULT_MAX_RUNS = 20
        const val MAX_EXCERPT = 400
        const val MAX_DIGEST = 60_000
    }
}
