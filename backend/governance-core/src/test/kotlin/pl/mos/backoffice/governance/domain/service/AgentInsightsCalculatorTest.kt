package pl.mos.backoffice.governance.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.domain.model.StepAgent
import pl.mos.backoffice.governance.domain.model.TaskStepExecution
import pl.mos.backoffice.shared.Money
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * ADR 0059: agent insights on a known set of executions.
 * - run 1 (successful): draft by Claude Code/Sonnet fails (1.00), is retried and succeeds (2.00): one accepted, not first pass;
 *   review by Model answer/Haiku succeeds (0.10): accepted, first pass;
 * - run 2 (successful): draft by Claude Code/Sonnet succeeds (3.00), the gate sends it back, it succeeds again (1.00):
 *   only the last one is accepted; review by Model answer/Haiku (0.10) accepted;
 * - run 3 (failed): draft by OpenCode/Sonnet succeeds (0.50): not accepted (the run failed);
 * - run 4: an execution before ADR 0059 (no agent), a skipped one and an open one.
 */
class AgentInsightsCalculatorTest {

    private val from = Instant.parse("2026-11-01T00:00:00Z")
    private val to = Instant.parse("2026-12-01T00:00:00Z")
    private val sonnet = UUID.randomUUID()
    private val haiku = UUID.randomUUID()
    private val claude = StepAgent("claude-code", "Claude Code", "CLAUDE_CODE", sonnet, "Sonnet")
    private val answer = StepAgent("model-answer", "Model answer", null, haiku, "Haiku")
    private val opencode = StepAgent("opencode", "OpenCode", "OPENCODE", sonnet, "Sonnet")

    private val run1 = UUID.randomUUID()
    private val run2 = UUID.randomUUID()
    private val run3 = UUID.randomUUID()
    private val run4 = UUID.randomUUID()

    private fun exec(
        run: UUID, ok: Boolean, node: String, index: Int, template: String?, attempt: Int, status: String, agent: StepAgent?, cost: String, secs: Double? = 60.0,
    ) = TaskStepExecution(run, ok, node, index, template, attempt, status, agent, Money(BigDecimal(cost)), Money(BigDecimal(cost)), secs)

    private val executions = listOf(
        exec(run1, true, "draft", 1, "agent-task", 1, "FAILED", claude, "1.00", 30.0),
        exec(run1, true, "draft", 2, "agent-task", 2, "SUCCEEDED", claude, "2.00", 90.0),
        exec(run1, true, "review", 3, "code-review", 1, "SUCCEEDED", answer, "0.10", 10.0),
        exec(run2, true, "draft", 1, "agent-task", 1, "SUCCEEDED", claude, "3.00", 120.0),
        exec(run2, true, "draft", 3, "agent-task", 2, "SUCCEEDED", claude, "1.00", 60.0),
        exec(run2, true, "review", 4, "code-review", 1, "SUCCEEDED", answer, "0.10", 20.0),
        exec(run3, false, "draft", 1, "agent-task", 1, "SUCCEEDED", opencode, "0.50"),
        exec(run4, true, "draft", 1, "agent-task", 1, "SUCCEEDED", null, "9.00"),
        exec(run4, true, "skipped", 2, "agent-task", 1, "SKIPPED", null, "0"),
        exec(run4, true, "open", 3, "agent-task", 1, "RUNNING", null, "0", null),
    )

    @Test
    fun `rows per agent and model with accepted results, first-pass rate and the cost of an accepted result`() {
        val i = AgentInsightsCalculator.insights(from, to, null, executions)

        assertThat(i.byAgent.map { it.agentKey }).describedAs("lowest cost per accepted first, none accepted last")
            .containsExactly("model-answer", "claude-code", "opencode")
        val haikuRow = i.byAgent[0]
        assertThat(haikuRow.accepted).isEqualTo(2)
        assertThat(haikuRow.firstPassRate).isEqualTo(1.0)
        assertThat(haikuRow.costPerAccepted!!.amount).isEqualByComparingTo("0.10")
        assertThat(haikuRow.harness).isNull()
        assertThat(haikuRow.modelLabel).isEqualTo("Haiku")

        val claudeRow = i.byAgent[1]
        assertThat(claudeRow.executions).isEqualTo(4)
        assertThat(claudeRow.succeeded).isEqualTo(3)
        assertThat(claudeRow.failed).isEqualTo(1)
        assertThat(claudeRow.accepted).describedAs("run 1 attempt 2 and run 2 attempt 2").isEqualTo(2)
        assertThat(claudeRow.firstPassRate).isEqualTo(0.0)
        assertThat(claudeRow.cost.amount).isEqualByComparingTo("7.00")
        assertThat(claudeRow.costPerAccepted!!.amount).isEqualByComparingTo("3.50")
        assertThat(claudeRow.duration.count).isEqualTo(4)
        assertThat(claudeRow.duration.p50Seconds).isEqualTo(75.0)
        assertThat(claudeRow.duration.totalSeconds).isEqualTo(300.0)

        val openRow = i.byAgent[2]
        assertThat(openRow.accepted).isZero()
        assertThat(openRow.firstPassRate).isNull()
        assertThat(openRow.costPerAccepted).isNull()

        assertThat(i.unattributed).describedAs("finished executions without an agent; skipped and open ones do not count").isEqualTo(1)
    }

    @Test
    fun `agents are compared per template, with the names the library knows`() {
        val i = AgentInsightsCalculator.insights(from, to, null, executions, mapOf("agent-task" to "Agent task"))

        assertThat(i.byTemplate.map { it.templateKey }).containsExactly("agent-task", "code-review")
        assertThat(i.byTemplate[0].templateName).isEqualTo("Agent task")
        assertThat(i.byTemplate[1].templateName).isNull()
        assertThat(i.byTemplate[0].rows.map { it.agentKey }).containsExactly("claude-code", "opencode")
        assertThat(i.byTemplate[1].rows.single().agentKey).isEqualTo("model-answer")
    }

    @Test
    fun `the same agent with another model is another row, nothing in scope gives empty insights`() {
        val other = claude.copy(modelProfileId = haiku, modelLabel = "Haiku")
        val i = AgentInsightsCalculator.insights(from, to, null, listOf(
            exec(run1, true, "a", 1, "t", 1, "SUCCEEDED", claude, "1"),
            exec(run1, true, "b", 2, "t", 1, "SUCCEEDED", other, "2"),
        ))
        assertThat(i.byAgent.map { it.modelLabel }).containsExactly("Sonnet", "Haiku")

        val empty = AgentInsightsCalculator.insights(from, to, UUID.randomUUID(), emptyList())
        assertThat(empty.byAgent).isEmpty()
        assertThat(empty.byTemplate).isEmpty()
        assertThat(empty.unattributed).isZero()
    }

    @Test
    fun `percentiles interpolate like percentile_cont`() {
        assertThat(AgentInsightsCalculator.percentile(listOf(10.0, 20.0, 30.0, 40.0), 0.5)).isEqualTo(25.0)
        assertThat(AgentInsightsCalculator.percentile(listOf(10.0, 20.0, 30.0, 40.0), 0.9)).isEqualTo(37.0)
        assertThat(AgentInsightsCalculator.percentile(listOf(5.0), 0.9)).isEqualTo(5.0)
        assertThat(AgentInsightsCalculator.distribution(emptyList()).count).isZero()
    }
}
