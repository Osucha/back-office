package pl.mos.backoffice.governance.adapter.out.insights

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import pl.mos.backoffice.governance.application.port.`in`.WorkflowInsightsQuery
import pl.mos.backoffice.governance.domain.model.PortCount
import pl.mos.backoffice.governance.domain.model.WorkflowScope
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.UserId
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Workflow metrics (ADR 0037) on a known dataset in November 2026 (outside the window of the delivery insights test):
 * library workflow L, project workflow W (project alpha) and a copy in project beta.
 * - run 1 (alpha, W v3, L v1, SUCCEEDED/SUCCESS): draft 30 min, review gate approves after 1 h, publish 30 min (2 h); ledger 1.50;
 * - run 2 (alpha, W v4, L v2, modified copy, SUCCEEDED/SUCCESS): draft fails once and is retried, the gate asks for a revision
 *   (2 h), draft again, gate approves (30 min), publish (4 h in all); ledger 3.00;
 * - run 3 (beta, L v2, FAILED): draft fails after 1 h; ledger 0.50;
 * - run 4 (beta, L v2, RUNNING);
 * - run 5 (gamma, L v2, SUCCEEDED): invisible to the project filter; ledger 100;
 * - run 6 (alpha, W v5, not linked, CANCELLED).
 */
@SpringBootTest(classes = [GovernanceTestApplication::class])
@Import(GovernanceTestBeans::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowInsightsIntegrationTest {

    @Autowired lateinit var readModel: JdbcWorkflowInsightsReadModel
    @Autowired lateinit var query: WorkflowInsightsQuery
    @Autowired lateinit var gate: TestGate
    @Autowired lateinit var jdbc: JdbcClient

    private val alpha = UUID.randomUUID()
    private val beta = UUID.randomUUID()
    private val gamma = UUID.randomUUID()
    private val library = UUID.randomUUID()
    private val workflow = UUID.randomUUID()
    private val from = Instant.parse("2026-11-01T00:00:00Z")
    private val to = Instant.parse("2026-12-01T00:00:00Z")
    private val visible = ProjectFilter.Only(setOf(alpha, beta))

    @BeforeAll
    fun seed() {
        val day1 = "2026-11-02"
        val run1 = run(alpha, "alpha", workflow, 3, 1, false, "SUCCEEDED", "SUCCESS", "${day1}T08:00:00Z", "${day1}T10:00:00Z", listOf(
            node("draft", "Draft", "TASK", "llm-task", 1, "SUCCEEDED", "success", day1, "08:00", "08:30", "1.00"),
            node("review", "Review", "HUMAN_GATE", null, 1, "SUCCEEDED", "approve", day1, "08:30", "09:30", "0"),
            node("publish", "Publish", "TASK", "notify-jira", 1, "SUCCEEDED", "success", day1, "09:30", "10:00", "0"),
        ))
        usage(run1, alpha, "1.50")

        val day2 = "2026-11-03"
        val run2 = run(alpha, "alpha", workflow, 4, 2, true, "SUCCEEDED", "SUCCESS", "${day2}T08:00:00Z", "${day2}T12:00:00Z", listOf(
            node("draft", "Draft", "TASK", "llm-task", 1, "FAILED", null, day2, "08:00", "08:10", "0.20"),
            node("draft", "Draft", "TASK", "llm-task", 2, "SUCCEEDED", "success", day2, "08:10", "08:30", "1.00"),
            node("review", "Review", "HUMAN_GATE", null, 1, "SUCCEEDED", "revise", day2, "08:30", "10:30", "0"),
            node("draft", "Draft", "TASK", "llm-task", 3, "SUCCEEDED", "success", day2, "10:30", "11:00", "1.00"),
            node("review", "Review", "HUMAN_GATE", null, 2, "SUCCEEDED", "approve", day2, "11:00", "11:30", "0"),
            node("publish", "Publish", "TASK", "notify-jira", 1, "SUCCEEDED", "success", day2, "11:30", "12:00", "0"),
        ))
        usage(run2, alpha, "3.00")

        val day3 = "2026-11-04"
        val run3 = run(beta, "beta", UUID.randomUUID(), 1, 2, false, "FAILED", "FAILURE", "${day3}T08:00:00Z", "${day3}T09:00:00Z", listOf(
            node("draft", "Draft", "TASK", "llm-task", 1, "FAILED", null, day3, "08:00", "09:00", "0.50"),
        ))
        usage(run3, beta, "0.50")

        run(beta, "beta", UUID.randomUUID(), 1, 2, false, "RUNNING", null, "2026-11-05T08:00:00Z", null, emptyList())

        val run5 = run(gamma, "gamma", UUID.randomUUID(), 1, 2, false, "SUCCEEDED", "SUCCESS", "2026-11-05T08:00:00Z", "2026-11-05T09:00:00Z", emptyList())
        usage(run5, gamma, "100")

        run(alpha, "alpha", workflow, 5, null, false, "CANCELLED", null, "2026-11-06T08:00:00Z", "2026-11-06T08:05:00Z", emptyList())
    }

    @Test
    fun `effectiveness and efficiency of a library workflow over the visible projects`() {
        val i = readModel.compute(WorkflowScope.Library(library), visible, from, to)
        val t = i.totals

        assertThat(t.runs.total).isEqualTo(4)
        assertThat(t.runs.successful).isEqualTo(2)
        assertThat(t.runs.failed).isEqualTo(1)
        assertThat(t.runs.cancelled).isZero()
        assertThat(t.runs.active).isEqualTo(1)
        assertThat(t.runs.modified).isEqualTo(1)
        assertThat(t.successRate).isEqualTo(0.6667)
        assertThat(t.firstPassRate).isEqualTo(0.3333) // run 1 only: run 2 needed a revision and a retry
        assertThat(t.reworkLoopsPerRun).isEqualTo(0.5) // (1 revise + 1 retry) / 4 runs

        // end to end: 7200, 14400, 3600; active (minus gate waits): 3600, 5400, 3600; human wait per run with a gate: 3600, 9000
        assertThat(t.duration.count).isEqualTo(3)
        assertThat(t.duration.p50Seconds).isEqualTo(7200.0)
        assertThat(t.duration.totalSeconds).isEqualTo(25200.0)
        assertThat(t.activeTime.p50Seconds).isEqualTo(3600.0)
        assertThat(t.activeTime.totalSeconds).isEqualTo(12600.0)
        assertThat(t.humanWait.count).isEqualTo(2)
        assertThat(t.humanWait.p50Seconds).isEqualTo(6300.0)

        assertThat(t.totalCost.amount).isEqualByComparingTo(BigDecimal("5.00"))
        assertThat(t.costPerRun.amount).isEqualByComparingTo(BigDecimal("1.25"))
        assertThat(t.costPerSuccessfulRun.amount).isEqualByComparingTo(BigDecimal("2.25"))
        assertThat(t.inputTokens).isEqualTo(3000)
        assertThat(t.outputTokens).isEqualTo(300)

        assertThat(i.byVersion.map { it.version to it.metrics.runs.total }).containsExactly(2 to 3, 1 to 1)
        assertThat(i.byVersion[1].metrics.firstPassRate).isEqualTo(1.0)
        assertThat(i.byProject.map { it.projectKey to it.metrics.runs.total }).containsExactly("alpha" to 2, "beta" to 2)

        assertThat(i.byNode.map { it.nodeId }).containsExactly("draft", "review", "publish")
        val draft = i.byNode[0]
        assertThat(draft.executions).isEqualTo(5)
        assertThat(draft.succeeded).isEqualTo(3)
        assertThat(draft.failed).isEqualTo(2)
        assertThat(draft.failureRate).isEqualTo(0.4)
        assertThat(draft.templateKey).isEqualTo("llm-task")
        assertThat(draft.cost.amount).isEqualByComparingTo(BigDecimal("3.70"))
        assertThat(i.byNode[1].ports).containsExactly(PortCount("approve", 2), PortCount("revise", 1))
        assertThat(i.byNode[1].duration.p50Seconds).isEqualTo(3600.0)

        assertThat(i.weekly.map { it.weekStart }).containsExactly(LocalDate.parse("2026-11-02"))
        assertThat(i.weekly[0].successful).isEqualTo(2)
        assertThat(i.weekly[0].failed).isEqualTo(1)
        assertThat(i.weekly[0].cost.amount).isEqualByComparingTo(BigDecimal("5.00"))
    }

    @Test
    fun `a project workflow is measured by its own versions, linked or not`() {
        val i = readModel.compute(WorkflowScope.Workflow(workflow), ProjectFilter.Only(setOf(alpha)), from, to)
        assertThat(i.totals.runs.total).isEqualTo(3)
        assertThat(i.totals.runs.cancelled).isEqualTo(1)
        assertThat(i.totals.duration.count).isEqualTo(2) // cancelled runs have no duration
        assertThat(i.byVersion.map { it.version }).containsExactly(5, 4, 3)
        assertThat(i.byProject).isEmpty()
        assertThat(readModel.projectOf(workflow)).isEqualTo(alpha)
    }

    @Test
    fun `headlines per library workflow honour the project filter`() {
        assertThat(readModel.libraryHeadlines(visible, from, to).getValue(library).runs.total).isEqualTo(4)
        assertThat(readModel.libraryHeadlines(ProjectFilter.All, from, to).getValue(library).runs.total).isEqualTo(5)
        assertThat(readModel.libraryHeadlines(ProjectFilter.Only(emptySet()), from, to)).isEmpty()
        assertThat(readModel.compute(WorkflowScope.Library(library), ProjectFilter.Only(emptySet()), from, to).totals.runs.total).isZero()
    }

    @Test
    fun `the use case needs library view for a library workflow and project view for a project workflow`() {
        val reader = CurrentUser(PrincipalKind.USER, UserId(UUID.randomUUID()), "r@example.com", "Rita Reader", setOf("library.view"), mapOf(alpha to setOf("project.view")))
        val own = gate.runAs(reader) { query.get(library, null, from, to) }
        assertThat(own.totals.runs.total).isEqualTo(2)
        assertThat(gate.runAs(reader) { query.libraryHeadlines(from, to) }.getValue(library).runs.total).isEqualTo(2)
        assertThat(gate.runAs(reader) { query.get(null, workflow, from, to) }.totals.runs.total).isEqualTo(3)

        assertThatThrownBy { gate.runAs(TestGate.viewer(alpha)) { query.get(library, null, from, to) } }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("library.view") }
        assertThatThrownBy { gate.runAs(TestGate.viewer(beta)) { query.get(null, workflow, from, to) } }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { gate.runAs(reader) { query.get(null, UUID.randomUUID(), from, to) } }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { gate.runAs(reader) { query.get(library, workflow, from, to) } }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { gate.runAs(reader) { query.get(null, null, from, to) } }.isInstanceOf(ValidationException::class.java)
    }

    // ------------------------------------------------------------------------------------------------ seeding

    private fun node(
        id: String, name: String, kind: String, template: String?, attempt: Int, status: String, port: String?,
        day: String, start: String, end: String, cost: String,
    ): String {
        val t = template?.let { "\"$it\"" } ?: "null"
        val p = port?.let { "\"$it\"" } ?: "null"
        return """{"id":"${UUID.randomUUID()}","nodeId":"$id","nodeName":"$name","kind":"$kind","templateKey":$t,"attempt":$attempt,"status":"$status","port":$p,""" +
            """"startedAt":"${day}T$start:00Z","finishedAt":"${day}T$end:00Z","cost":{"amount":$cost,"currency":"USD"},"artifactIds":[]}"""
    }

    private fun run(
        project: UUID, key: String, workflowId: UUID, workflowVersion: Int, libraryVersion: Int?, modified: Boolean,
        status: String, outcome: String?, created: String, finished: String?, nodes: List<String>,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO workflow_run (id, project_id, project_key, workflow_id, workflow_version, workflow_name, status, outcome, nodes, created_at, started_at, finished_at,
                                         library_workflow_id, library_version, library_modified)
               VALUES (:id, :p, :key, :w, :wv, 'Summarise', :status, :outcome, CAST(:nodes AS jsonb), :created, :created, :finished, :lib, :lv, :mod)""",
        ).param("id", id).param("p", project).param("key", key).param("w", workflowId).param("wv", workflowVersion).param("status", status).param("outcome", outcome)
            .param("nodes", nodes.joinToString(",", "[", "]")).param("created", ts(created)).param("finished", finished?.let(::ts))
            .param("lib", if (libraryVersion != null) library else null).param("lv", libraryVersion).param("mod", modified).update()
        return id
    }

    private fun usage(run: UUID, project: UUID, cost: String) {
        jdbc.sql(
            "INSERT INTO usage_record (id, occurred_at, model_label, input_tokens, output_tokens, cost, project_id, run_id, step_id) VALUES (:id, now(), 'm', 1000, 100, :c, :p, :r, 'draft')",
        ).param("id", UUID.randomUUID()).param("c", BigDecimal(cost)).param("p", project).param("r", run).update()
    }

    private fun ts(text: String) = Timestamp.from(Instant.parse(text))
}
