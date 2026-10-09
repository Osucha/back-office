package pl.mos.backoffice.projects.adapter.out.expression

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.application.port.out.ArtifactView
import pl.mos.backoffice.projects.application.port.out.CiContextView
import pl.mos.backoffice.projects.application.port.out.CiMergeRequestView
import pl.mos.backoffice.projects.application.port.out.InputView
import pl.mos.backoffice.projects.application.port.out.ReviewView
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.shared.ValidationException

class SpelExpressionEvaluatorTest {

    private val evaluator = SpelExpressionEvaluator()
    private val view = WorkflowStateView(
        input = InputView("SHOP-1", mapOf("priority" to "high"), CiContextView("GITLAB", mergeRequest = CiMergeRequestView(iid = "7", targetBranch = "main"), hasMergeRequest = true)),
        artifacts = mapOf("spec" to ArtifactView("id", "spec", "SPEC", 120)),
        confidence = 0.9,
        lastOutcome = "SUCCESS",
        review = ReviewView("REQUEST_CHANGES", 40, "needs work", blockers = 1, majors = 0),
    )

    @Test
    fun `documented expressions evaluate against the state view`() {
        assertThat(evaluator.evaluate("confidence >= 0.8", view)).isTrue()
        assertThat(evaluator.evaluate("review.verdict != 'REQUEST_CHANGES'", view)).isFalse()
        assertThat(evaluator.evaluate("artifacts['spec'] != null", view)).isTrue()
        assertThat(evaluator.evaluate("artifacts['plan'] != null", view)).isFalse()
        assertThat(evaluator.evaluate("input.ci.mergeRequest.targetBranch == 'main'", view)).isTrue()
        assertThat(evaluator.evaluate("artifacts['spec'] != null and lastOutcome == 'SUCCESS'", view)).isTrue()
        assertThat(evaluator.evaluate("input.parameters['priority'] == 'high'", view)).isTrue()
        assertThat(evaluator.evaluate("review.blockers > 0 or review.score < 50", view)).isTrue()
        assertThat(evaluator.evaluate("artifacts['spec']?.sizeBytes > 100", view)).isTrue()
    }

    @Test
    fun `the empty view is null-safe for the documented roots`() {
        assertThat(evaluator.evaluate("confidence >= 0.8", WorkflowStateView.EMPTY)).isFalse()
        assertThat(evaluator.evaluate("review.verdict != 'REQUEST_CHANGES'", WorkflowStateView.EMPTY)).isTrue()
        assertThat(evaluator.evaluate("input.ci.mergeRequest.targetBranch == 'main'", WorkflowStateView.EMPTY)).isFalse()
    }

    @Test
    fun `validate reports syntax errors, unknown fields and non-boolean results`() {
        assertThat(evaluator.validate("confidence >= 0.8")).isNull()
        assertThat(evaluator.validate("review.verdict != 'REQUEST_CHANGES'")).isNull()
        assertThat(evaluator.validate("confidence >= ")).contains("syntax error")
        assertThat(evaluator.validate("confidnce >= 0.8")).isNotNull()
        assertThat(evaluator.validate("review.score")).contains("boolean")
        assertThat(evaluator.validate("")).isNotNull()
    }

    @Test
    fun `method invocation and type references are sandboxed`() {
        assertThat(evaluator.validate("T(java.lang.Runtime).getRuntime() != null")).isNotNull()
        assertThatThrownBy { evaluator.evaluate("input.issueKey.toUpperCase() == 'X'", view) }.isInstanceOf(ValidationException::class.java)
    }

    // ---- injection suite (ADR 0029): expressions are sandboxed, data is never evaluated

    @Test
    fun `type references, method calls and bean references are refused`() {
        listOf(
            "T(java.lang.Runtime).getRuntime().exec('id') != null",
            "new java.io.File('/etc/passwd').exists()",
            "review.summary.getClass().forName('java.lang.Runtime') != null",
            "@environment != null",
        ).forEach { expression ->
            assertThat(evaluator.validate(expression)).describedAs(expression).isNotNull()
            assertThatThrownBy { evaluator.evaluate(expression, view) }.describedAs(expression).isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `issue and review text that contains expression syntax is compared as data, never evaluated`() {
        val payload = "#{T(java.lang.Runtime).getRuntime().exec('touch /tmp/pwned')} ' or true or '"
        val hostile = view.copy(
            issue = pl.mos.backoffice.projects.application.port.out.IssueView(key = "SHOP-1", description = payload),
            review = ReviewView("APPROVE", 100, payload, humanReviewRequired = true),
        )
        assertThat(evaluator.evaluate("issue.description == 'harmless'", hostile)).isFalse()
        assertThat(evaluator.evaluate("review.verdict != 'REQUEST_CHANGES' and !review.humanReviewRequired", hostile)).isFalse()
        // method calls on data are not available either
        assertThatThrownBy { evaluator.evaluate("review.summary.length() > 0", hostile) }.isInstanceOf(ValidationException::class.java)
    }
}
