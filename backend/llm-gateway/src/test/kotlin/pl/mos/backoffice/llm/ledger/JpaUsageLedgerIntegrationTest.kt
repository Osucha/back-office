package pl.mos.backoffice.llm.ledger

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import pl.mos.backoffice.llm.PostgresTestSupport
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.SortSpec
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "spring.ai.model.chat=none",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class JpaUsageLedgerIntegrationTest {

    @Autowired
    lateinit var ledger: UsageLedger

    @Autowired
    lateinit var repository: UsageRecordJpaRepository

    @Autowired
    lateinit var mvc: MockMvcTester

    private val sonnet = ModelRef(Provider.ANTHROPIC, "claude-sonnet-5")
    private val gpt = ModelRef(Provider.OPENAI, "gpt-5")
    private val projectA = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val benchmarkId = UUID.randomUUID()
    private val day1 = Instant.parse("2026-09-01T10:00:00Z")
    private val day2 = Instant.parse("2026-09-02T23:30:00Z")

    @BeforeEach
    fun seed() {
        repository.deleteAll()
        ledger.record(record(day1, sonnet, TokenUsage(1000, 100, 200, 0), "0.010000", UsageContext(UsageKind.PIPELINE_STEP, projectId = projectA, runId = runId, stepId = "specify")))
        ledger.record(record(day1, gpt, TokenUsage(500, 50), "0.020000", UsageContext(UsageKind.PIPELINE_STEP, projectId = projectA, runId = runId, stepId = "plan")))
        ledger.record(record(day2, sonnet, TokenUsage(2000, 300), "0.030000", UsageContext(UsageKind.BENCHMARK_ATTEMPT, benchmarkId = benchmarkId, attemptId = UUID.randomUUID())))
        ledger.record(record(day2, sonnet, TokenUsage(10, 1), "0.000500", UsageContext(UsageKind.MODEL_TEST)))
    }

    private fun record(at: Instant, model: ModelRef, usage: TokenUsage, cost: String, context: UsageContext) = UsageRecord(
        occurredAt = at, modelRef = model, usage = usage, cost = Money.usd(cost), estimatedCost = Money.usd(cost), durationMs = 1234, context = context,
    )

    @Test
    fun `round trips a record with every column`() {
        val page = ledger.entries(UsageQuery(from = null, to = null, runId = runId), page = 0, size = 10)
        assertThat(page.totalElements).isEqualTo(2)
        val specify = page.content.first { it.context.stepId == "specify" }
        assertThat(specify.modelRef).isEqualTo(sonnet)
        assertThat(specify.usage).isEqualTo(TokenUsage(1000, 100, 200, 0))
        assertThat(specify.cost).isEqualTo(Money.usd("0.01"))
        assertThat(specify.estimatedCost).isEqualTo(Money.usd("0.01"))
        assertThat(specify.durationMs).isEqualTo(1234)
        assertThat(specify.occurredAt).isEqualTo(day1)
        assertThat(specify.context.projectId).isEqualTo(projectA)
        assertThat(specify.context.kind).isEqualTo(UsageKind.PIPELINE_STEP)
    }

    @Test
    fun `summary groups by model with totals`() {
        val summary = ledger.summary(UsageQuery(from = null, to = null), UsageGroupBy.MODEL)

        assertThat(summary.groups.map { it.key }).containsExactly("anthropic/claude-sonnet-5", "openai/gpt-5")
        val sonnetGroup = summary.groups.first()
        assertThat(sonnetGroup.calls).isEqualTo(3)
        assertThat(sonnetGroup.cost).isEqualTo(Money.usd("0.0405"))
        assertThat(sonnetGroup.usage).isEqualTo(TokenUsage(3010, 401, 200, 0))
        assertThat(summary.total).isEqualTo(Money.usd("0.0605"))
        assertThat(summary.totalUsage.inputTokens).isEqualTo(3510)
    }

    @Test
    fun `summary groups by day, project and context kind and honours the time window`() {
        val byDay = ledger.summary(UsageQuery(from = null, to = null), UsageGroupBy.DAY)
        assertThat(byDay.groups.map { it.key }).containsExactly("2026-09-01", "2026-09-02")
        assertThat(byDay.groups[0].cost).isEqualTo(Money.usd("0.03"))
        assertThat(byDay.groups[1].calls).isEqualTo(2)

        val byProject = ledger.summary(UsageQuery(from = null, to = null), UsageGroupBy.PROJECT)
        assertThat(byProject.groups.map { it.key }).containsExactlyInAnyOrder(projectA.toString(), JpaUsageLedger.UNATTRIBUTED)

        val byKind = ledger.summary(UsageQuery(from = day2, to = null), UsageGroupBy.CONTEXT_KIND)
        assertThat(byKind.groups.map { it.key }).containsExactly("BENCHMARK_ATTEMPT", "MODEL_TEST")

        val byBenchmark = ledger.summary(UsageQuery(from = null, to = null, benchmarkId = benchmarkId), UsageGroupBy.BENCHMARK)
        assertThat(byBenchmark.groups).hasSize(1)
        assertThat(byBenchmark.groups.single().key).isEqualTo(benchmarkId.toString())

        assertThat(ledger.totalCost(UsageQuery(from = day1, to = day2))).isEqualTo(Money.usd("0.03"))
        assertThat(ledger.totalCost(UsageQuery(from = null, to = null, projectId = UUID.randomUUID()))).isEqualTo(Money.ZERO.rounded())
    }

    @Test
    fun `entries are paged newest first`() {
        val first = ledger.entries(UsageQuery(from = null, to = null), page = 0, size = 3)
        assertThat(first.totalElements).isEqualTo(4)
        assertThat(first.content).hasSize(3)
        assertThat(first.content.map { it.occurredAt }).isSortedAccordingTo(Comparator.reverseOrder())
        val second = ledger.entries(UsageQuery(from = null, to = null), page = 1, size = 3)
        assertThat(second.content).hasSize(1)
    }

    @Test
    fun `entries sort by a column, nulls last, and the default stays newest first`() {
        ledger.record(record(day1, gpt, TokenUsage(1, 1), "0.000100", UsageContext(UsageKind.CODE_REVIEW)).copy(durationMs = null))
        fun page(sort: String) = ledger.entries(UsageQuery(from = null, to = null, sort = SortSpec.parse(sort, UsageQuery.ENTRY_SORT_FIELDS)), 0, 10)

        val byCost = page("cost.desc")
        assertThat(byCost.totalElements).isEqualTo(5)
        assertThat(byCost.content.map { it.cost }).containsExactly(
            Money.usd("0.03"), Money.usd("0.02"), Money.usd("0.01"), Money.usd("0.0005"), Money.usd("0.0001"),
        )
        assertThat(page("tokens.asc").content.map { it.usage.inputTokens + it.usage.outputTokens }).containsExactly(2L, 11L, 550L, 1100L, 2300L)
        assertThat(page("model.asc").content.map { it.modelRef.modelId }).containsExactly("claude-sonnet-5", "claude-sonnet-5", "claude-sonnet-5", "gpt-5", "gpt-5")
        assertThat(page("kind.asc").content.first().context.kind).isEqualTo(UsageKind.BENCHMARK_ATTEMPT)
        assertThat(page("duration.asc").content.last().durationMs).isNull()
        assertThat(page("duration.desc").content.last().durationMs).isNull()
        assertThat(page("occurredAt.asc").content.first().occurredAt).isEqualTo(day1)
        assertThat(ledger.entries(UsageQuery(from = null, to = null), 0, 10).content.first().occurredAt).isEqualTo(day2)
    }

    @Test
    fun `entries reject an unknown sort field`() {
        assertThatThrownBy { SortSpec.parse("secret.asc", UsageQuery.ENTRY_SORT_FIELDS) }.isInstanceOf(ValidationException::class.java)
        assertThat(mvc.get().uri("/api/v1/usage/entries").param("sort", "secret.asc")).hasFailed()
        assertThat(mvc.get().uri("/api/v1/usage/entries").param("sort", "cost.desc").param("runId", runId.toString()))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.content[*].context.stepId").asArray().containsExactly("plan", "specify")
    }

    @Test
    fun `usage endpoints serve summary and entries`() {
        assertThat(mvc.get().uri("/api/v1/usage/summary").param("groupBy", "MODEL").param("projectId", projectA.toString()))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.groups[*].key").asArray().containsExactly("openai/gpt-5", "anthropic/claude-sonnet-5")

        assertThat(mvc.get().uri("/api/v1/usage/entries").param("runId", runId.toString()).param("size", "5"))
            .hasStatusOk()
            .bodyJson()
            .satisfies(
                { json -> assertThat(json).extractingPath("$.totalElements").isEqualTo(2) },
                { json -> assertThat(json).extractingPath("$.content[*].modelRef.provider").asArray().containsExactlyInAnyOrder("OPENAI", "ANTHROPIC") },
                { json -> assertThat(json).extractingPath("$.content[0].context.kind").isEqualTo("PIPELINE_STEP") },
            )
    }
}
