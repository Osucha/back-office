package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.nodes.HumanGateNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.TaskExecutors
import pl.mos.backoffice.projects.application.service.nodes.TaskNodeExecutor
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CoverageStatus
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/**
 * Rework loops (ADR 0019): a gate's revise comment reaches only the step it sends the run back to, a failed step
 * keeps it for the retry, the implementer's rework prompt carries the latest review, and a review against the
 * specification with a MISSING requirement requests changes.
 */
class FeedbackAndReworkTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val models = TemplateModelBuilder()
    private val renderer = TemplateRenderer()

    private fun task(key: String = "step", produces: List<String> = listOf("result"), prompt: TaskPrompt = TaskPrompt(null, "x"), schema: String? = null) = ResolvedTask(
        key = key, name = key, executor = TaskExecutor.LLM, modelProfileId = null, prompt = prompt, skills = emptyList(), config = emptyMap(),
        outputSchema = schema, confidenceField = null, produces = produces, consumes = emptyList(), interfaces = emptyList(), lineage = listOf(key),
        ports = listOf("success", "failure"),
    )

    // ------------------------------------------------------------------------------------------ task node with a scripted strategy

    private var nextResult: NodeResult = NodeResult.success(summary = "done")
    private val strategy = object : TaskExecutorStrategy {
        override val executor = TaskExecutor.LLM
        override fun execute(context: NodeContext, task: ResolvedTask): NodeResult = nextResult
        override fun resume(context: NodeContext, task: ResolvedTask, jobId: RunnerJobId): NodeResult = NodeResult.success(summary = "job done")
    }
    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask = task(key)
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }
    private val taskNode = TaskNodeExecutor(catalog, TaskExecutors(listOf(strategy)))

    private fun context(nodeId: String, state: Map<String, Any?>, run: WorkflowRun = Fixtures.runningRun(project, definition)): NodeContext {
        val node = definition.requireNode(nodeId)
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), state)
    }

    /** What the engine does with node updates: `feedback` is an appender channel, every other key is replaced. */
    private fun Map<String, Any?>.after(result: NodeResult): Map<String, Any?> {
        val next = HashMap(this)
        result.stateUpdates.forEach { (k, v) ->
            if (k == RunStateKeys.FEEDBACK) next[k] = (this[k] as? List<*>).orEmpty() + (v as List<*>) else next[k] = v
        }
        return next
    }

    private fun feedbackFor(nodeId: String, state: Map<String, Any?>): String = models.build(context(nodeId, state))["feedback"] as String

    private fun revise(comment: String, state: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val decided = state + (RunStateKeys.DECISION to mapOf("option" to "revise", "comment" to comment))
        return decided.after(HumanGateNodeExecutor().execute(context("spec-gate", decided)))
    }

    @Test
    fun `a spec-gate comment reaches the specify step only, not plan, tasks or implement`() {
        val afterGate = revise("name the error message")
        assertThat(RunStateKeys.pendingFeedback(afterGate)).containsExactly("[Spec ok?] name the error message")
        assertThat(feedbackFor("specify", afterGate)).isEqualTo("[Spec ok?] name the error message")

        val afterSpecify = afterGate.after(taskNode.execute(context("specify", afterGate)))

        assertThat(RunStateKeys.pendingFeedback(afterSpecify)).isEmpty()
        listOf("plan", "tasks", "implement").forEach { assertThat(feedbackFor(it, afterSpecify)).describedAs(it).isEmpty() }
        assertThat(renderer.render("Plan{{#feedback}}\nFeedback: {{feedback}}{{/feedback}}", models.build(context("plan", afterSpecify))).text).isEqualTo("Plan")
        // the step that received it still sees it on a later attempt; the history keeps every comment
        assertThat(feedbackFor("specify", afterSpecify)).isEqualTo("[Spec ok?] name the error message")
        assertThat(models.build(context("plan", afterSpecify))["feedbackHistory"]).isEqualTo("[Spec ok?] name the error message")
    }

    @Test
    fun `a second revise adds to the comments the step already received`() {
        val first = revise("one").let { it.after(taskNode.execute(context("specify", it))) }
        val second = revise("two", first)
        assertThat(feedbackFor("specify", second)).isEqualTo("[Spec ok?] one\n\n[Spec ok?] two")
        val consumed = second.after(taskNode.execute(context("specify", second)))
        assertThat(RunStateKeys.feedbackOf(consumed, "specify")).containsExactly("[Spec ok?] one", "[Spec ok?] two")
        assertThat(feedbackFor("clarify", consumed)).isEmpty()
    }

    @Test
    fun `a failed step and a step waiting for its runner job keep the pending feedback until they complete`() {
        val afterGate = revise("retry with tests")

        nextResult = NodeResult.failure("model unavailable")
        val failed = taskNode.execute(context("specify", afterGate))
        assertThat(failed.stateUpdates).doesNotContainKey(RunStateKeys.PENDING_FEEDBACK)

        nextResult = NodeResult.awaiting(RunnerJobId(UUID.randomUUID()))
        val waiting = taskNode.execute(context("specify", afterGate))
        assertThat(waiting.stateUpdates).doesNotContainKey(RunStateKeys.PENDING_FEEDBACK)

        val resumed = taskNode.resume(context("specify", afterGate), RunnerJobId(UUID.randomUUID()))
        assertThat(resumed.stateUpdates).containsEntry(RunStateKeys.PENDING_FEEDBACK, null)
        assertThat(RunStateKeys.feedbackOf(afterGate.after(resumed), "specify")).containsExactly("[Spec ok?] retry with tests")
    }

    // ------------------------------------------------------------------------------------------ rework after the AI review

    /** The rework part of the built-in implement prompts (BuiltInLibrary.REVIEW_SECTION and FEEDBACK_SECTION). */
    private val reworkTemplate = """
        Implement {{issue.key}}
        {{#artifacts.review}}
        ## Latest review findings
        {{artifacts.review}}
        {{/artifacts.review}}
        {{#feedback}}
        ## Feedback on the previous attempt of this step (address every point)
        {{feedback}}
        {{/feedback}}
    """.trimIndent()

    @Test
    fun `the implementer's rework prompt carries the latest review findings, the first attempt has no empty section`() {
        val state = mapOf<String, Any?>(RunStateKeys.ISSUE to mapOf("key" to "SHOP-1", "summary" to "Cart"))
        val first = renderer.render(reworkTemplate, models.build(context("implement", state))).text
        assertThat(first.trim()).isEqualTo("Implement SHOP-1")

        val report = ReviewReport(Verdict.REQUEST_CHANGES, 60, "Null handling is missing.", listOf(Finding(Severity.BLOCKER, "src/Cart.kt", 12, "NPE on empty cart", "Guard the empty list")))
        val reviewed = Fixtures.runningRun(project, definition).addArtifact(
            Artifact(ArtifactId.new(), "code-review", "review", ArtifactKind.REVIEW, "text/markdown", report.toMarkdown(), createdAt = now),
        )
        val rework = renderer.render(reworkTemplate, models.build(context("implement", state, reviewed))).text
        assertThat(rework).contains("## Latest review findings").contains("NPE on empty cart").contains("src/Cart.kt").contains("REQUEST_CHANGES")
        assertThat(rework).doesNotContain("## Feedback on the previous attempt")
    }

    // ------------------------------------------------------------------------------------------ MISSING requirement

    private val gateway = object : LlmGateway {
        var answer: Map<String, Any?> = emptyMap()
        override fun chat(model: ModelRef, request: LlmRequest): LlmResult =
            LlmResult(answer.toString(), answer, TokenUsage(10, 5), latency = Duration.ofMillis(1), modelRef = model)
        override fun isConfigured(provider: Provider) = true
    }
    private val modelCatalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = ModelProfileSnapshot(id, "Test model", ModelRef(Provider.ANTHROPIC, "claude-test"), Pricing(BigDecimal.ONE, BigDecimal.ONE), true)
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private val json = object : JsonCodec {
        override fun toJson(value: Any?): String = value.toString()
        override fun parseObject(json: String): Map<String, Any?> = throw IllegalArgumentException("not used")
    }
    private val llm = LlmTaskExecutor(gateway, ModelResolver(modelCatalog), renderer, models, ReviewVerdictPolicy(), json)

    @Test
    fun `a review against the specification with a MISSING criterion requests changes and keeps the coverage matrix`() {
        gateway.answer = mapOf(
            "summary" to "AC2 is not implemented.", "findings" to emptyList<Any>(), "verdict" to "APPROVE", "score" to 100,
            "coverage" to listOf(
                mapOf("requirement" to "AC1: lists open requests", "status" to "MET", "evidence" to "src/List.kt:10"),
                mapOf("requirement" to "AC2: blocks saving without a reason", "status" to "MISSING", "evidence" to ""),
            ),
        )
        val run = Fixtures.runningRun(project, definition)
        val result = llm.execute(context("code-review", emptyMap(), run), task("review-against-spec", produces = listOf("review"), schema = "{}"))

        val review = result.review!!
        assertThat(review.verdict).isEqualTo(Verdict.REQUEST_CHANGES)
        assertThat(review.coverage.map { it.status }).containsExactly(CoverageStatus.MET, CoverageStatus.MISSING)
        assertThat(review.missingRequirements).isEqualTo(1)
        assertThat(result.summary).contains("1 requirements missing")
        assertThat(result.artifacts.single { it.name == "review" }.content).contains("### Requirement coverage (1 missing)").contains("AC2: blocks saving without a reason")
    }
}
