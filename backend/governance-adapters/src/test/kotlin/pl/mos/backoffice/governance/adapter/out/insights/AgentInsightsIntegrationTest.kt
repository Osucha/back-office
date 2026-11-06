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
import pl.mos.backoffice.governance.adapter.`in`.rest.AgentInsightsDto
import pl.mos.backoffice.governance.application.port.`in`.AgentInsightsQuery
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.ProjectFilter
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * ADR 0059: the agent insights read model on runs created in January 2027 (outside the windows of the other insights
 * tests): project alpha has a successful run (draft by Claude Code failed once, then succeeded; review by a model answer)
 * and a failed run (draft by OpenCode); project beta (not visible to alpha's viewer) a run with an execution stored
 * before ADR 0059 (no agent).
 */
@SpringBootTest(classes = [GovernanceTestApplication::class])
@Import(GovernanceTestBeans::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentInsightsIntegrationTest {

    @Autowired lateinit var readModel: JdbcAgentInsightsReadModel
    @Autowired lateinit var query: AgentInsightsQuery
    @Autowired lateinit var gate: TestGate
    @Autowired lateinit var jdbc: JdbcClient

    private val alpha = UUID.randomUUID()
    private val beta = UUID.randomUUID()
    private val sonnet = UUID.randomUUID()
    private val from = Instant.parse("2027-01-01T00:00:00Z")
    private val to = Instant.parse("2027-02-01T00:00:00Z")

    @BeforeAll
    fun seed() {
        val claude = agent("claude-code", "Claude Code", "CLAUDE_CODE", sonnet, "Sonnet")
        val answer = agent("model-answer", "Model answer", null, null, null)
        run(alpha, "SUCCEEDED", "SUCCESS", "2027-01-05T08:00:00Z", listOf(
            node("start", "START", null, 1, "SUCCEEDED", null, "08:00", "08:00", "0"),
            node("draft", "TASK", "agent-task", 1, "FAILED", claude, "08:00", "08:10", "1.00"),
            node("draft", "TASK", "agent-task", 2, "SUCCEEDED", claude, "08:10", "08:30", "2.00"),
            node("review", "TASK", "code-review", 1, "SUCCEEDED", answer, "08:30", "08:31", "0.10"),
        ))
        run(alpha, "FAILED", "FAILURE", "2027-01-06T08:00:00Z", listOf(
            node("draft", "TASK", "agent-task", 1, "SUCCEEDED", agent("opencode", "OpenCode", "OPENCODE", sonnet, "Sonnet"), "08:00", "08:20", "0.50"),
        ))
        run(beta, "SUCCEEDED", "SUCCESS", "2027-01-07T08:00:00Z", listOf(
            node("draft", "TASK", "agent-task", 1, "SUCCEEDED", null, "08:00", "08:05", "9.00"),
        ))
        run(alpha, "SUCCEEDED", "SUCCESS", "2026-12-31T23:00:00Z", listOf(
            node("draft", "TASK", "agent-task", 1, "SUCCEEDED", claude, "23:00", "23:05", "100"),
        ))
    }

    @Test
    fun `task step executions come back with their agent, cost, duration and the result of their run`() {
        val steps = readModel.taskSteps(ProjectFilter.Only(setOf(alpha, beta)), from, to)

        assertThat(steps.map { it.nodeId }).describedAs("task steps only, in run order, outside the window left out")
            .containsExactly("draft", "draft", "review", "draft", "draft")
        val retry = steps[1]
        assertThat(retry.agent!!.agentKey).isEqualTo("claude-code")
        assertThat(retry.agent!!.modelProfileId).isEqualTo(sonnet)
        assertThat(retry.attempt).isEqualTo(2)
        assertThat(retry.runSuccessful).isTrue()
        assertThat(retry.cost.amount).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(retry.durationSeconds).isEqualTo(1200.0)
        assertThat(retry.index).isGreaterThan(steps[0].index)
        assertThat(steps[2].agent!!.harness).isNull()
        assertThat(steps[3].runSuccessful).isFalse()
        assertThat(steps[4].agent).isNull()

        assertThat(readModel.taskSteps(ProjectFilter.Only(emptySet()), from, to)).isEmpty()
        assertThat(readModel.taskSteps(ProjectFilter.All, from, to).size).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `the use case aggregates the visible projects and needs project view on one project`() {
        val insights = gate.runAs(TestGate.viewer(alpha)) { query.get(null, from, to) }
        val dto = AgentInsightsDto.of(insights)

        assertThat(dto.byAgent.map { it.agentKey }).containsExactly("model-answer", "claude-code", "opencode")
        val claude = dto.byAgent[1]
        assertThat(claude.executions).isEqualTo(2)
        assertThat(claude.accepted).isEqualTo(1)
        assertThat(claude.costPerAccepted!!.amount).isEqualByComparingTo(BigDecimal("3.00"))
        assertThat(claude.duration.count).isEqualTo(2)
        assertThat(dto.byAgent[2].costPerAccepted).isNull()
        assertThat(dto.byTemplate.map { it.templateKey }).containsExactly("agent-task", "code-review")
        assertThat(dto.unattributed).describedAs("beta is not visible").isZero()

        assertThatThrownBy { gate.runAs(TestGate.viewer(alpha)) { query.get(beta, from, to) } }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(gate.runAs(TestGate.viewer(beta)) { query.get(beta, from, to) }.unattributed).isEqualTo(1)
    }

    // ------------------------------------------------------------------------------------------------ seeding

    private fun agent(key: String, name: String, harness: String?, model: UUID?, label: String?): String =
        """{"agentKey":"$key","agentName":"$name","harness":${q(harness)},"modelProfileId":${q(model?.toString())},"modelLabel":${q(label)}}"""

    private fun q(value: String?) = value?.let { "\"$it\"" } ?: "null"

    private fun node(id: String, kind: String, template: String?, attempt: Int, status: String, agent: String?, start: String, end: String, cost: String): String =
        """{"id":"${UUID.randomUUID()}","nodeId":"$id","nodeName":"$id","kind":"$kind","templateKey":${q(template)},"attempt":$attempt,"status":"$status",""" +
            """"startedAt":"2027-01-05T$start:00Z","finishedAt":"2027-01-05T$end:00Z","cost":{"amount":$cost,"currency":"USD"},"artifactIds":[],"agent":${agent ?: "null"}}"""

    private fun run(project: UUID, status: String, outcome: String?, created: String, nodes: List<String>): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO workflow_run (id, project_id, project_key, workflow_id, workflow_version, workflow_name, status, outcome, nodes, created_at, started_at, finished_at)
               VALUES (:id, :p, 'K', :w, 1, 'Agents', :status, :outcome, CAST(:nodes AS jsonb), :created, :created, :created)""",
        ).param("id", id).param("p", project).param("w", UUID.randomUUID()).param("status", status).param("outcome", outcome)
            .param("nodes", nodes.joinToString(",", "[", "]")).param("created", Timestamp.from(Instant.parse(created))).update()
        return id
    }
}
