package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.shared.runner.JobCredentialRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
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
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Least-privilege git access (ADR 0031): the level each runner step asks the runner cluster for. */
class GitAccessDerivationTest {

    private val project = Fixtures.project()
    private val catalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = ModelProfileSnapshot(id, "Test model", ModelRef(Provider.ANTHROPIC, "claude-test"), Pricing(BigDecimal.ONE, BigDecimal.ONE), true)
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private val runner = CapturingRunner()
    private val support = RunnerTaskSupport(runner, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), Duration.ofMinutes(30))

    private fun context(project: pl.mos.backoffice.projects.domain.model.Project = this.project, startedBy: Actor? = null): NodeContext {
        val run = Fixtures.runningRun(project, startedBy = startedBy)
        val node = run.definitionSnapshot.requireNode("implement")
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), emptyMap())
    }

    private fun task(executor: TaskExecutor, config: Map<String, Any?>) = ResolvedTask(
        key = "t", name = "T", executor = executor, modelProfileId = null, prompt = TaskPrompt(null, "Do {{issue.key}}"),
        skills = emptyList(), config = config, outputSchema = null, confidenceField = null,
        produces = emptyList(), consumes = emptyList(), interfaces = emptyList(), lineage = listOf("t"), ports = listOf("success", "failure"),
    )

    private fun submit(mode: RunnerMode, config: Map<String, Any?>): RunnerJobSpec {
        val executor = if (mode == RunnerMode.SPEC_KIT) TaskExecutor.SPEC_KIT else TaskExecutor.IMPLEMENT
        support.run(context(), task(executor, config), mode, null, "x")
        return runner.specs.last()
    }

    @Test
    fun `ADR 0036 - a project without Spec Kit sends specKit false and no constitution, and refuses Spec Kit steps`() {
        val off = Fixtures.project(specKit = false)
        support.run(context(off), task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x")
        val spec = runner.specs.last()
        assertThat(spec.specKit).isFalse()
        assertThat(spec.constitution).isNull()

        val refused = support.run(context(off), task(TaskExecutor.SPEC_KIT, emptyMap()), RunnerMode.SPEC_KIT, null, "x")
        assertThat(refused.failed).isTrue()
        assertThat(refused.error).contains("Spec Kit").contains("off for this project")
        assertThat(runner.specs).hasSize(1)

        val on = submit(RunnerMode.IMPLEMENT, emptyMap())
        assertThat(on.specKit).isTrue()
        assertThat(on.constitution).isEqualTo("Keep it simple.")
    }

    @Test
    fun `a step that allows it runs in an empty workspace of a project without repositories, others fail`() {
        val noCode = Fixtures.project().copy(repositories = emptyList())
        support.run(context(noCode), task(TaskExecutor.IMPLEMENT, mapOf("push" to false, "repositoryOptional" to true)), RunnerMode.IMPLEMENT, null, "x")
        val spec = runner.specs.last()
        assertThat(spec.repository).isNull()
        assertThat(spec.repositories).isEmpty()
        assertThat(spec.gitAccess).isEqualTo(GitAccess.NONE)
        assertThat(spec.prompt).doesNotContain("## Repositories")

        val refused = support.run(context(noCode), task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x")
        assertThat(refused.failed).isTrue()
        assertThat(runner.specs).hasSize(1)
    }

    @Test
    fun `ADR 0051 - each repository gets the least privileged fitting project token by name, never a push token for reading`() {
        val now = java.time.Instant.now()
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
        fun specOf(config: Map<String, Any?>): RunnerJobSpec {
            withTokens.run(context(), task(TaskExecutor.IMPLEMENT, config), RunnerMode.IMPLEMENT, null, "x")
            return runner.specs.last()
        }
        val key = project.repositories.single().key
        assertThat(specOf(mapOf("push" to false)).credentials.single()).isEqualTo(JobCredentialRef(read.id.toString(), key, "GIT_READ"))
        assertThat(specOf(emptyMap()).credentials.single()).isEqualTo(JobCredentialRef(push.id.toString(), key, "PUSH_BRANCH"))
        // only a push token: a READ job gets no project token (the pool's own read token is used instead)
        tokens.rows = listOf(push)
        assertThat(specOf(mapOf("push" to false)).credentials).isEmpty()
        // a token whose test failed is never chosen
        tokens.rows = listOf(token("broken", setOf(ScmCapability.GIT_READ, ScmCapability.PUSH_BRANCH), status = VerificationStatus.FAILED))
        assertThat(specOf(emptyMap()).credentials).isEmpty()
        // the spec only names the token, it never carries a value
        assertThat(runner.specs.last().toString()).doesNotContain("SealedSecret")
    }

    @Test
    fun `implement with push asks for PUSH_MR and opens the MR through push options`() {
        val spec = submit(RunnerMode.IMPLEMENT, emptyMap())
        assertThat(spec.gitAccess).isEqualTo(GitAccess.PUSH_MR)
        assertThat(spec.repository!!.push).isTrue()
        assertThat(spec.repository!!.mergeRequest).isNotNull()
    }

    @Test
    fun `implement without push is READ and carries no merge request`() {
        val spec = submit(RunnerMode.IMPLEMENT, mapOf("push" to false))
        assertThat(spec.gitAccess).isEqualTo(GitAccess.READ)
        assertThat(spec.repository!!.mergeRequest).isNull()
    }

    @Test
    fun `spec kit pushes its branch with PUSH_MR, an analysis without push is READ`() {
        assertThat(submit(RunnerMode.SPEC_KIT, emptyMap()).gitAccess).isEqualTo(GitAccess.PUSH_MR)
        assertThat(submit(RunnerMode.SPEC_KIT, mapOf("push" to "false", "phase" to "ANALYZE")).gitAccess).isEqualTo(GitAccess.READ)
    }

    @Test
    fun `benchmarks read, jobs without a repository get nothing`() {
        val repo = RunnerRepository("https://git.example.com/g/shop.git", "main", "eval/1", push = true)
        // an evaluation attempt never pushes, even if a repository says push
        assertThat(GitAccess.derive(RunnerMode.EVAL_CODING, repo)).isEqualTo(GitAccess.READ)
        assertThat(GitAccess.derive(RunnerMode.IMPLEMENT, null)).isEqualTo(GitAccess.NONE)
        assertThat(GitAccess.derive(RunnerMode.IMPLEMENT, repo.copy(push = false))).isEqualTo(GitAccess.READ)
        assertThat(GitAccess.derive(RunnerMode.IMPLEMENT, repo)).isEqualTo(GitAccess.PUSH_MR)
    }

    @Test
    fun `ADR 0040 - steps of a private project started by its owner ask for the owner's personal runner`() {
        val owner = UUID.randomUUID()
        val person = Actor(PrincipalKind.USER, owner, "Owner")
        val private = Fixtures.project().copy(personal = true)
        support.run(context(private, person), task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x")
        assertThat(runner.specs.last().personalRunnerOf).isEqualTo(owner)
        // a team project, or a private one started by the system, uses the shared fleet
        support.run(context(project, person), task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x")
        assertThat(runner.specs.last().personalRunnerOf).isNull()
        support.run(context(private, Actor(PrincipalKind.SYSTEM, null, "system")), task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x")
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
        val subscription = support.complete(ctx, task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x", jobId)
        assertThat(subscription.cost.amount.signum()).isZero()
        // ADR 0042: the API-equivalent cost is kept next to the zero spend
        assertThat(subscription.apiCost!!.amount).isEqualByComparingTo("0.42")
        assertThat(subscription.usage.inputTokens).isEqualTo(100)
        runner.results[jobId] = result(subscription = false)
        val metered = support.complete(ctx, task(TaskExecutor.IMPLEMENT, emptyMap()), RunnerMode.IMPLEMENT, null, "x", jobId)
        assertThat(metered.cost.amount).isEqualByComparingTo("0.42")
    }

    private class CapturingRunner : AgentRunner {
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
