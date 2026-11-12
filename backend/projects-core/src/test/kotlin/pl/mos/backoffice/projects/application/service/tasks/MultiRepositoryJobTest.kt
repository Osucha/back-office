package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.nodes.ActionNodeExecutor
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.RepoResult
import pl.mos.backoffice.shared.runner.RunnerDiffStat
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR 0034, 0063: the job spec of a step in a workflow with several sources (contract with the execution plane) and the
 * per-repository result mapping; agents read every source, the delivery pushes the project repositories.
 */
class MultiRepositoryJobTest {

    private val app = GitRepository("app", "https://git.example.com/acme/app.git", "main", RepositoryPurpose.SOURCE, primary = true)
    private val docs = GitRepository("docs", "https://git.example.com/acme/docs.git", "develop", RepositoryPurpose.DOCS, "Docs <b>site</b>")
    private val lib = GitRepository("lib", "https://git.example.com/acme/lib.git", "trunk", RepositoryPurpose.OTHER)
    private val project = Fixtures.project().copy(repositories = listOf(app, docs, lib))
    private val threeSources = listOf(WorkflowSource("docs"), WorkflowSource("app"), WorkflowSource("lib"))
    private val catalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = ModelProfileSnapshot(id, "Test model", ModelRef(Provider.ANTHROPIC, "claude-test"), Pricing(BigDecimal.ONE, BigDecimal.ONE), true)
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private val runner = ScriptedRunner()

