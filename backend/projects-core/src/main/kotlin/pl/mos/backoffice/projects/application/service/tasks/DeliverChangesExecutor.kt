package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.ActionStep
import pl.mos.backoffice.projects.application.port.out.ActionStrategy
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunAdvance
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.tasks.RunnerTaskSupport.Companion.boolean
import pl.mos.backoffice.projects.application.service.tasks.RunnerTaskSupport.Companion.list
import pl.mos.backoffice.projects.application.service.tasks.RunnerTaskSupport.Companion.string
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.projects.domain.service.StepRepository
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.projects.domain.service.WorkflowSources
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.prompt.UntrustedText
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobMergeRequest
import pl.mos.backoffice.shared.runner.JobRepository
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMergeRequest
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import java.time.Duration

/**
 * ADR 0063: `DELIVER_CHANGES`, the only step with push rights. Submits a runner job of mode `DELIVER` (no agent) in the
 * run directory: the runner commits the work tree of every delivered repository with the commit subject, pushes the
 * branch and opens the merge request through push options (never merges). Non-blocking like the agent jobs: [execute]
 * returns [NodeResult.awaiting] with a `RUN_ADVANCE` continuation and [resume] maps the result to `mr`, `diff`, the run's
 * branch and the per-repository results (state `branch`, `mr`, `repositories`).
 *
 * The delivered repositories ([WorkflowSources.delivery]) are the project repositories of the workflow's sources (config
 * `repositories`: the listed keys); each gets the job branch and, with `mergeRequest` (default true), its own merge
 * request. Tokens: a project repository token with PUSH_BRANCH (ADR 0051), else the runner pool's token (deprecated).
 *
 * Branch safety (ADR 0031): the branch is `branchTemplate`, else the run's branch, else `feature/<issue key>`; it is never
 * the base branch of a delivered repository, `HEAD` or a `refs/` path (the run's fallback `run/<id8>` then); the runner
 * refuses such refspecs too.
 */
