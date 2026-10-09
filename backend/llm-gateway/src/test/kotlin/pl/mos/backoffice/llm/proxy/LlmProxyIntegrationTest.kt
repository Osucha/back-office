package pl.mos.backoffice.llm.proxy

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.llm.PostgresTestSupport
import pl.mos.backoffice.llm.budget.application.port.`in`.BudgetInput
import pl.mos.backoffice.llm.budget.application.service.BudgetService
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.JobTokenVerifier
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.usage.Pricing
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Job tokens handed out by the test (the agent-runner module issues them in production). */
class FakeJobTokens : JobTokenVerifier {
    val tokens = ConcurrentHashMap<String, JobCredential>()
    override fun verify(token: String): JobCredential? = tokens[token]

    fun issue(
        projectId: UUID? = UUID.randomUUID(),
        allow: Set<String> = setOf("anthropic/claude-opus-5", "openai/gpt-5"),
        benchmarkId: UUID? = null,
        maxCostUsd: BigDecimal? = null,
        maxTokens: Long? = null,
        maxRequests: Long? = null,
    ): Pair<String, JobCredential> {
        val token = "bo_job_" + UUID.randomUUID()
        val credential = JobCredential(
            UUID.randomUUID(), projectId, UUID.randomUUID(), "implement", benchmarkId, null, allow, maxCostUsd, maxTokens, Instant.now().plusSeconds(600),
            maxRequests = maxRequests,
        )
        tokens[token] = credential
        return token to credential
    }
}

@TestConfiguration(proxyBeanMethods = false)
class ProxyTestBeans {
    val events = CopyOnWriteArrayList<DomainEvent>()
    val pricing = ConcurrentHashMap<String, Pricing>()

    @Bean fun jobTokens() = FakeJobTokens()

    @Bean fun eventPublisher(): EventPublisher = EventPublisher { events += it }

    @Bean
    @Primary
    fun pricedCatalog(): ModelCatalog = object : ModelCatalog {
        override fun find(id: ModelProfileId): ModelProfileSnapshot? = null
        override fun findAllEnabled(): List<ModelProfileSnapshot> = emptyList()
        override fun findPricing(ref: ModelRef): Pricing? = pricing[ref.label]
    }
}

abstract class ProxyTestBase {
    companion object {
        val provider: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stop() = Unit

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.ai.anthropic.api-key") { "sk-ant-platform" }
            registry.add("spring.ai.openai.api-key") { "sk-oai-platform" }
            registry.add("backoffice.llm.proxy.base-urls.anthropic") { provider.baseUrl() }
            registry.add("backoffice.llm.proxy.base-urls.openai") { provider.baseUrl() + "/v1" }
        }
    }
}

