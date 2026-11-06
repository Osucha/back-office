package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.projects.domain.service.SessionContinuation
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerArtifact
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR 0057 for coding agent steps: the environment goes to `spec.instructions` (not the prompt), the agent's MCP servers
 * to the spec, an output schema asks for `structured-answer.json` which is mapped like a model answer, and
 * `continueSession` puts the step into the earlier step's session. ADR 0058: `params` in prompts and text config.
 */
class CodingAgentEnvironmentTest {

    private val project = Fixtures.project()
    private val catalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = ModelProfileSnapshot(id, "Test model", ModelRef(Provider.ANTHROPIC, "claude-test"), Pricing(BigDecimal.ONE, BigDecimal.ONE), true)
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = null
    }

    /** The answers the agents of these tests write, by their exact text. */
    private val answers = mapOf(
        SCHEMA to mapOf(
            "type" to "object", "required" to listOf("report", "confidence"),
            "properties" to mapOf("report" to mapOf("type" to "string"), "confidence" to mapOf("type" to "number"), "summary" to mapOf("type" to "string")),
        ),
        REVIEW_SCHEMA to mapOf("type" to "object", "required" to listOf("summary", "findings", "verdict", "score")),
        GOOD to mapOf("report" to "# Report", "confidence" to 0.8, "summary" to "all fine"),
        MISSING_FIELD to mapOf("summary" to "no report"),
        REVIEW to mapOf(
            "summary" to "looks good", "verdict" to "APPROVE", "score" to 95,
            "findings" to listOf(mapOf("severity" to "MINOR", "file" to "a.kt", "title" to "Name", "detail" to "rename")),
        ),
    )
    private val json = object : JsonCodec {
        override fun toJson(value: Any?): String = value.toString()
        override fun parseObject(json: String): Map<String, Any?> = answers[json.trim()] ?: throw IllegalArgumentException("not JSON")
    }
    private val runner = ScriptedRunner()
    private val support = RunnerTaskSupport(
        runner, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), Duration.ofMinutes(30),
        structuredAnswers = StructuredAnswers(json, ReviewVerdictPolicy()),
    )

    private fun context(nodes: List<WorkflowNode>? = null, nodeId: String = "implement", p: Project = project): NodeContext {
        val definition = Fixtures.issueWorkflow(p).let { d -> nodes?.let { d.copy(nodes = d.nodes + it) } ?: d }
        val run = Fixtures.runningRun(p, definition)
        val node = run.definitionSnapshot.requireNode(nodeId)
        val started = run.nodeStarted(node, now)
        return NodeContext(started, p, node, started.nodes.last(), mapOf(RunStateKeys.ISSUE to mapOf("key" to "SHOP-1", "summary" to "Pay")))
    }

    private fun task(
        schema: String? = null,
        produces: List<String> = emptyList(),
        config: Map<String, Any?> = mapOf("push" to false),
        parameters: List<ResolvedParameter> = emptyList(),
        user: String = "Do {{issue.key}}.",
    ) = ResolvedTask(
        key = "t", name = "T",
        agent = TestAgents.CLAUDE_CODE.copy(instructions = "You are the implementer of {{project.name}}.", mcpServers = listOf(AgentMcpServer("docs", "https://mcp.example.com"))),
        modelProfileId = null, prompt = TaskPrompt(null, user), skills = emptyList(), config = config, outputSchema = schema,
        confidenceField = "confidence".takeIf { schema != null && "confidence" in produces }, produces = produces, consumes = emptyList(), category = null,
        lineage = listOf("t"), ports = listOf("success", "failure"), rules = listOf(RuleSnapshot("ground", "Ground", "Cite evidence.")),
        parameters = parameters,
    )

    private fun submit(task: ResolvedTask, ctx: NodeContext = context()): RunnerJobSpec {
        support.run(ctx, task, RunnerMode.IMPLEMENT, null, "x")
        return runner.specs.last()
    }

    private fun complete(task: ResolvedTask, artifacts: List<RunnerArtifact>, status: RunnerJobStatus = RunnerJobStatus.SUCCEEDED): pl.mos.backoffice.projects.application.port.out.NodeResult {
        runner.result = result(artifacts, status)
        return support.complete(context(), task, RunnerMode.IMPLEMENT, null, "x", RunnerJobId(UUID.randomUUID()))
    }

    @Test
    fun `the environment document and the MCP servers go to the spec, the rules stay out of the prompt`() {
        val spec = submit(task())

        assertThat(spec.instructions).startsWith(AgentEnvironment.PLATFORM_PREAMBLE).contains("You are the implementer of Shop.")
            .contains("## Project constitution").contains("Keep it simple.").contains("Cite evidence.")
        assertThat(spec.prompt).doesNotContain("Cite evidence.").doesNotContain("Keep it simple.").startsWith("Do SHOP-1.")
        assertThat(spec.mcpServers).containsExactly(AgentMcpServer("docs", "https://mcp.example.com"))
        assertThat(spec.collectArtifacts).doesNotContain(RunnerTaskSupport.STRUCTURED_ANSWER_FILE)
    }

    @Test
    fun `an output schema adds the answer section and collects structured-answer json`() {
        val spec = submit(task(schema = SCHEMA, produces = listOf("report", "confidence")))

        assertThat(spec.prompt).contains("## Answer").contains(RunnerTaskSupport.STRUCTURED_ANSWER_FILE).contains(SCHEMA)
        assertThat(spec.prompt.indexOf("## Answer")).isLessThan(spec.prompt.indexOf("## Untrusted data"))
        assertThat(spec.collectArtifacts).contains(RunnerTaskSupport.STRUCTURED_ANSWER_FILE)
    }

    @Test
    fun `a valid structured answer becomes artefacts, confidence and state like a model answer`() {
        val mapped = complete(task(schema = SCHEMA, produces = listOf("report", "confidence")), listOf(answer(GOOD)))

        assertThat(mapped.port).isEqualTo(WorkflowNode.PORT_SUCCESS)
        assertThat(mapped.confidence).isEqualTo(0.8)
        assertThat(mapped.stateUpdates[RunStateKeys.CONFIDENCE]).isEqualTo(0.8)
        assertThat(mapped.artifacts.map { it.name }).contains("report").doesNotContain(RunnerTaskSupport.STRUCTURED_ANSWER_ARTIFACT)
        assertThat(mapped.artifacts.first { it.name == "report" }.content).isEqualTo("# Report")
        assertThat(mapped.summary).isEqualTo("all fine")
    }

    @Test
    fun `ADR 0059 the finished job records the agent and the model of the plan, failed jobs too`() {
        val mapped = complete(task(schema = SCHEMA, produces = listOf("report", "confidence")), listOf(answer(GOOD)))
        val agent = mapped.agent!!
        assertThat(agent.agentKey).isEqualTo(TestAgents.CLAUDE_CODE.key)
        assertThat(agent.agentName).isEqualTo(TestAgents.CLAUDE_CODE.name)
        assertThat(agent.harness).isEqualTo(TestAgents.CLAUDE_CODE.harness)
        assertThat(agent.modelProfileId).isEqualTo(project.defaults.modelProfileId)
        assertThat(agent.modelLabel).isEqualTo("Test model")

        val failed = complete(task(), emptyList(), RunnerJobStatus.FAILED)
        assertThat(failed.error).isNotNull()
        assertThat(failed.agent!!.modelLabel).isEqualTo("Test model")
    }

    @Test
    fun `a review answer of a coding agent becomes the run review with the verdict from the policy`() {
        val mapped = complete(task(schema = REVIEW_SCHEMA, produces = listOf("review")), listOf(answer(REVIEW)))

        assertThat(mapped.port).isEqualTo(WorkflowNode.PORT_SUCCESS)
        assertThat(mapped.review!!.findings.single().title).isEqualTo("Name")
        assertThat(mapped.stateUpdates).containsKey(RunStateKeys.REVIEW)
        assertThat(mapped.artifacts.map { it.name }).contains("review")
    }

    @Test
    fun `a missing, unreadable or invalid answer fails the step with the reasons`() {
        val withSchema = task(schema = SCHEMA, produces = listOf("report", "confidence"))

        val missing = complete(withSchema, emptyList())
        assertThat(missing.port).isEqualTo(WorkflowNode.PORT_FAILURE)
        assertThat(missing.error).contains("wrote no structured-answer.json")

        val unreadable = complete(withSchema, listOf(answer("not json at all")))
        assertThat(unreadable.error).contains("structured-answer.json is not valid").contains("not a JSON object")

        val invalid = complete(withSchema, listOf(answer(MISSING_FIELD)))
        assertThat(invalid.port).isEqualTo(WorkflowNode.PORT_FAILURE)
        assertThat(invalid.error).contains("required property report is missing")

        val failedJob = complete(withSchema, emptyList(), RunnerJobStatus.FAILED)
        assertThat(failedJob.error).describedAs("a failed job reports the job, not the missing answer").doesNotContain("structured-answer")
    }

    @Test
    fun `ADR 0058 - params reach the prompt and the text config`() {
        val params = listOf(ResolvedParameter(TemplateParameter("area", "Area"), "billing", ParameterSource.STEP))
        val spec = submit(task(user = "Work on {{params.area}}.", parameters = params, config = mapOf("push" to false, "commitSubjectTemplate" to "fix({{params.area}}): pay")))
        assertThat(spec.prompt).startsWith("Work on billing.")
        assertThat(spec.commitSubject).isEqualTo("fix(billing): pay")
    }

    @Test
    fun `continueSession puts the step into the earlier step's workspace and role, and the earlier step gets them too`() {
        val nodes = listOf(WorkflowNode.Task("fix", "Fix", task = TaskBinding("agent-task", TaskOverrides(config = mapOf(SessionContinuation.KEY to "implement")))))

        val fix = submit(task(config = mapOf("push" to false, SessionContinuation.KEY to "implement")), context(nodes, "fix"))
        assertThat(fix.workspace!!.key).isEqualTo(SessionContinuation.RUN_WORKSPACE)
        assertThat(fix.workspace!!.sessionRole).isEqualTo("implement")

        val implement = submit(task(), context(nodes, "implement"))
        assertThat(implement.workspace!!.key).isEqualTo(SessionContinuation.RUN_WORKSPACE)
        assertThat(implement.workspace!!.sessionRole).isEqualTo("implement")
        assertThat(implement.workspace!!.name).describedAs("one workspace per run").endsWith("-run")

        assertThat(submit(task(), context()).workspace).describedAs("no continuation: a throwaway workspace").isNull()
    }

    private fun answer(content: String) = RunnerArtifact(RunnerTaskSupport.STRUCTURED_ANSWER_ARTIFACT, RunnerTaskSupport.STRUCTURED_ANSWER_FILE, "application/json", content)

    private fun result(artifacts: List<RunnerArtifact>, status: RunnerJobStatus) = RunnerResult(
        status = status, summary = "done", branch = null, commitSha = null, diffStat = null, verification = null,
        artifacts = artifacts, usage = TokenUsage(10, 5), reportedCostUsd = null, error = if (status == RunnerJobStatus.SUCCEEDED) null else "boom",
        finishedAt = now,
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

    private companion object {
        const val GOOD = "good-answer"
        const val MISSING_FIELD = "missing-field"
        const val REVIEW = "review-answer"
        val SCHEMA = """{"type":"object","required":["report","confidence"],"properties":{"report":{"type":"string"},"confidence":{"type":"number"},"summary":{"type":"string"}}}"""
        val REVIEW_SCHEMA = """{"type":"object","required":["summary","findings","verdict","score"],"properties":{"summary":{"type":"string"},"findings":{"type":"array"},"verdict":{"type":"string"},"score":{"type":"integer"}}}"""
    }
}