class DeliverChangesExecutor(
    private val runner: AgentRunner,
    private val renderer: TemplateRenderer,
    private val templateModel: TemplateModelBuilder,
    /** ADR 0043: library sources the workflow may list; null = none (tests). */
    private val librarySources: LibrarySourceRepository? = null,
    /** ADR 0051: tokens of project repositories; null = none (tests), the runner pool's tokens are used. */
    private val scmConnections: ScmConnectionRepository? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : ActionStrategy {
    override val type = ActionType.DELIVER_CHANGES

    private data class Plan(val spec: RunnerJobSpec, val repositories: List<StepRepository>, val branch: String, val mergeRequest: Boolean)

    override fun execute(context: NodeContext, step: ActionStep): NodeResult {
        val plan = try {
            plan(context, step, logWarnings = true)
        } catch (e: RunnerJobs.PlanException) {
            return NodeResult.failure(e.message ?: "nothing can be delivered")
        }
        context.log("submitting delivery job ${plan.spec.jobLabel}: branch ${plan.branch}" + if (plan.mergeRequest) ", merge request" else ", no merge request")
        val jobId = runner.submit(plan.spec, RunAdvance.runnerContinuation(context.run.id, context.node.id))
        context.log("delivery job ${plan.spec.jobLabel} queued as $jobId; the run continues when it finishes")
        return NodeResult.awaiting(jobId)
    }

    override fun resume(context: NodeContext, step: ActionStep, jobId: RunnerJobId): NodeResult {
        val plan = try {
            plan(context, step, logWarnings = false)
        } catch (e: RunnerJobs.PlanException) {
            return NodeResult.failure(e.message ?: "nothing can be delivered", runnerJobId = jobId)
        }
        val result = runner.result(jobId) ?: return NodeResult.failure("runner job $jobId has no result", runnerJobId = jobId)
        return map(context, plan, jobId, result)
    }

    private fun plan(context: NodeContext, step: ActionStep, logWarnings: Boolean): Plan {
        val node = context.node
        val project = context.project
        val config = step.config
        val definition = context.run.definitionSnapshot
        val sources = WorkflowSources.plan(project, definition.sources) { librarySources?.findByKey(it) }
        if (!sources.valid) throw RunnerJobs.PlanException("sources of the workflow: " + sources.errors.joinToString("; "))
        val delivery = WorkflowSources.delivery(sources, config.list(ActionCatalog.REPOSITORIES))
        if (!delivery.valid) throw RunnerJobs.PlanException("delivery ${node.id}: " + delivery.errors.joinToString("; "))
        if (logWarnings) delivery.warnings.forEach { context.warn("delivery: $it") }
        val repos = delivery.repositories
        val model = templateModel.build(context, sources.repositories)
        // the branch is never the base branch of a repository it is pushed to (nor HEAD / a ref path)
        val fallbackBranch = "run/${context.run.id.short}"
        val branch = renderer.renderText(config.string(ActionCatalog.BRANCH_TEMPLATE) ?: context.stateString(RunStateKeys.BRANCH) ?: RunnerTaskSupport.DEFAULT_BRANCH_TEMPLATE, model)
            .let(RunnerTaskSupport::sanitiseBranch)
            .let { candidate ->
                val refused = repos.map { it.repository.defaultBranch }.distinct().firstNotNullOfOrNull { RunnerTaskSupport.refusedBranchReason(candidate, it) }
                if (refused != null && logWarnings) context.log("branch \"$candidate\" refused ($refused); using $fallbackBranch")
                if (refused != null) fallbackBranch else candidate
            }
        val opensMergeRequest = config.boolean(ActionCatalog.MERGE_REQUEST, default = true)
        val mergeRequest = if (opensMergeRequest) {
            JobMergeRequest(
                create = true,
                title = UntrustedText.sanitize(renderer.renderText(config.string(ActionCatalog.MERGE_REQUEST_TITLE_TEMPLATE) ?: RunnerTaskSupport.DEFAULT_MR_TITLE, model), RunnerTaskSupport.MAX_MR_TITLE)
                    .ifBlank { "Back Office run ${context.run.id.short}" },
                description = UntrustedText.sanitize(
                    renderer.renderText(config.string(ActionCatalog.MERGE_REQUEST_DESCRIPTION_TEMPLATE) ?: RunnerTaskSupport.DEFAULT_MR_DESCRIPTION, model),
                    RunnerTaskSupport.MAX_MR_DESCRIPTION,
                ).takeIf { it.isNotBlank() },
                draft = config.boolean(ActionCatalog.MERGE_REQUEST_DRAFT, default = false),
            )
        } else {
            null
        }
        val jobRepositories = repos.map { r ->
            JobRepository(
                key = r.key, url = r.repository.url, baseBranch = r.repository.defaultBranch, access = GitAccess.PUSH_MR, path = r.key,
                pushBranch = branch, mergeRequest = mergeRequest, primary = r.primary,
            )
        }
        val credentials = RunnerJobs.credentialsOf(scmConnections, project.id, jobRepositories)
        val primary = repos.first { it.primary }
        // legacy mirror of the primary entry for runners older than ADR 0034
        val repository = RunnerRepository(
            url = primary.repository.url, baseRef = primary.repository.defaultBranch, branch = branch, push = true,
            mergeRequest = mergeRequest?.let { RunnerMergeRequest(it.create, it.title, it.description, primary.repository.defaultBranch, it.draft) },
        )
        val spec = RunnerJobSpec(
            mode = RunnerMode.DELIVER,
            // no agent runs; the fields are required by the spec
            agent = AgentHarness.OPENCODE,
            model = NO_MODEL,
            prompt = "Deliver the changes of run ${context.run.id.short} (${step.name}).",
            repository = repository,
            timeout = node.timeoutSeconds?.let(Duration::ofSeconds) ?: timeout,
            collectDiff = true,
            usageContext = UsageContext(
                UsageKind.PIPELINE_STEP, projectId = project.id.value, runId = context.run.id.value, stepId = node.id,
                actingUserId = ActingUser.startedBy(context.run)?.userId,
            ),
            jobLabel = RunnerTaskSupport.jobLabel(context.run.id.short, node.id, context.attempt),
            repositories = jobRepositories,
            gitAccess = GitAccess.PUSH_MR,
            personalRunnerOf = RunnerJobs.personalRunnerOf(context),
            // the run directory the agents worked in; its setup command unchanged, so the runner does not set it up again
            workspace = RunnerWorkspace(
                RunnerWorkspace.RUN_KEY, RunnerWorkspace.nameFor(context.run.id.value, RunnerWorkspace.RUN_KEY),
                setupCommand = definition.setupCommand?.takeIf { it.isNotBlank() },
            ),
            // ADR 0047: the commit subject never contains the system prompt or the notices
            commitSubject = UntrustedText.sanitize(renderer.renderText(config.string(ActionCatalog.COMMIT_SUBJECT_TEMPLATE) ?: RunnerTaskSupport.DEFAULT_COMMIT_SUBJECT, model), RunnerTaskSupport.MAX_COMMIT_SUBJECT)
                .lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !it.startsWith(":") },
            credentials = credentials.map { it.first },
        )
        if (logWarnings) {
            jobRepositories.forEach { r -> context.log("deliver repository ${r.key} (/workspace/${r.path}): git access ${r.access}, ${RunnerTaskSupport.describe(r.access)}") }
            RunnerJobs.logCredentials(context, jobRepositories, credentials)
        }
        return Plan(spec, repos, branch, opensMergeRequest)
    }

    private fun map(context: NodeContext, plan: Plan, jobId: RunnerJobId, result: RunnerResult): NodeResult {
        val project = context.project
        val succeeded = result.status == RunnerJobStatus.SUCCEEDED
        val repositories = RunnerJobs.repositoryResults(plan.repositories, result, succeeded)
        val artifacts = mutableListOf<NewArtifact>()
        result.diff?.takeIf { it.isNotBlank() }?.let { artifacts += NewArtifact("diff", ArtifactKind.DIFF, "text/x-diff", it) }
        val pushed = repositories.filter { it.pushed }
        val primary = repositories.firstOrNull { it.primary }
        val pushedBranch = primary?.branch ?: result.branch ?: plan.branch
        // the run's merge request: the primary repository's, else the first other repository that opened one
        val mrRepo = pushed.firstOrNull { it.primary } ?: pushed.firstOrNull { it.mergeRequestUrl != null }
        val mr = if (succeeded && mrRepo != null) {
            val target = plan.repositories.firstOrNull { it.key == mrRepo.key }?.repository?.defaultBranch ?: project.primaryRepository?.defaultBranch ?: ""
            MergeRequestSnapshot(url = mrRepo.mergeRequestUrl, branch = mrRepo.branch ?: pushedBranch, targetBranch = target)
        } else {
            null
        }
        val updates = HashMap<String, Any?>()
        if (succeeded && pushed.isNotEmpty()) updates[RunStateKeys.BRANCH] = pushedBranch
        mr?.let { updates[RunStateKeys.MR] = it.toMap() }
        @Suppress("UNCHECKED_CAST")
        val known = (context.state[RunStateKeys.REPOSITORIES] as? Map<String, Any?>).orEmpty()
        updates[RunStateKeys.REPOSITORIES] = LinkedHashMap(known).apply { repositories.forEach { put(it.key, it.toStateMap()) } }
        val summary = when {
            !succeeded -> "delivery ${result.status}: ${result.error ?: result.summary ?: "no details"}"
            pushed.isEmpty() -> "nothing to deliver: no repository has changes".also { context.warn(it) }
            else -> "delivered branch $pushedBranch to " + pushed.joinToString { it.key } + (mr?.url?.let { "; merge request $it" } ?: "")
        }
        return NodeResult(
            port = if (succeeded) WorkflowNode.PORT_SUCCESS else WorkflowNode.PORT_FAILURE,
            summary = summary,
            runnerJobId = jobId,
            artifacts = artifacts,
            mergeRequest = mr,
            stateUpdates = updates,
            error = if (succeeded) null else summary,
            repositories = repositories,
        )
    }

    companion object {
        /** A delivery commits and pushes; it never needs the time of an agent. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(30)

        /** A DELIVER job runs no model; the spec requires one, the runner ignores it. */
        val NO_MODEL = ModelRef(Provider.ANTHROPIC, "none")
    }
}