@SpringBootTest(properties = ["spring.ai.model.chat=none", "spring.flyway.enabled=true", "spring.jpa.open-in-view=false"])
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class, ProxyTestBeans::class)
class LlmProxyIntegrationTest : ProxyTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: FakeJobTokens
    @Autowired lateinit var beans: ProxyTestBeans
    @Autowired lateinit var ledger: UsageLedger
    @Autowired lateinit var budgets: BudgetService
    @Autowired lateinit var gate: FakeAuthorizationGate

    private val anthropicJson = """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hi"}],
        "usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}"""

    @BeforeEach
    fun reset() {
        provider.resetAll()
        beans.pricing["anthropic/claude-opus-5"] = Pricing(BigDecimal("5"), BigDecimal("25"))
    }

    private fun messages(token: String, body: String) = mvc.perform(
        MockMvcRequestBuilders.post("/api/v1/llm/anthropic/v1/messages").header("x-api-key", token).header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun entries(projectId: UUID) = ledger.entries(UsageQuery(null, null, projectId = projectId), 0, 50).content

    @Test
    fun `anthropic messages are forwarded with the platform key and metered`() {
        provider.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withHeader("request-id", "req_1").withBody(anthropicJson)))
        val (token, credential) = tokens.issue()

        messages(token, """{"model":"claude-opus-5","max_tokens":1024,"messages":[{"role":"user","content":"hi"}]}""")
            .andExpect(status().isOk).andExpect(header().string("request-id", "req_1")).andExpect(jsonPath("$.id").value("msg_1"))

        provider.verify(
            postRequestedFor(urlEqualTo("/v1/messages")).withHeader("x-api-key", equalTo("sk-ant-platform"))
                .withHeader("anthropic-version", equalTo("2023-06-01")).withHeader("anthropic-beta", equalTo("prompt-caching-2024-07-31")),
        )
        val record = entries(credential.projectId!!).single()
        assertThat(record.usage.inputTokens).isEqualTo(120)
        assertThat(record.usage.outputTokens).isEqualTo(30)
        assertThat(record.modelRef.label).isEqualTo("anthropic/claude-opus-5")
        assertThat(record.context.stepId).isEqualTo("implement")
        assertThat(record.cost.amount).isEqualByComparingTo("0.001350")
        assertThat(gate.asSystem("test") { budgets.getProject(credential.projectId!!) }.spentUsd).isEqualByComparingTo("0.001350")
    }

    @Test
    fun `anthropic streams are passed through line by line and usage comes from message_start and message_delta`() {
        val sse = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":200,"cache_read_input_tokens":50,"cache_creation_input_tokens":10,"output_tokens":1}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}""",
            "",
        ).joinToString("\n") + "\n"
        provider.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse).withChunkedDribbleDelay(4, 50)))
        val (token, credential) = tokens.issue()

        messages(token, """{"model":"claude-opus-5","max_tokens":1024,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            .andExpect(status().isOk).andExpect(content().string(sse))

        val usage = entries(credential.projectId!!).single().usage
        assertThat(usage.inputTokens).isEqualTo(200)
        assertThat(usage.outputTokens).isEqualTo(42)
        assertThat(usage.cacheReadTokens).isEqualTo(50)
        assertThat(usage.cacheWriteTokens).isEqualTo(10)
    }

    @Test
    fun `openai streams ask for the usage chunk and are metered from it`() {
        val sse = listOf(
            """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"Hi"}}],"usage":null}""",
            "",
            """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":10}}}""",
            "",
            "data: [DONE]",
            "",
        ).joinToString("\n") + "\n"
        provider.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse)))
        val (token, credential) = tokens.issue()

        mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/llm/openai/v1/chat/completions").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content("""{"model":"gpt-5","stream":true,"messages":[{"role":"user","content":"hi"}]}"""),
        ).andExpect(status().isOk).andExpect(content().string(sse))

        provider.verify(
            postRequestedFor(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer sk-oai-platform"))
                .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))),
        )
        val record = entries(credential.projectId!!).single()
        assertThat(record.modelRef.label).isEqualTo("openai/gpt-5")
        assertThat(record.usage.inputTokens).isEqualTo(40)
        assertThat(record.usage.cacheReadTokens).isEqualTo(10)
        assertThat(record.usage.outputTokens).isEqualTo(7)
    }

    @Test
    fun `models outside the allowlist are refused without calling the provider`() {
        val (token, _) = tokens.issue(allow = setOf("anthropic/claude-haiku-*"))
        messages(token, """{"model":"claude-opus-5","max_tokens":10,"messages":[]}""")
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("model-not-allowed"))
        provider.verify(0, postRequestedFor(anyUrl()))

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/llm/openai/v1/models").header("Authorization", "Bearer ${tokens.issue().first}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data[0].id").value("gpt-5")).andExpect(jsonPath("$.data.length()").value(1))
    }

    @Test
    fun `only job tokens authenticate`() {
        messages("bo_job_unknown", "{}").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("invalid-token"))
        messages("bo_rt_runner-token", "{}").andExpect(status().isUnauthorized)
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/llm/openai/v1/models").header("Authorization", "Bearer bo_pat_personal")).andExpect(status().isUnauthorized)
        provider.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `a project hard limit stops calls with 402 and emits alert and exceeded events`() {
        beans.pricing["anthropic/claude-opus-5"] = Pricing(BigDecimal("1"), BigDecimal("1000"))
        provider.stubFor(
            post(urlEqualTo("/v1/messages")).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                    .withBody("""{"id":"m","usage":{"input_tokens":100,"output_tokens":2000}}"""),
            ),
        )
        val (token, credential) = tokens.issue()
        val projectId = credential.projectId!!
        gate.user = FakeAuthorizationGate.user(platform = setOf("admin.settings.manage"), allProjects = true)
        mvc.perform(
            MockMvcRequestBuilders.put("/api/v1/projects/$projectId/budget").contentType(MediaType.APPLICATION_JSON)
                .content("""{"monthlyLimitUsd":1.00,"alertAtPercent":80,"hardStop":true}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.state").value("OK")).andExpect(jsonPath("$.scope").value("PROJECT"))

        // estimate: 100 output tokens (max_tokens) at 1000 USD/MTok = 0.10 USD < 1 USD, so the call goes through
        messages(token, """{"model":"claude-opus-5","max_tokens":100,"messages":[{"role":"user","content":"go"}]}""").andExpect(status().isOk)
        // actual: 2000 output tokens = 2 USD: over the limit
        assertThat(beans.events.map { it.type to it.payload["projectId"] }).contains("BudgetAlert" to projectId.toString(), "BudgetExceeded" to projectId.toString())

        messages(token, """{"model":"claude-opus-5","max_tokens":100,"messages":[{"role":"user","content":"again"}]}""")
            .andExpect(status().`is`(402)).andExpect(jsonPath("$.code").value("budget-exceeded")).andExpect(jsonPath("$.scope").value("PROJECT"))
        provider.verify(1, postRequestedFor(urlEqualTo("/v1/messages")))

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/budgets")).andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.projectId == '$projectId')].state").value("EXCEEDED"))
            .andExpect(jsonPath("$[?(@.scope == 'PLATFORM')].state").value("NO_LIMIT"))
        gate.user = FakeAuthorizationGate.user(platform = setOf("usage.view"))
    }

    @Test
    fun `the per-job token cap from the credential is enforced`() {
        provider.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(anthropicJson)))
        val (token, _) = tokens.issue(maxTokens = 200)
        messages(token, """{"model":"claude-opus-5","max_tokens":10,"messages":[]}""").andExpect(status().isOk) // uses 150 tokens
        messages(token, """{"model":"claude-opus-5","max_tokens":100,"messages":[]}""")
            .andExpect(status().`is`(402)).andExpect(jsonPath("$.scope").value("JOB"))
    }

    @Test
    fun `the per-job request cap stops a looping agent before the provider is called (ADR 0029)`() {
        provider.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(anthropicJson)))
        val (token, _) = tokens.issue(maxRequests = 2)
        repeat(2) { messages(token, """{"model":"claude-opus-5","max_tokens":10,"messages":[]}""").andExpect(status().isOk) }
        messages(token, """{"model":"claude-opus-5","max_tokens":10,"messages":[]}""")
            .andExpect(status().`is`(402)).andExpect(jsonPath("$.scope").value("JOB")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("request cap")))
        provider.verify(2, postRequestedFor(urlEqualTo("/v1/messages")))
    }

    @Test
    fun `platform budget needs admin settings permission`() {
        gate.user = FakeAuthorizationGate.user(platform = setOf("usage.view"))
        org.assertj.core.api.Assertions.assertThatThrownBy { budgets.setPlatform(BudgetInput(BigDecimal.TEN)) }
            .isInstanceOf(pl.mos.backoffice.shared.InsufficientPermissionException::class.java)
    }
}

