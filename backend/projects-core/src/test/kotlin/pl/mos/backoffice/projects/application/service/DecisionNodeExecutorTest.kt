package pl.mos.backoffice.projects.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.application.service.nodes.DecisionNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.HumanGateNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.WaitForCiNodeExecutor
import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.DecisionSpec
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.ValidationException

class DecisionNodeExecutorTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)

    /** Fake evaluator: understands `confidence >= X`, `review.verdict != 'X'`, `true`, `false`, `boom`. */
    private val evaluator = object : ExpressionEvaluator {
        override fun validate(expression: String): String? = null
        override fun evaluate(expression: String, view: WorkflowStateView): Boolean = when {
            expression == "true" -> true
            expression == "false" -> false
            expression == "boom" -> throw ValidationException("cannot evaluate boom")
            expression.startsWith("confidence >=") -> (view.confidence ?: -1.0) >= expression.substringAfter(">=").trim().toDouble()
            expression.startsWith("review.verdict !=") -> view.review.verdict != expression.substringAfter("!=").trim().trim(Char(39))
            else -> error("unsupported $expression")
        }
    }
    private val executor = DecisionNodeExecutor(evaluator)

    private fun context(node: WorkflowNode, state: Map<String, Any?>, run: pl.mos.backoffice.projects.domain.model.WorkflowRun = Fixtures.runningRun(project, definition)): NodeContext {
        val started = run.nodeStarted(node, now)
        return NodeContext(started, project, node, started.nodes.last(), state)
    }

    @Test
    fun `the first true branch wins and confidence comes from the state`() {
        val node = definition.requireNode("confident")
        assertThat(executor.execute(context(node, mapOf(RunStateKeys.CONFIDENCE to 0.9))).port).isEqualTo("yes")
        assertThat(executor.execute(context(node, mapOf(RunStateKeys.CONFIDENCE to 0.5))).port).isEqualTo("no")
        assertThat(executor.execute(context(node, emptyMap())).port).isEqualTo("no")
    }

    @Test
    fun `review verdict is read from the run review and the default branch is taken otherwise`() {
        val node = definition.requireNode("review-ok")
        val approved = Fixtures.runningRun(project, definition).withReview(ReviewReport(Verdict.APPROVE, 95, "fine"))
        assertThat(executor.execute(context(node, emptyMap(), approved)).port).isEqualTo("approved")
        val changes = Fixtures.runningRun(project, definition).withReview(ReviewReport(Verdict.REQUEST_CHANGES, 40, "nope", listOf(Finding(Severity.BLOCKER, "a.kt", 1, "NPE", "x"))))
        assertThat(executor.execute(context(node, emptyMap(), changes)).port).isEqualTo("changes")
    }

    @Test
    fun `no matching branch without a default and evaluation errors fail the node`() {
        val noDefault = WorkflowNode.Decision("d", "D", decision = DecisionSpec(listOf(DecisionBranch("a", "a", "false"))))
        val result = executor.execute(context(noDefault, emptyMap()))
        assertThat(result.failed).isTrue()
        assertThat(result.error).contains("no branch")
        val broken = WorkflowNode.Decision("d", "D", decision = DecisionSpec(listOf(DecisionBranch("a", "a", "boom")), defaultBranch = "z"))
        assertThat(executor.execute(context(broken, emptyMap())).error).contains("boom")
    }

    @Test
    fun `human gate consumes the pending decision, records feedback on revise and maps timeout`() {
        val gate = definition.requireNode("spec-gate")
        val revise = HumanGateNodeExecutor().execute(context(gate, mapOf(RunStateKeys.DECISION to mapOf("option" to "REVISE", "comment" to "more detail"))))
        assertThat(revise.port).isEqualTo("revise")
        assertThat(revise.stateUpdates[RunStateKeys.FEEDBACK]).isEqualTo(listOf("[Spec ok?] more detail"))
        assertThat(revise.stateUpdates[RunStateKeys.DECISION]).isNull()
        assertThat(HumanGateNodeExecutor().execute(context(gate, mapOf(RunStateKeys.DECISION to mapOf("option" to "timeout")))).port).isEqualTo("timeout")
        assertThat(HumanGateNodeExecutor().execute(context(gate, emptyMap())).failed).isTrue()
    }

    @Test
    fun `wait for CI consumes the pending signal`() {
        val ci = definition.requireNode("ci-tests")
        assertThat(WaitForCiNodeExecutor().execute(context(ci, mapOf(RunStateKeys.CI to mapOf("signal" to "tests", "status" to "SUCCESS")))).port).isEqualTo("success")
        assertThat(WaitForCiNodeExecutor().execute(context(ci, mapOf(RunStateKeys.CI to mapOf("signal" to "tests", "status" to "FAIL")))).port).isEqualTo("fail")
        assertThat(WaitForCiNodeExecutor().execute(context(ci, mapOf(RunStateKeys.CI to mapOf("status" to RunStateKeys.CI_STATUS_TIMEOUT)))).port).isEqualTo("timeout")
    }
}
