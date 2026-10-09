package pl.mos.backoffice.projects.application.service.tasks

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

    private fun context(project: pl.mos.backoffice.projects.domain.model.Project = this.project): NodeContext {
        val run = Fixtures.runningRun(project)
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

    private class CapturingRunner : AgentRunner {
        val specs = mutableListOf<RunnerJobSpec>()
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
