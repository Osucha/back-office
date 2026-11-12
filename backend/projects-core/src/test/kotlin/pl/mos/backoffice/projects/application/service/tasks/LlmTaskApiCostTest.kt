package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.OwnLoginLlm
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.time.Duration

/** ADR 0065: a model answer step on a Claude Code login model spends nothing and is worth Claude Code's estimate. */
class LlmTaskApiCostTest {

    private val project = Fixtures.project().copy(personal = true)
    private val loginId = ModelProfileId.new()
    private val apiId = ModelProfileId.new()
    private val login = ModelProfileSnapshot(loginId, "Sonnet (my login)", ModelRef(Provider.ANTHROPIC, "claude-sonnet-5"), Pricing.FREE, true, ModelRuntime.CLAUDE_CODE_LOGIN)
    private val api = ModelProfileSnapshot(apiId, "Sonnet", ModelRef(Provider.ANTHROPIC, "claude-sonnet-5"), Pricing(BigDecimal("3"), BigDecimal("15")), true)
    private val catalog = object : ModelCatalog {
        override fun find(id: ModelProfileId) = listOf(login, api).firstOrNull { it.id == id }
        override fun findAllEnabled(): List<ModelProfileSnapshot> = listOf(login, api)
        override fun findPricing(ref: ModelRef): Pricing? = null
    }
    private val gateway = object : LlmGateway {
        override fun chat(model: ModelRef, request: LlmRequest): LlmResult =
            LlmResult("{}", mapOf("summary" to "ok"), TokenUsage(1_000_000, 0), latency = Duration.ofSeconds(1), modelRef = model)
        override fun isConfigured(provider: Provider) = true
    }
    private var reported: BigDecimal? = null
    private val ownLogin = OwnLoginLlm { model, _ ->
        LlmResult("{}", mapOf("summary" to "ok"), TokenUsage(100_000, 10_000), latency = Duration.ofSeconds(1), modelRef = model, reportedCostUsd = reported)
    }
    private val json = object : JsonCodec {
        override fun toJson(value: Any?): String = value.toString()
        override fun parseObject(json: String): Map<String, Any?> = emptyMap()
    }
    private val executor = LlmTaskExecutor(gateway, ModelResolver(catalog), TemplateRenderer(), TemplateModelBuilder(), ReviewVerdictPolicy(), json, ownLogin = ownLogin)

    private fun task(model: ModelProfileId) = ResolvedTask(
        key = "summarise", name = "Summarise", agent = TestAgents.MODEL_ANSWER, modelProfileId = model,
        instructions = "Summarise the issue.", skills = emptyList(), config = emptyMap(), outputSchema = "{}", confidenceField = null,
        produces = listOf("summary"), consumes = emptyList(), category = null, lineage = listOf("summarise"), ports = listOf("success"),
    )

    private fun context(): NodeContext {
        val run: WorkflowRun = Fixtures.runningRun(project, Fixtures.reviewWorkflow(project), RunInput(issueKey = null))
        val node = run.definitionSnapshot.nodes.first()
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), emptyMap())
    }

    @Test
    fun `a login model's answer costs nothing and carries Claude Code's estimate`() {
        reported = BigDecimal("0.37")
        val result = executor.execute(context(), task(loginId))
        assertThat(result.cost).isEqualByComparingTo(Money.ZERO)
        assertThat(result.apiCost).isEqualTo(Money.usd("0.37"))
    }

    @Test
    fun `without an estimate the tokens are priced like the API profile of the same family`() {
        reported = null
        val result = executor.execute(context(), task(loginId))
        // 100 000 * 3 / 1e6 + 10 000 * 15 / 1e6
        assertThat(result.apiCost).isEqualTo(Money.usd("0.45"))
    }

    @Test
    fun `a gateway answer has the same amount spent and at API prices`() {
        val result = executor.execute(context(), task(apiId))
        assertThat(result.cost).isEqualTo(Money.usd("3"))
        assertThat(result.apiCost).isEqualTo(result.cost)
    }
}
