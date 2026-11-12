package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.nodes.ActionNodeExecutor
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Least-privilege git access (ADR 0031, 0063): agent jobs only read (every repository READ, never a push token); only the
 * `DELIVER_CHANGES` job gets PUSH_MR and a token that may push.
 */
class GitAccessDerivationTest {

    private val project = Fixtures.project()
    private val catalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = ModelProfileSnapshot(id, "Test model", ModelRef(Provider.ANTHROPIC, "claude-test"), Pricing(BigDecimal.ONE, BigDecimal.ONE), true)
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private val runner = CapturingRunner()
    private val support = RunnerTaskSupport(runner, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), Duration.ofMinutes(30))

    private fun context(project: pl.mos.backoffice.projects.domain.model.Project = this.project, startedBy: Actor? = null, nodeId: String = "implement"): NodeContext {
        val run = Fixtures.runningRun(project, startedBy = startedBy)
        val node = run.definitionSnapshot.requireNode(nodeId)
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), emptyMap())
    }

    private fun task(agent: AgentSnapshot, config: Map<String, Any?>) = ResolvedTask(
        key = "t", name = "T", agent = agent, modelProfileId = null, instructions = ("Do {{issue.key}}"),
        skills = emptyList(), config = config, outputSchema = null, confidenceField = null,
        produces = emptyList(), consumes = emptyList(), category = null, lineage = listOf("t"), ports = listOf("success", "failure"),
    )

    private fun submit(config: Map<String, Any?>): RunnerJobSpec {
        support.run(context(), task(TestAgents.OPENCODE, config), "x")
        return runner.specs.last()
    }

    @Test
    fun `ADR 0063 - an agent job only reads - READ, no branch to push, no merge request, no commit subject, mode IMPLEMENT`() {
        // even a step that still carries the former git keys does not push
        val spec = submit(mapOf("push" to true, "mergeRequest" to true, "branchTemplate" to "x/{{run.short}}"))
        assertThat(spec.mode).isEqualTo(RunnerMode.IMPLEMENT)
        assertThat(spec.gitAccess).isEqualTo(GitAccess.READ)
        assertThat(spec.repositories).allSatisfy {
            assertThat(it.access).isEqualTo(GitAccess.READ)
            assertThat(it.pushBranch).isNull()
            assertThat(it.mergeRequest).isNull()
        }
        assertThat(spec.repository!!.push).isFalse()
        assertThat(spec.repository!!.mergeRequest).isNull()
        assertThat(spec.commitSubject).isNull()
        assertThat(spec.collectDiff).isTrue()
        assertThat(spec.prompt).contains("a delivery step of the workflow commits and pushes them").contains("do not commit and do")
    }

    @Test
    fun `a step that allows it runs in an empty workspace of a project without repositories, others fail`() {
        val noCode = Fixtures.project().copy(repositories = emptyList())
        support.run(context(noCode), task(TestAgents.OPENCODE, mapOf("repositoryOptional" to true)), "x")
        val spec = runner.specs.last()
        assertThat(spec.repository).isNull()
        assertThat(spec.repositories).isEmpty()
        assertThat(spec.gitAccess).isEqualTo(GitAccess.NONE)
        assertThat(spec.prompt).doesNotContain("## Repositories")

        val refused = support.run(context(noCode), task(TestAgents.OPENCODE, emptyMap()), "x")
        assertThat(refused.failed).isTrue()
        assertThat(refused.error).contains("has none")
        assertThat(runner.specs).hasSize(1)
    }

    @Test
    fun `ADR 0051 - agent jobs get the read-only project token, never a push token - the delivery the push token`() {
        val now = Instant.now()
        fun token(name: String, caps: Set<ScmCapability>, powers: List<String> = emptyList(), status: VerificationStatus = VerificationStatus.OK) =
            ScmConnection(
                ScmConnectionId.new(), project.id, "main", name, ScmProvider.GITLAB, caps,
                SealedSecret("k1", "w", "c"), null,
                ScmVerification(status, now, null, null, null, null, caps, emptySet(), powers, emptyList()),
                now, Actor.SYSTEM, now, Actor.SYSTEM,
            )
        val read = token("reader", setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ))
        val push = token("pusher", setOf(ScmCapability.GIT_READ, ScmCapability.PUSH_BRANCH), listOf("git push"))
        val tokens = object : ScmConnectionRepository {
            var rows = listOf(read, push)
            override fun save(connection: ScmConnection) = connection
            override fun findById(id: ScmConnectionId) = rows.firstOrNull { it.id == id }
            override fun findByProject(projectId: ProjectId) = rows.filter { it.projectId == projectId }
            override fun findAll() = rows
            override fun delete(id: ScmConnectionId) = Unit
            override fun replaceSecret(id: ScmConnectionId, expected: SealedSecret, replacement: SealedSecret) = false
        }
        val withTokens = RunnerTaskSupport(runner, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), Duration.ofMinutes(30), scmConnections = tokens)
        val delivery = DeliverChangesExecutor(runner, TemplateRenderer(), TemplateModelBuilder(), scmConnections = tokens)
        fun specOf(config: Map<String, Any?>): RunnerJobSpec {
            withTokens.run(context(), task(TestAgents.OPENCODE, config), "x")
            return runner.specs.last()
        }
        fun deliverySpec(): RunnerJobSpec {
            val ctx = context(nodeId = DefaultWorkflows.NODE_DELIVER)
            delivery.execute(ctx, ActionNodeExecutor.stepOf(ctx.node as WorkflowNode.Action))
            return runner.specs.last()
        }
        val key = project.repositories.single().key
        assertThat(specOf(emptyMap()).credentials.single()).isEqualTo(JobCredentialRef(read.id.toString(), key, "GIT_READ"))
        assertThat(deliverySpec().credentials.single()).isEqualTo(JobCredentialRef(push.id.toString(), key, "PUSH_BRANCH"))
        // only a push token: a READ job gets no project token (the pool's own read token is used instead)
        tokens.rows = listOf(push)
        assertThat(specOf(emptyMap()).credentials).isEmpty()
        // a token whose test failed is never chosen
        tokens.rows = listOf(token("broken", setOf(ScmCapability.GIT_READ, ScmCapability.PUSH_BRANCH), status = VerificationStatus.FAILED))
        assertThat(deliverySpec().credentials).isEmpty()
        // the spec only names the token, it never carries a value
        assertThat(runner.specs.last().toString()).doesNotContain("SealedSecret")

        // ADR 0051 step 4: gitlab-read takes the verified read-only CI_READ token, never one with write powers
        val ci = token("ci-reader", setOf(ScmCapability.GIT_READ, ScmCapability.CI_READ))
        val apiWriter = token("api-writer", setOf(ScmCapability.CI_READ, ScmCapability.MR_COMMENT), listOf("api scope"))
        tokens.rows = listOf(apiWriter, ci, push)
        val withTool = specOf(mapOf("agentTools" to listOf("gitlab-read")))
        assertThat(withTool.agentTools).containsExactly(pl.mos.backoffice.shared.runner.AgentTool("gitlab-read", key))
        assertThat(withTool.credentials).contains(JobCredentialRef(ci.id.toString(), key, "CI_READ"))
        assertThat(withTool.credentials.map { it.ref }).doesNotContain(apiWriter.id.toString())

        tokens.rows = listOf(apiWriter, push)
        val refused = withTokens.run(context(), task(TestAgents.OPENCODE, mapOf("agentTools" to listOf("gitlab-read"))), "x")
        assertThat(refused.failed).isTrue()
        assertThat(refused.error).contains("read-only token with CI_READ")
        val unknown = withTokens.run(context(), task(TestAgents.OPENCODE, mapOf("agentTools" to listOf("root-shell"))), "x")
        assertThat(unknown.error).contains("unknown agent tool 'root-shell'")
    }

    @Test
    fun `only delivery jobs push, benchmarks read, jobs without a repository get nothing`() {
        val repo = RunnerRepository("https://git.example.com/g/shop.git", "main", "eval/1", push = true)
        // an evaluation attempt and an agent job never push, even if a repository says push
        assertThat(GitAccess.derive(RunnerMode.EVAL_CODING, repo)).isEqualTo(GitAccess.READ)
        assertThat(GitAccess.derive(RunnerMode.IMPLEMENT, repo)).isEqualTo(GitAccess.READ)
        assertThat(GitAccess.derive(RunnerMode.IMPLEMENT, null)).isEqualTo(GitAccess.NONE)
        assertThat(GitAccess.derive(RunnerMode.DELIVER, repo.copy(push = false))).isEqualTo(GitAccess.READ)
        assertThat(GitAccess.derive(RunnerMode.DELIVER, repo)).describedAs("a legacy single-repository spec never pushes; DELIVER sends repositories").isEqualTo(GitAccess.READ)
        assertThat(GitAccess.pushingMode(RunnerMode.DELIVER)).isTrue()
        assertThat(GitAccess.pushingMode(RunnerMode.IMPLEMENT)).isFalse()
        // jobs stored before ADR 0063 with mode SPEC_KIT read as agent jobs
        assertThat(RunnerMode.of("SPEC_KIT")).isEqualTo(RunnerMode.IMPLEMENT)
        assertThat(RunnerMode.of("DELIVER")).isEqualTo(RunnerMode.DELIVER)
    }

    @Test
    fun `ADR 0040 - steps of a private project started by its owner ask for the owner's personal runner`() {
        val owner = UUID.randomUUID()
        val person = Actor(PrincipalKind.USER, owner, "Owner")
        val private = Fixtures.project().copy(personal = true)
        support.run(context(private, person), task(TestAgents.OPENCODE, emptyMap()), "x")
        assertThat(runner.specs.last().personalRunnerOf).isEqualTo(owner)
        // a team project, or a private one started by the system, uses the shared fleet
        support.run(context(project, person), task(TestAgents.OPENCODE, emptyMap()), "x")
        assertThat(runner.specs.last().personalRunnerOf).isNull()
        support.run(context(private, Actor(PrincipalKind.SYSTEM, null, "system")), task(TestAgents.OPENCODE, emptyMap()), "x")
        assertThat(runner.specs.last().personalRunnerOf).isNull()
    }

    @Test
    fun `ADR 0040 - a subscription run costs nothing, a gateway run its reported cost`() {
        val jobId = RunnerJobId(UUID.randomUUID())
        fun result(subscription: Boolean) = RunnerResult(
            status = RunnerJobStatus.SUCCEEDED, summary = "done", branch = null, commitSha = null, diffStat = null, verification = null,
            artifacts = emptyList(), usage = pl.mos.backoffice.shared.llm.TokenUsage(100, 20, 0, 0), reportedCostUsd = BigDecimal("0.42"),
            error = null, finishedAt = now, subscription = subscription,
        )
        val ctx = context()
        runner.results[jobId] = result(subscription = true)
        val subscription = support.complete(ctx, task(TestAgents.OPENCODE, emptyMap()), "x", jobId)
        assertThat(subscription.cost.amount.signum()).isZero()
        // ADR 0042: the API-equivalent cost is kept next to the zero spend
        assertThat(subscription.apiCost!!.amount).isEqualByComparingTo("0.42")
        assertThat(subscription.usage.inputTokens).isEqualTo(100)
        // ADR 0063: an agent job opens no merge request and does not set the run's branch
        assertThat(subscription.mergeRequest).isNull()
        assertThat(subscription.stateUpdates).doesNotContainKey("branch")
        runner.results[jobId] = result(subscription = false)
        val metered = support.complete(ctx, task(TestAgents.OPENCODE, emptyMap()), "x", jobId)
        assertThat(metered.cost.amount).isEqualByComparingTo("0.42")
    }

    @Test
    fun `a failed step reports the runner's reason and the agent's own account of where it stopped`() {
        val jobId = RunnerJobId(UUID.randomUUID())
        val report = "I stopped at Task 3: the sandbox has no Docker.\n\n**What I changed:** only work-log.md.\n\n**Details:** long text"
        runner.results[jobId] = RunnerResult(
            status = RunnerJobStatus.FAILED, summary = report, branch = null, commitSha = null, diffStat = null, verification = null,
            artifacts = emptyList(), usage = pl.mos.backoffice.shared.llm.TokenUsage.ZERO, reportedCostUsd = null,
            error = "verifyCommand exited with code 1", finishedAt = now,
        )
        val failed = support.complete(context(), task(TestAgents.OPENCODE, emptyMap()), "x", jobId)
        assertThat(failed.error).startsWith("verifyCommand exited with code 1\n\nThe agent reports:\nI stopped at Task 3")
        assertThat(failed.error).contains("only work-log.md").doesNotContain("long text").endsWith("(full report in the step's result and work log)")
    }

    @Test
    fun `ADR 0063 - output files of the template and a step's legacy collectArtifacts are collected`() {
        val spec = submit(mapOf("collectArtifacts" to listOf("extra.md")))
        assertThat(spec.collectArtifacts).containsExactly("extra.md", "agent-questions.json")
        support.run(context(), task(TestAgents.OPENCODE, emptyMap()).copy(outputFiles = listOf("specs/**/spec.md")), "x")
        assertThat(runner.specs.last().collectArtifacts).containsExactly("specs/**/spec.md", "agent-questions.json")
        assertThat(submit(emptyMap()).collectArtifacts).containsAll(RunnerTaskSupport.DEFAULT_COLLECT)
    }

    internal class CapturingRunner : AgentRunner {
        val specs = mutableListOf<RunnerJobSpec>()
        val results = mutableMapOf<RunnerJobId, RunnerResult>()
        override fun result(jobId: RunnerJobId): RunnerResult? = results[jobId]
        override fun submit(spec: RunnerJobSpec, continuation: WorkRequest): RunnerJobId {
            specs += spec
            return RunnerJobId(UUID.randomUUID())
        }
        @Deprecated("test") override fun start(spec: RunnerJobSpec): RunnerJobId = throw UnsupportedOperationException()
        @Deprecated("test") override fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult = throw UnsupportedOperationException()
        override fun status(jobId: RunnerJobId): RunnerJobStatus = RunnerJobStatus.CREATED
        override fun cancel(jobId: RunnerJobId) = Unit
        override fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine> = emptyList()
        override fun isAvailable() = true
    }
}
