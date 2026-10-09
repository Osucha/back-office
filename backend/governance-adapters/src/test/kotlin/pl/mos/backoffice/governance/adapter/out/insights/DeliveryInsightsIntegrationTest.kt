package pl.mos.backoffice.governance.adapter.out.insights

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.governance.adapter.GovernanceTestApplication
import pl.mos.backoffice.governance.adapter.GovernanceTestBeans
import pl.mos.backoffice.governance.adapter.TestGate
import pl.mos.backoffice.governance.application.port.`in`.DeliveryInsightsQuery
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.ProjectFilter
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every delivery metric of docs/architecture/11-governance.md on a known dataset (window September 2026, UTC):
 * - run A (SHOP-1, SUCCEEDED/SUCCESS, MR): implement fails once, is retried, a gate asks for a revision (2 h),
 *   implement again, gate approves (1 h), CI wait 30 min; ledger 4.00 on anthropic/claude-opus-5 (2 calls);
 * - run B (SHOP-2, FAILED): specify fails; ledger 0.50 on openai/gpt-5;
 * - run C (SHOP-1, CANCELLED) created in August: outside the window, but the first start of SHOP-1;
 * - run D (no issue, SUCCEEDED/SUCCESS, no MR): LLM review, gate approves (30 min); ledger 0.25 on openai/gpt-5;
 * - run E of another project (invisible to the project filter): ledger 100.
 */