    private val support = RunnerTaskSupport(runner, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), Duration.ofMinutes(30))
    private val delivery = DeliverChangesExecutor(runner, TemplateRenderer(), TemplateModelBuilder())

    private fun context(
        p: Project = project,
        state: Map<String, Any?> = emptyMap(),
        setupCommand: String? = null,
        sources: List<WorkflowSource> = threeSources,
        nodeId: String = "implement",
    ): NodeContext {
        val run = Fixtures.runningRun(p, Fixtures.issueWorkflow(p).copy(setupCommand = setupCommand, sources = sources))
        val node = run.definitionSnapshot.requireNode(nodeId)
        val started = run.nodeStarted(node, now)
        return NodeContext(started, p, node, started.nodes.last(), state)
    }

    private fun task(config: Map<String, Any?>) = ResolvedTask(
        key = "t", name = "T", agent = TestAgents.OPENCODE, modelProfileId = null, instructions = ("Do {{issue.key}} in {{repos}}"),
        skills = emptyList(), config = config, outputSchema = null, confidenceField = null,
        produces = emptyList(), consumes = emptyList(), category = null, lineage = listOf("t"), ports = listOf("success", "failure"),
    )

    private fun submit(config: Map<String, Any?> = emptyMap(), p: Project = project, sources: List<WorkflowSource> = threeSources): RunnerJobSpec {
        support.run(context(p, sources = sources), task(config), "x")
        return runner.specs.last()
    }

    private fun deliver(config: Map<String, Any?> = emptyMap(), sources: List<WorkflowSource> = threeSources): RunnerJobSpec {
        val ctx = context(sources = sources, nodeId = DefaultWorkflows.NODE_DELIVER)
        val node = ctx.node as WorkflowNode.Action
        val step = ActionNodeExecutor.stepOf(node.copy(action = node.action.copy(config = config)))
        assertThat(delivery.execute(ctx, step).error).isNull()
        return runner.specs.last()
    }

    @Test
    fun `every source becomes a read-only job repository with its own path - the primary is the project's`() {
        val spec = submit()
        assertThat(spec.repositories.map { it.key }).containsExactly("docs", "app", "lib")
        val byKey = spec.repositories.associateBy { it.key }
        with(byKey.getValue("app")) {
            assertThat(url).isEqualTo(app.url)
            assertThat(baseBranch).isEqualTo("main")
            assertThat(access).isEqualTo(GitAccess.READ)
            assertThat(path).isEqualTo("app")
            assertThat(pushBranch).isNull()
            assertThat(primary).isTrue()
        }
        assertThat(byKey.getValue("docs").baseBranch).isEqualTo("develop")
        assertThat(spec.repositories.count { it.primary }).isEqualTo(1)
        assertThat(spec.gitAccess).isEqualTo(GitAccess.READ)
        with(spec.repository!!) {
            assertThat(url).isEqualTo(app.url)
            assertThat(baseRef).isEqualTo("main")
            assertThat(push).isFalse()
        }
    }

    @Test
    fun `the delivery pushes every project source, or the listed ones, each with its merge request`() {
        val spec = deliver(mapOf("branchTemplate" to "feature/acme-42"))
        assertThat(spec.repositories.map { "${it.key}:${it.access}:${it.pushBranch}:${it.primary}" })
            .containsExactly("docs:PUSH_MR:feature/acme-42:false", "app:PUSH_MR:feature/acme-42:true", "lib:PUSH_MR:feature/acme-42:false")
        assertThat(spec.repositories).allSatisfy { assertThat(it.mergeRequest).isNotNull() }
        val docsOnly = deliver(mapOf("repositories" to listOf("docs")))
        assertThat(docsOnly.repositories.single().let { it.key to it.primary }).isEqualTo("docs" to true)
        assertThat(docsOnly.repository!!.url).isEqualTo(docs.url)
        assertThat(docsOnly.repository!!.mergeRequest!!.targetBranch).isEqualTo("develop")
    }

    @Test
    fun `a delivery branch that is the base branch of any delivered repository is refused`() {
        val spec = deliver(mapOf("branchTemplate" to "develop"))
        assertThat(spec.repositories.mapNotNull { it.pushBranch }.toSet().single()).startsWith("run/")
    }

    @Test
    fun `the default workflow keeps one repository - the primary, as one repositories entry`() {
        val spec = submit(p = Fixtures.project(), sources = emptyList())
        assertThat(spec.repositories.single().let { listOf(it.key, it.access, it.primary, it.path) }).containsExactly("main", GitAccess.READ, true, "main")
        assertThat(spec.gitAccess).isEqualTo(GitAccess.READ)
    }

    @Test
    fun `a step works in the run directory by default, with its own session and the workflow's setup command (ADR 0060)`() {
        val ctx = context(setupCommand = "npm ci")
        support.run(ctx, task(emptyMap()), "x")
        val spec = runner.specs.last()
        assertThat(spec.workspace).isEqualTo(
            RunnerWorkspace(RunnerWorkspace.RUN_KEY, RunnerWorkspace.nameFor(ctx.run.id.value, RunnerWorkspace.RUN_KEY), "s-implement", false, "npm ci"),
        )
        assertThat(spec.setupCommand).describedAs("the run directory runs the setup").isNull()
        assertThat(spec.prompt).contains("Run directory: this step works in the run's shared directory")
        assertThat(spec.agentHome).isEqualTo(TestAgents.OPENCODE.key)
        assertThat(spec.access).describedAs("FULL is not sent").isNull()
    }

    @Test
    fun `a FRESH agent keeps the run directory without a session, a SEPARATE agent gets a fresh copy of the sources (ADR 0062, 0063)`() {
        val fresh = task(emptyMap()).let { it.copy(agent = it.agent.copy(session = AgentSession.FRESH)) }
        support.run(context(), fresh, "x")
        assertThat(runner.specs.last().workspace!!.let { it.key to it.sessionRole }).isEqualTo(RunnerWorkspace.RUN_KEY to null)
        val alone = task(emptyMap()).let { it.copy(agent = it.agent.copy(access = AgentAccess(directory = AgentDirectory.SEPARATE))) }
        support.run(context(setupCommand = "npm ci"), alone, "x")
        val spec = runner.specs.last()
        assertThat(spec.workspace).isNull()
        assertThat(spec.repositories.map { it.key }).containsExactly("docs", "app", "lib")
        assertThat(spec.setupCommand).isEqualTo("npm ci")
        assertThat(spec.prompt).doesNotContain("Run directory").contains("thrown away after the step")
    }

    @Test
    fun `a legacy sessionRole is the step's own session role (ADR 0044 compatibility)`() {
        assertThat(submit(mapOf(RunDirectory.LEGACY_SESSION_ROLE to "reviewer")).workspace!!.sessionRole).isEqualTo("reviewer")
        assertThat(submit(mapOf(RunDirectory.LEGACY_SESSION_ROLE to "Not Valid")).workspace!!.sessionRole).isEqualTo("s-implement")
    }

    @Test
    fun `a prepare step reads only and asks nothing`() {
        val spec = submit(mapOf(RunDirectory.PREPARE_ONLY to true))
        assertThat(spec.workspace!!.prepareOnly).isTrue()
        assertThat(spec.workspace!!.sessionRole).isNull()
        assertThat(spec.gitAccess).isEqualTo(GitAccess.READ)
        assertThat(spec.collectArtifacts).describedAs("a prepare step asks nothing").doesNotContain("agent-questions.json")
    }

    @Test
    fun `a prepare step on an agent with a SEPARATE directory fails the step before any job is submitted`() {
        val before = runner.specs.size
        val alone = task(mapOf(RunDirectory.PREPARE_ONLY to true)).let { it.copy(agent = it.agent.copy(access = AgentAccess(directory = AgentDirectory.SEPARATE))) }
        assertThat(support.run(context(), alone, "x").error).contains("directory").contains("prepare step")
        assertThat(runner.specs).hasSize(before)
    }

    @Test
    fun `the prompt lists the repositories and descriptions stay data`() {
        val spec = submit()
        assertThat(spec.prompt).contains("## Repositories")
            .contains("- app: /workspace/app (primary, working directory), purpose SOURCE")
            .contains("- docs: /workspace/docs, purpose DOCS, base branch develop")
            .contains("- lib: /workspace/lib, purpose OTHER, base branch trunk, your changes stay in the run directory")
            .contains("untrusted-data source=\"repositories.docs.description\"")
        // the agent notice stays the last block
        assertThat(spec.prompt.trimEnd()).endsWith("mention it in your summary.")
    }

    @Test
    fun `an agent step in a project without repositories, or with unknown sources, fails before any job is submitted`() {
        val result = support.run(context(project.copy(repositories = emptyList()), sources = emptyList()), task(emptyMap()), "x")
        assertThat(result.failed).isTrue()
        assertThat(result.error).contains("has none")
        val unknown = support.run(context(sources = listOf(WorkflowSource("web"))), task(emptyMap()), "x")
        assertThat(unknown.error).contains("sources of the workflow").contains("web")
        assertThat(runner.specs).isEmpty()
    }

    @Test
    fun `agent results are mapped per repository, without a merge request`() {
        val jobId = RunnerJobId(UUID.randomUUID())
        runner.result = result(
            repositories = listOf(
                RepoResult("docs", null, "d0c5", RunnerDiffStat(1, 10, 0)),
                RepoResult("app", null, "a99", RunnerDiffStat(3, 40, 2)),
                RepoResult("lib", null, "11b"),
            ),
        )
        val mapped = support.complete(context(state = mapOf(RunStateKeys.REPOSITORIES to mapOf("old" to mapOf("pushed" to false)))), task(emptyMap()), "x", jobId)

        assertThat(mapped.port).isEqualTo(WorkflowNode.PORT_SUCCESS)
        assertThat(mapped.repositories.map { "${it.key}:${it.pushed}:${it.commitSha}:${it.access}" })
            .containsExactly("docs:false:d0c5:READ", "app:false:a99:READ", "lib:false:11b:READ")
        assertThat(mapped.repositories.single { it.key == "docs" }.let { listOf(it.url, it.purpose, it.diffStat!!.insertions) })
            .containsExactly(docs.url, RepositoryPurpose.DOCS, 10)
        assertThat(mapped.mergeRequest).isNull()
        @Suppress("UNCHECKED_CAST")
        val state = mapped.stateUpdates[RunStateKeys.REPOSITORIES] as Map<String, Map<String, Any?>>
        assertThat(state.keys).containsExactly("old", "docs", "app", "lib")
    }

    @Test
    fun `delivery results are mapped per repository and the run merge request is the primary one, else a pushed one`() {
        val ctx = context(nodeId = DefaultWorkflows.NODE_DELIVER)
        val step = ActionNodeExecutor.stepOf(ctx.node as WorkflowNode.Action)
        val jobId = RunnerJobId(UUID.randomUUID())
        runner.result = result(
            repositories = listOf(
                RepoResult("docs", "feature/acme-42", "d0c5", RunnerDiffStat(1, 10, 0), "https://git.example.com/acme/docs/-/merge_requests/7", pushed = true),
                RepoResult("app", "feature/acme-42", "a99", RunnerDiffStat(3, 40, 2), "https://git.example.com/acme/app/-/merge_requests/12", pushed = true),
                RepoResult("lib", null, null, null, null, pushed = false),
            ),
        )
        val mapped = delivery.resume(ctx, step, jobId)
        assertThat(mapped.repositories.map { "${it.key}:${it.pushed}:${it.access}" }).containsExactly("docs:true:PUSH_MR", "app:true:PUSH_MR", "lib:false:PUSH_MR")
        assertThat(mapped.mergeRequest!!.url).isEqualTo("https://git.example.com/acme/app/-/merge_requests/12")
        assertThat(mapped.mergeRequest!!.targetBranch).isEqualTo("main")

        runner.result = result(
            repositories = listOf(
                RepoResult("docs", "feature/acme-42", "d0c5", null, "https://git.example.com/acme/docs/-/merge_requests/7", pushed = true),
                RepoResult("app", null, null, null, null, pushed = false, error = "push rejected: pre-receive hook declined"),
            ),
        )
        val fallback = delivery.resume(ctx, step, jobId)
        assertThat(fallback.repositories.single { it.key == "app" }.error).contains("pre-receive")
        assertThat(fallback.mergeRequest!!.url).isEqualTo("https://git.example.com/acme/docs/-/merge_requests/7")
        assertThat(fallback.mergeRequest!!.targetBranch).isEqualTo("develop")
    }

    @Test
    fun `an older runner without per-repository results is read as the primary repository`() {
        val ctx = context(nodeId = DefaultWorkflows.NODE_DELIVER)
        val step = ActionNodeExecutor.stepOf(ctx.node as WorkflowNode.Action)
        runner.result = result(repositories = emptyList()).copy(
            branch = "feature/acme-42", commitSha = "abc", diffStat = RunnerDiffStat(2, 5, 1), mergeRequestUrl = "https://git.example.com/acme/app/-/merge_requests/3",
        )
        val mapped = delivery.resume(ctx, step, RunnerJobId(UUID.randomUUID()))
        val primary = mapped.repositories.single { it.primary }
        assertThat(listOf(primary.key, primary.commitSha, primary.pushed, primary.mergeRequestUrl))
            .containsExactly("app", "abc", true, "https://git.example.com/acme/app/-/merge_requests/3")
        assertThat(mapped.repositories.filter { !it.primary }).allSatisfy { assertThat(it.pushed).isFalse(); assertThat(it.commitSha).isNull() }
        assertThat(mapped.mergeRequest!!.url).isEqualTo("https://git.example.com/acme/app/-/merge_requests/3")
    }

    private fun result(repositories: List<RepoResult>) = RunnerResult(
        status = RunnerJobStatus.SUCCEEDED, summary = "done", branch = "feature/acme-42", commitSha = "a99", diffStat = null, verification = null,
        artifacts = emptyList(), usage = TokenUsage(10, 5), reportedCostUsd = null, error = null, finishedAt = now, repositories = repositories,
    )

    private class ScriptedRunner : AgentRunner {
        val specs = mutableListOf<RunnerJobSpec>()
        var result: RunnerResult? = null
        override fun submit(spec: RunnerJobSpec, continuation: WorkRequest): RunnerJobId {
            specs += spec
            return RunnerJobId(UUID.randomUUID())
        }
        override fun result(jobId: RunnerJobId): RunnerResult? = result
        @Deprecated("test") override fun start(spec: RunnerJobSpec): RunnerJobId = throw UnsupportedOperationException()
        @Deprecated("test") override fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult = throw UnsupportedOperationException()
        override fun status(jobId: RunnerJobId): RunnerJobStatus = RunnerJobStatus.CREATED
        override fun cancel(jobId: RunnerJobId) = Unit
        override fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine> = emptyList()
        override fun isAvailable() = true
    }
}
