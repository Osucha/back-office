package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.ActionStep
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.nodes.ActionNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.ActionStrategies
import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.RepoResult
import pl.mos.backoffice.shared.runner.RunnerDiffStat
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import java.util.UUID

/** ADR 0063: `DELIVER_CHANGES` commits the run directory, pushes the branch and opens the merge request (the only push). */
class DeliverChangesExecutorTest {

    private val runner = GitAccessDerivationTest.CapturingRunner()
    private val executor = DeliverChangesExecutor(runner, TemplateRenderer(), TemplateModelBuilder())

    private fun context(
        project: Project = Fixtures.project(),
        config: Map<String, Any?> = emptyMap(),
        sources: List<WorkflowSource> = emptyList(),
        state: Map<String, Any?> = mapOf(RunStateKeys.ISSUE to mapOf("key" to "SHOP-1", "summary" to "Pay invoices")),
    ): Pair<NodeContext, ActionStep> {
        val base = Fixtures.issueWorkflow(project)
        val definition = base.copy(
            sources = sources,
            setupCommand = "npm ci",
            nodes = base.nodes.map { if (it.id == DefaultWorkflows.NODE_DELIVER) (it as WorkflowNode.Action).copy(action = ActionBinding(ActionType.DELIVER_CHANGES, config)) else it },
        )
        val run = Fixtures.runningRun(project, definition)
        val node = run.definitionSnapshot.requireNode(DefaultWorkflows.NODE_DELIVER) as WorkflowNode.Action
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), state) to ActionNodeExecutor.stepOf(node)
    }

    private fun twoRepositories(): Project = Fixtures.project().copy(
        repositories = listOf(
            GitRepository("app", "https://git.example.com/g/app.git", "main", RepositoryPurpose.SOURCE, null, primary = true),
            GitRepository("docs", "https://git.example.com/g/docs.git", "develop", RepositoryPurpose.DOCS, null),
        ),
    )

    @Test
    fun `a delivery job pushes the branch of the issue with a merge request, in the run directory`() {
        val (ctx, step) = context()
        val result = executor.execute(ctx, step)
        assertThat(result.awaitingJob).isNotNull()
        val spec = runner.specs.single()
        assertThat(spec.mode).isEqualTo(RunnerMode.DELIVER)
        assertThat(spec.gitAccess).isEqualTo(GitAccess.PUSH_MR)
        val repo = spec.repositories.single()
        assertThat(repo.access).isEqualTo(GitAccess.PUSH_MR)
        assertThat(repo.pushBranch).isEqualTo("feature/shop-1")
        assertThat(repo.primary).isTrue()
        assertThat(repo.mergeRequest!!.title).isNotBlank()
        assertThat(repo.mergeRequest!!.draft).isFalse()
        assertThat(spec.repository!!.push).isTrue()
        assertThat(spec.repository!!.mergeRequest!!.targetBranch).isEqualTo("main")
        assertThat(spec.workspace!!.key).isEqualTo(RunnerWorkspace.RUN_KEY)
        assertThat(spec.workspace!!.sessionRole).isNull()
        assertThat(spec.workspace!!.setupCommand).isEqualTo("npm ci")
        assertThat(spec.collectDiff).isTrue()
        assertThat(spec.skills).isEmpty()
        assertThat(spec.instructions).isNull()
        assertThat(spec.jobLabel).contains("-deliver-")
    }

    @Test
    fun `the branch template, the commit subject and the merge request settings come from the config`() {
        val (ctx, step) = context(
            config = mapOf(
                "branchTemplate" to "bo/{{run.short}}", "commitSubjectTemplate" to "feat: {{issue.key}}", "mergeRequestTitleTemplate" to "Fix {{issue.key}}",
                "mergeRequestDraft" to true, "mergeRequestDescriptionTemplate" to "Run {{run.id}}",
            ),
        )
        executor.execute(ctx, step)
        val spec = runner.specs.single()
        assertThat(spec.repositories.single().pushBranch).isEqualTo("bo/${ctx.run.id.short}")
        assertThat(spec.commitSubject).isEqualTo("feat: SHOP-1")
        assertThat(spec.repositories.single().mergeRequest!!.title).isEqualTo("Fix SHOP-1")
        assertThat(spec.repositories.single().mergeRequest!!.draft).isTrue()
        assertThat(spec.repositories.single().mergeRequest!!.description).contains(ctx.run.id.toString())

        val (noMr, noMrStep) = context(config = mapOf("mergeRequest" to false))
        executor.execute(noMr, noMrStep)
        assertThat(runner.specs.last().repositories.single().mergeRequest).isNull()
    }

    @Test
    fun `the run's branch is reused and the default branch is never pushed`() {
        val (ctx, step) = context(state = mapOf(RunStateKeys.BRANCH to "feature/earlier"))
        executor.execute(ctx, step)
        assertThat(runner.specs.last().repositories.single().pushBranch).isEqualTo("feature/earlier")
        val (main, mainStep) = context(config = mapOf("branchTemplate" to "main"))
        executor.execute(main, mainStep)
        assertThat(runner.specs.last().repositories.single().pushBranch).isEqualTo("run/${main.run.id.short}")
    }

    @Test
    fun `every project repository of the sources is delivered unless the config names some, library sources never`() {
        val project = twoRepositories()
        val (all, allStep) = context(project, sources = listOf(WorkflowSource("app"), WorkflowSource("docs")))
        executor.execute(all, allStep)
        assertThat(runner.specs.last().repositories.map { it.key }).containsExactly("app", "docs")
        assertThat(runner.specs.last().repositories.single { it.primary }.key).isEqualTo("app")

        val (docs, docsStep) = context(project, config = mapOf("repositories" to listOf("docs")), sources = listOf(WorkflowSource("app"), WorkflowSource("docs")))
        executor.execute(docs, docsStep)
        assertThat(runner.specs.last().repositories.map { it.key }).containsExactly("docs")
        assertThat(runner.specs.last().repositories.single().primary).isTrue()

        val (unknown, unknownStep) = context(project, config = mapOf("repositories" to listOf("web")))
        val refused = executor.execute(unknown, unknownStep)
        assertThat(refused.failed).isTrue()
        assertThat(refused.error).contains("repository web is not a project repository")
    }

    @Test
    fun `a project without repositories has nothing to deliver`() {
        val (ctx, step) = context(Fixtures.project().copy(repositories = emptyList()))
        val refused = executor.execute(ctx, step)
        assertThat(refused.failed).isTrue()
        assertThat(refused.error).contains("no project repository to deliver")
        assertThat(runner.specs).isEmpty()
    }

    @Test
    fun `the result gives the merge request, the branch, the diff and the repositories to the run`() {
        val (ctx, step) = context()
        val jobId = RunnerJobId(UUID.randomUUID())
        runner.results[jobId] = RunnerResult(
            status = RunnerJobStatus.SUCCEEDED, summary = "pushed", branch = null, commitSha = null, diffStat = null, verification = null,
            artifacts = emptyList(), diff = "diff --git a/x b/x", usage = TokenUsage.ZERO, reportedCostUsd = null, error = null, finishedAt = now,
            repositories = listOf(
                RepoResult("main", "feature/shop-1", "abc123", RunnerDiffStat(1, 2, 0), "https://git.example.com/g/shop/-/merge_requests/7", pushed = true),
            ),
        )
        val result = executor.resume(ctx, step, jobId)
        assertThat(result.failed).isFalse()
        assertThat(result.mergeRequest!!.url).isEqualTo("https://git.example.com/g/shop/-/merge_requests/7")
        assertThat(result.mergeRequest!!.targetBranch).isEqualTo("main")
        assertThat(result.stateUpdates[RunStateKeys.BRANCH]).isEqualTo("feature/shop-1")
        assertThat(result.stateUpdates[RunStateKeys.MR]).isNotNull()
        assertThat(result.artifacts.map { it.name }).containsExactly("diff")
        assertThat(result.repositories.single().pushed).isTrue()
        assertThat(result.summary).contains("delivered branch feature/shop-1")
    }

    @Test
    fun `nothing changed is a success without a merge request - a failed job takes the failure port`() {
        val (ctx, step) = context()
        val jobId = RunnerJobId(UUID.randomUUID())
        fun result(status: RunnerJobStatus) = RunnerResult(
            status = status, summary = null, branch = null, commitSha = null, diffStat = null, verification = null,
            artifacts = emptyList(), usage = TokenUsage.ZERO, reportedCostUsd = null, error = if (status == RunnerJobStatus.SUCCEEDED) null else "push rejected",
            finishedAt = now, repositories = listOf(RepoResult("main", pushed = false)),
        )
        runner.results[jobId] = result(RunnerJobStatus.SUCCEEDED)
        val nothing = executor.resume(ctx, step, jobId)
        assertThat(nothing.port).isEqualTo(WorkflowNode.PORT_SUCCESS)
        assertThat(nothing.mergeRequest).isNull()
        assertThat(nothing.summary).contains("nothing to deliver")
        assertThat(nothing.stateUpdates).doesNotContainKey(RunStateKeys.BRANCH)

        runner.results[jobId] = result(RunnerJobStatus.FAILED)
        val failed = executor.resume(ctx, step, jobId)
        assertThat(failed.port).isEqualTo(WorkflowNode.PORT_FAILURE)
        assertThat(failed.error).contains("push rejected")

        assertThat(executor.resume(ctx, step, RunnerJobId(UUID.randomUUID())).error).contains("has no result")
    }

    @Test
    fun `the action node waits for the delivery job and resumes through its strategy`() {
        val node = ActionNodeExecutor(ActionStrategies(listOf(executor)))
        val (ctx, _) = context()
        val waiting = node.execute(ctx)
        assertThat(waiting.awaitingJob).isNotNull()
        val jobId = waiting.awaitingJob!!
        runner.results[jobId] = RunnerResult(
            status = RunnerJobStatus.SUCCEEDED, summary = null, branch = "feature/shop-1", commitSha = "abc", diffStat = null, verification = null,
            artifacts = emptyList(), mergeRequestUrl = "https://mr/1", usage = TokenUsage.ZERO, reportedCostUsd = null, error = null, finishedAt = now,
        )
        // an older runner reports the legacy top-level fields for the primary repository
        val done = node.resume(ctx, jobId)
        assertThat(done.port).isEqualTo(WorkflowNode.PORT_SUCCESS)
        assertThat(done.mergeRequest!!.url).isEqualTo("https://mr/1")
    }
}