@SpringBootTest(classes = [GovernanceTestApplication::class])
@Import(GovernanceTestBeans::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryInsightsIntegrationTest {

    @Autowired lateinit var readModel: JdbcDeliveryInsightsReadModel
    @Autowired lateinit var query: DeliveryInsightsQuery
    @Autowired lateinit var gate: TestGate
    @Autowired lateinit var jdbc: JdbcClient

    private val p1 = UUID.randomUUID()
    private val p2 = UUID.randomUUID()
    private val workflow = UUID.randomUUID()
    private val from = Instant.parse("2026-09-01T00:00:00Z")
    private val to = Instant.parse("2026-10-01T00:00:00Z")
    private val runA = UUID.randomUUID()
    private val runB = UUID.randomUUID()
    private val runD = UUID.randomUUID()

    @BeforeAll
    fun seed() {
        run(runA, p1, "SHOP-1", "SUCCEEDED", "SUCCESS", "2026-09-07T08:00:00Z", "2026-09-07T20:00:00Z", mr = true, nodes = listOf(
            node("impl", "Implement", "TASK", "implement", 1, "FAILED", "08:00", "09:00", "1.00", job = true, day = "2026-09-07"),
            node("impl", "Implement", "TASK", "implement", 2, "SUCCEEDED", "09:00", "10:00", "2.00", job = true, day = "2026-09-07"),
            node("gate", "Review", "HUMAN_GATE", null, 1, "SUCCEEDED", "10:00", "12:00", "0", day = "2026-09-07"),
            node("impl", "Implement", "TASK", "implement", 3, "SUCCEEDED", "12:00", "13:00", "1.00", job = true, day = "2026-09-07"),
            node("gate", "Review", "HUMAN_GATE", null, 2, "SUCCEEDED", "13:00", "14:00", "0", day = "2026-09-07"),
            node("ci", "Tests", "WAIT_FOR_CI", null, 1, "SUCCEEDED", "14:00", "14:30", "0", day = "2026-09-07"),
        ))
        decision(runA, p1, "gate", "revise")
        decision(runA, p1, "gate", "approve")
        usage(runA, p1, "impl", "anthropic/claude-opus-5", "3.00", "2026-09-07T09:30:00Z")
        usage(runA, p1, "impl", "anthropic/claude-opus-5", "1.00", "2026-09-07T12:30:00Z")

        run(runB, p1, "SHOP-2", "FAILED", "FAILURE", "2026-09-08T08:00:00Z", "2026-09-08T09:00:00Z", nodes = listOf(
            node("spec", "Specify", "TASK", "specify", 1, "FAILED", "08:00", "09:00", "0.50", day = "2026-09-08"),
        ))
        usage(runB, p1, "spec", "openai/gpt-5", "0.50", "2026-09-08T08:30:00Z")

        run(UUID.randomUUID(), p1, "SHOP-1", "CANCELLED", null, "2026-08-30T08:00:00Z", "2026-08-30T09:00:00Z", nodes = emptyList())

        run(runD, p1, null, "SUCCEEDED", "SUCCESS", "2026-09-15T00:00:00Z", "2026-09-15T01:00:00Z", nodes = listOf(
            node("rev", "Review", "TASK", "review", 1, "SUCCEEDED", "00:00", "00:30", "0.25", day = "2026-09-15"),
            node("gate2", "Sign-off", "HUMAN_GATE", null, 1, "SUCCEEDED", "00:30", "01:00", "0", day = "2026-09-15"),
        ))
        decision(runD, p1, "gate2", "approve")
        usage(runD, p1, "rev", "openai/gpt-5", "0.25", "2026-09-15T00:10:00Z")

        val runE = UUID.randomUUID()
        run(runE, p2, null, "SUCCEEDED", "SUCCESS", "2026-09-10T00:00:00Z", "2026-09-10T01:00:00Z", mr = true, nodes = emptyList())
        usage(runE, p2, "x", "anthropic/claude-opus-5", "100", "2026-09-10T00:10:00Z")
    }

    @Test
    fun `every metric on the seeded dataset`() {
        val i = readModel.compute(ProjectFilter.Only(setOf(p1)), from, to)

        assertThat(i.runs.total).isEqualTo(3)
        assertThat(i.runs.succeeded).isEqualTo(2)
        assertThat(i.runs.failed).isEqualTo(1)
        assertThat(i.runs.cancelled).isEqualTo(0)

        // lead time SHOP-1: first start 2026-08-30T08:00 (run C) -> first successful agent job of the MR run 2026-09-07T10:00
        assertThat(i.leadTime.count).isEqualTo(1)
        assertThat(i.leadTime.p50Seconds).isEqualTo(8 * 86400.0 + 2 * 3600)

        // human wait: 7200, 3600, 1800
        assertThat(i.humanWait.count).isEqualTo(3)
        assertThat(i.humanWait.totalSeconds).isEqualTo(12600.0)
        assertThat(i.humanWait.p50Seconds).isEqualTo(3600.0)
        assertThat(i.humanWait.p90Seconds).isEqualTo(6480.0)
        assertThat(i.ciWait.count).isEqualTo(1)
        assertThat(i.ciWait.p50Seconds).isEqualTo(1800.0)

        // rework: 1 revise decision + 1 failure->retry (implement attempt 2) over 3 runs
        assertThat(i.reworkLoopsPerRun).isCloseTo(2.0 / 3, Offset.offset(0.0001))

        assertThat(i.totalCost.amount).isEqualByComparingTo(BigDecimal("4.75"))
        assertThat(i.costPerSuccessfulRun.amount).isEqualByComparingTo(BigDecimal("2.125")) // (4.00 + 0.25) / 2
        assertThat(i.costPerMergeRequest.amount).isEqualByComparingTo(BigDecimal("4.00")) // only run A has an MR

        assertThat(i.agentSuccessByTemplate.map { Triple(it.key, it.jobs, it.succeeded) })
            .containsExactly(Triple("implement", 3, 2), Triple("review", 1, 1), Triple("specify", 1, 0))
        assertThat(i.agentSuccessByTemplate.first().cost.amount).isEqualByComparingTo(BigDecimal("4.00"))
        assertThat(i.agentSuccessByModel.map { Triple(it.key, it.jobs, it.succeeded) })
            .containsExactly(Triple("anthropic/claude-opus-5", 3, 2), Triple("openai/gpt-5", 2, 1))
        assertThat(i.agentSuccessByModel[1].cost.amount).isEqualByComparingTo(BigDecimal("0.75"))

        assertThat(i.gateDecisions.map { it.option to it.count }).containsExactly("approve" to 2, "revise" to 1)

        assertThat(i.weekly.map { it.weekStart }).containsExactly(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-14"))
        assertThat(i.weekly[0].succeeded).isEqualTo(1)
        assertThat(i.weekly[0].failed).isEqualTo(1)
        assertThat(i.weekly[0].humanWaitHours).isEqualTo(3.0)
        assertThat(i.weekly[0].cost.amount).isEqualByComparingTo(BigDecimal("4.50"))
        assertThat(i.weekly[1].succeeded).isEqualTo(1)
        assertThat(i.weekly[1].humanWaitHours).isEqualTo(0.5)
        assertThat(i.weekly[1].cost.amount).isEqualByComparingTo(BigDecimal("0.25"))

        assertThat(i.bottleneck?.nodeId).isEqualTo("gate")
        assertThat(i.bottleneck?.nodeName).isEqualTo("Review")
        assertThat(i.bottleneck?.p90WaitSeconds).isEqualTo(6840.0)

        assertThat(i.modelMix.map { Triple(it.model, it.calls, it.cost.amount.stripTrailingZeros().toPlainString()) })
            .containsExactly(Triple("anthropic/claude-opus-5", 2L, "4"), Triple("openai/gpt-5", 2L, "0.75"))
    }

    @Test
    fun `all visible projects include the other project, an empty filter sees nothing`() {
        val all = readModel.compute(ProjectFilter.All, from, to)
        assertThat(all.runs.total).isEqualTo(4)
        assertThat(all.totalCost.amount).isEqualByComparingTo(BigDecimal("104.75"))
        val none = readModel.compute(ProjectFilter.Only(emptySet()), from, to)
        assertThat(none.runs.total).isZero()
        assertThat(none.humanWait.count).isZero()
    }

    @Test
    fun `the use case checks project view and filters by the caller's projects`() {
        val viewer = TestGate.viewer(p1)
        val own = gate.runAs(viewer) { query.get(null, from, to) }
        assertThat(own.runs.total).isEqualTo(3)
        assertThatThrownBy { gate.runAs(viewer) { query.get(p2, from, to) } }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(gate.runAs(viewer) { query.get(p1, from, to) }.projectId).isEqualTo(p1)
    }

    // ------------------------------------------------------------------------------------------------ seeding

    private fun node(
        id: String, name: String, kind: String, template: String?, attempt: Int, status: String,
        start: String, end: String, cost: String, job: Boolean = false, day: String,
    ): String {
        val t = template?.let { "\"$it\"" } ?: "null"
        val j = if (job) "\"${UUID.randomUUID()}\"" else "null"
        return """{"id":"${UUID.randomUUID()}","nodeId":"$id","nodeName":"$name","kind":"$kind","templateKey":$t,"attempt":$attempt,"status":"$status",""" +
            """"startedAt":"${day}T$start:00Z","finishedAt":"${day}T$end:00Z","runnerJobId":$j,"cost":{"amount":$cost,"currency":"USD"},"artifactIds":[]}"""
    }

    private fun run(id: UUID, project: UUID, issue: String?, status: String, outcome: String?, created: String, finished: String, mr: Boolean = false, nodes: List<String>) {
        jdbc.sql(
            """INSERT INTO workflow_run (id, project_id, project_key, workflow_id, workflow_name, issue_key, status, outcome, nodes, merge_request, created_at, started_at, finished_at)
               VALUES (:id, :p, 'shop', :w, 'Issue to MR', :issue, :status, :outcome, CAST(:nodes AS jsonb), CAST(:mr AS jsonb), :created, :created, :finished)""",
        ).param("id", id).param("p", project).param("w", workflow).param("issue", issue).param("status", status).param("outcome", outcome)
            .param("nodes", nodes.joinToString(",", "[", "]"))
            .param("mr", if (mr) """{"url":"https://git.example.com/shop/-/merge_requests/1","branch":"feature/x","targetBranch":"main"}""" else null)
            .param("created", ts(created)).param("finished", ts(finished)).update()
    }

    private fun decision(run: UUID, project: UUID, node: String, option: String) {
        jdbc.sql("INSERT INTO approval_request (id, run_id, node_id, project_id, status, decision, created_at) VALUES (:id, :r, :n, :p, 'DECIDED', CAST(:d AS jsonb), now())")
            .param("id", UUID.randomUUID()).param("r", run).param("n", node).param("p", project)
            .param("d", """{"option":"$option","decidedAt":"2026-09-07T12:00:00Z"}""").update()
    }

    private fun usage(run: UUID, project: UUID, step: String, model: String, cost: String, at: String) {
        jdbc.sql(
            "INSERT INTO usage_record (id, occurred_at, model_label, input_tokens, output_tokens, cost, project_id, run_id, step_id) VALUES (:id, :at, :m, 1000, 100, :c, :p, :r, :s)",
        ).param("id", UUID.randomUUID()).param("at", ts(at)).param("m", model).param("c", BigDecimal(cost)).param("p", project).param("r", run).param("s", step).update()
    }

    private fun ts(text: String) = Timestamp.from(Instant.parse(text))
}
