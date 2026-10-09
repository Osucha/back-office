package pl.mos.backoffice.evaluation.adapter.`in`.rest

import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import pl.mos.backoffice.evaluation.PostgresTestSupport
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.domain.event.BenchmarkFinished
import pl.mos.backoffice.evaluation.domain.event.BenchmarkProgress
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(properties = ["backoffice.evaluation.seed-starter-content=false", "backoffice.evaluation.recover-on-startup=false"])
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class EvaluationRestIntegrationTest {

    @Autowired lateinit var mvc: MockMvcTester
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var gateway: LlmGateway
    @Autowired lateinit var benchmarks: BenchmarkRepository
    @Autowired lateinit var events: CopyOnWriteArrayList<DomainEvent>
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var audit: RecordingAuditPort
    @Autowired lateinit var contributions: List<PermissionCatalogContribution>

    private fun profileBody(name: String, provider: String = "ANTHROPIC", modelId: String = "claude-sonnet-5", enabled: Boolean = true) = """
        {"displayName":"$name","ref":{"provider":"$provider","modelId":"$modelId","options":{"reasoning":"HIGH","maxOutputTokens":2048}},
         "pricing":{"inputPerMTok":2,"outputPerMTok":10,"cacheReadPerMTok":0.2},"capabilities":["CODING","REASONING"],"contextWindow":200000,"notes":"n","enabled":$enabled}
    """.trimIndent()

    private fun post(uri: String, body: String) = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
    private fun put(uri: String, body: String) = mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
    private fun idOf(body: String): UUID = UUID.fromString(json.readTree(body).get("id").asString())

    private fun createProfile(name: String, provider: String = "ANTHROPIC", modelId: String = "claude-sonnet-5"): UUID {
        val result = post("/api/v1/model-profiles", profileBody(name, provider, modelId)).exchange()
        assertThat(result).hasStatus(HttpStatus.CREATED)
        return idOf(result.response.contentAsString)
    }

    @Test
    fun `model profiles support create, read, update, list and delete`() {
        val id = createProfile("CRUD profile")

        assertThat(mvc.get().uri("/api/v1/model-profiles/$id")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.displayName").isEqualTo("CRUD profile") },
            { assertThat(it).extractingPath("$.ref.options.reasoning").isEqualTo("HIGH") },
            { assertThat(it).extractingPath("$.pricing.cacheReadPerMTok").asNumber().isEqualTo(0.2) },
            { assertThat(it).extractingPath("$.providerConfigured").isEqualTo(true) },
            { assertThat(it).extractingPath("$.capabilities").asArray().containsExactlyInAnyOrder("CODING", "REASONING") },
        )

        assertThat(put("/api/v1/model-profiles/$id", profileBody("Renamed", provider = "OPENAI", modelId = "gpt-5", enabled = false)))
            .hasStatusOk().bodyJson().satisfies(
                { assertThat(it).extractingPath("$.displayName").isEqualTo("Renamed") },
                { assertThat(it).extractingPath("$.enabled").isEqualTo(false) },
                { assertThat(it).extractingPath("$.providerConfigured").isEqualTo(false) },
            )

        assertThat(mvc.get().uri("/api/v1/model-profiles").param("enabled", "false")).hasStatusOk()
            .bodyJson().extractingPath("$[*].id").asArray().contains(id.toString())
        assertThat(mvc.get().uri("/api/v1/model-profiles").param("enabled", "true")).hasStatusOk()
            .bodyJson().extractingPath("$[*].id").asArray().doesNotContain(id.toString())

        assertThat(mvc.delete().uri("/api/v1/model-profiles/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/model-profiles/$id")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/model-profiles", profileBody(""))).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `providers endpoint reports configured flag per provider`() {
        assertThat(mvc.get().uri("/api/v1/model-profiles/providers")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.length()").isEqualTo(6) },
            { assertThat(it).extractingPath("$[?(@.provider == 'ANTHROPIC')].configured").asArray().containsExactly(true) },
            { assertThat(it).extractingPath("$[?(@.provider == 'OPENAI')].configured").asArray().containsExactly(false) },
        )
    }

    @Test
    fun `a benchmark runs end to end through the executor and the leaderboard reflects the attempts`() {
        every { gateway.chat(any(), any()) } answers {
            val model = firstArg<ModelRef>()
            val request = secondArg<LlmRequest>()
            if (request.responseSchema != null) {
                val score = if (request.messages.last().content.contains("answer from claude-haiku")) 60 else 90
                LlmResult("{}", mapOf("score" to score, "passed" to (score >= 70), "criteria" to emptyList<Any>(), "rationale" to "judged"), TokenUsage(500, 50), latency = Duration.ofMillis(200), modelRef = model)
            } else {
                LlmResult("answer from ${model.modelId}", null, TokenUsage(1000, 200), latency = Duration.ofMillis(300), modelRef = model)
            }
        }
        val judge = createProfile("Judge", modelId = "claude-fable-5-1")
        val strong = createProfile("Strong", modelId = "claude-sonnet-5")
        val weak = createProfile("Weak", modelId = "claude-haiku-4-5")

        val suite = post("/api/v1/task-suites", """{"name":"Suite","description":"d"}""").exchange()
        assertThat(suite).hasStatus(HttpStatus.CREATED)
        val suiteId = idOf(suite.response.contentAsString)
        val task = """{"name":"Analyse","category":"ANALYSIS","difficulty":"EASY","kind":"PROMPT","instructions":"Explain the failure.","rubric":"Correctness 0-10","timeoutSeconds":120}"""
        assertThat(post("/api/v1/task-suites/$suiteId/tasks", task)).hasStatus(HttpStatus.CREATED)
        assertThat(post("/api/v1/task-suites/$suiteId/tasks", task.replace("Analyse", "Review").replace("ANALYSIS", "REVIEW"))).hasStatus(HttpStatus.CREATED)
        assertThat(mvc.get().uri("/api/v1/task-suites/$suiteId")).hasStatusOk().bodyJson().extractingPath("$.taskCount").isEqualTo(2)

        val created = post(
            "/api/v1/benchmarks",
            """{"name":"Compare","suiteId":"$suiteId","modelProfileIds":["$strong","$weak"],"judgeModelProfileId":"$judge","repetitions":2}""",
        ).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.status").isEqualTo("DRAFT")
        val benchmarkId = idOf(created.response.contentAsString)

        assertThat(mvc.post().uri("/api/v1/benchmarks/$benchmarkId/start")).hasStatus(HttpStatus.ACCEPTED).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.status").isEqualTo("RUNNING") },
            { assertThat(it).extractingPath("$.progress.total").isEqualTo(8) },
            { assertThat(it).extractingPath("$.models.length()").isEqualTo(2) },
            { assertThat(it).extractingPath("$.judge.displayName").isEqualTo("Judge") },
        )
        assertThat(mvc.post().uri("/api/v1/benchmarks/$benchmarkId/start")).hasStatus(HttpStatus.CONFLICT)

        awaitCompletion(BenchmarkId(benchmarkId))

        assertThat(mvc.get().uri("/api/v1/benchmarks/$benchmarkId")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.status").isEqualTo("COMPLETED") },
            { assertThat(it).extractingPath("$.progress.done").isEqualTo(8) },
            { assertThat(it).extractingPath("$.progress.failed").isEqualTo(0) },
        )
        assertThat(mvc.get().uri("/api/v1/benchmarks/$benchmarkId/attempts").param("modelProfileId", weak.toString()))
            .hasStatusOk().bodyJson().extractingPath("$[*].score").asArray().containsOnly(60.0)

        assertThat(mvc.get().uri("/api/v1/benchmarks/$benchmarkId/leaderboard")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.overall[0].modelDisplayName").isEqualTo("Strong") },
            { assertThat(it).extractingPath("$.overall[0].meanScore").asNumber().isEqualTo(90.0) },
            { assertThat(it).extractingPath("$.overall[0].passRate").asNumber().isEqualTo(1.0) },
            { assertThat(it).extractingPath("$.overall[0].attempts").isEqualTo(4) },
            { assertThat(it).extractingPath("$.overall[0].stability").asNumber().isEqualTo(0.0) },
            { assertThat(it).extractingPath("$.overall[1].modelDisplayName").isEqualTo("Weak") },
            { assertThat(it).extractingPath("$.overall[1].passRate").asNumber().isEqualTo(0.0) },
            { assertThat(it).extractingPath("$.overall[1].totalCost.amount").asNumber().isEqualTo(0.016) },
            { assertThat(it).extractingPath("$.byCategory.ANALYSIS.length()").isEqualTo(2) },
            { assertThat(it).extractingPath("$.byDifficulty.EASY[0].modelDisplayName").isEqualTo("Strong") },
            { assertThat(it).extractingPath("$.byTask.length()").isEqualTo(2) },
        )

        val attemptId = benchmarks.findAttempts(BenchmarkId(benchmarkId)).first().id
        assertThat(mvc.get().uri("/api/v1/benchmarks/$benchmarkId/attempts/$attemptId")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.output").asString().startsWith("answer from") },
            { assertThat(it).extractingPath("$.judgeRationale").isEqualTo("judged") },
            { assertThat(it).extractingPath("$.judgeCost.amount").asNumber().isEqualTo(0.0015) },
        )

        assertThat(events.filterIsInstance<BenchmarkProgress>().filter { it.benchmarkId.value == benchmarkId }).hasSize(8)
        assertThat(events.filterIsInstance<BenchmarkFinished>().single { it.benchmarkId.value == benchmarkId }.status).isEqualTo(BenchmarkStatus.COMPLETED)

        assertThat(mvc.delete().uri("/api/v1/model-profiles/$strong")).hasStatus(HttpStatus.CONFLICT)
        assertThat(mvc.delete().uri("/api/v1/benchmarks/$benchmarkId")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/benchmarks/$benchmarkId/leaderboard")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `mutations are audited and stamped with the actor, readers without run or manage get 403`() {
        val id = createProfile("Audited profile")
        assertThat(mvc.get().uri("/api/v1/model-profiles/$id")).hasStatusOk().bodyJson().extractingPath("$.updatedBy.displayName").isEqualTo("Alice Test")
        assertThat(audit.entries.filter { it.entityId == id.toString() }.map { it.operation }).containsExactly("MODEL_PROFILE_CREATE")

        val previous = gate.user
        try {
            gate.user = FakeAuthorizationGate.user(platform = setOf("models.view", "evaluation.view"))
            assertThat(mvc.get().uri("/api/v1/model-profiles")).hasStatusOk()
            assertThat(mvc.get().uri("/api/v1/model-profiles/providers")).hasStatusOk()
            assertThat(mvc.get().uri("/api/v1/task-suites")).hasStatusOk()
            assertThat(mvc.get().uri("/api/v1/benchmarks")).hasStatusOk()
            assertThat(post("/api/v1/model-profiles", profileBody("Denied"))).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.post().uri("/api/v1/model-profiles/$id/test")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.delete().uri("/api/v1/model-profiles/$id")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/task-suites", """{"name":"Denied"}""")).hasStatus(HttpStatus.FORBIDDEN)

            gate.user = FakeAuthorizationGate.user()
            assertThat(mvc.get().uri("/api/v1/model-profiles")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/benchmarks")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/task-suites")).hasStatus(HttpStatus.FORBIDDEN)
        } finally {
            gate.user = previous
        }
        assertThat(mvc.get().uri("/api/v1/model-profiles/$id")).hasStatusOk()
        assertThat(mvc.delete().uri("/api/v1/model-profiles/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(audit.entries.filter { it.entityId == id.toString() }.map { it.operation }).containsExactly("MODEL_PROFILE_CREATE", "MODEL_PROFILE_DELETE")
    }

    @Test
    fun `the module contributes its permissions to the catalogue`() {
        assertThat(contributions.flatMap { it.permissions() }.map { it.key })
            .contains("models.view", "models.manage", "evaluation.view", "evaluation.run")
    }

    private fun awaitCompletion(id: BenchmarkId) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (benchmarks.findById(id)!!.status.terminal) return
            Thread.sleep(100)
        }
        error("benchmark $id did not finish in time: ${benchmarks.findAttempts(id).map { it.status to it.error }}")
    }
}
