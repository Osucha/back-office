package pl.mos.backoffice.projects.adapter.out.expression

import org.springframework.expression.EvaluationException
import org.springframework.expression.Expression
import org.springframework.expression.ParseException
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.SimpleEvaluationContext
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.shared.ValidationException
import java.util.concurrent.ConcurrentHashMap

/**
 * [ExpressionEvaluator] on Spring Expression Language (ADR 0014). Expressions run in a read-only
 * `SimpleEvaluationContext` (property reads, indexers, literals, operators; no method invocation, no
 * bean references, no type references) with a [WorkflowStateView] as root object. `validate` parses
 * and evaluates against the empty view so typos in field names surface at design time.
 */
@Component
class SpelExpressionEvaluator : ExpressionEvaluator {

    private val parser = SpelExpressionParser(SpelParserConfiguration(false, false, MAX_EXPRESSION_LENGTH))
    private val cache = ConcurrentHashMap<String, Expression>()

    override fun validate(expression: String): String? {
        if (expression.isBlank()) return "expression must not be blank"
        if (expression.length > MAX_EXPRESSION_LENGTH) return "expression is longer than $MAX_EXPRESSION_LENGTH characters"
        return try {
            val value = parse(expression).getValue(context(WorkflowStateView.EMPTY))
            if (value is Boolean) null else "expression must be boolean, got ${value?.javaClass?.simpleName ?: "null"}"
        } catch (e: ParseException) {
            "syntax error: ${e.simpleMessage}"
        } catch (e: EvaluationException) {
            e.simpleMessage
        } catch (e: IllegalStateException) {
            e.message ?: "invalid expression"
        }
    }

    override fun evaluate(expression: String, view: WorkflowStateView): Boolean = try {
        when (val value = parse(expression).getValue(context(view))) {
            is Boolean -> value
            null -> false
            else -> throw ValidationException("expression $expression is not boolean (got ${value.javaClass.simpleName})", "expression")
        }
    } catch (e: ParseException) {
        throw ValidationException("syntax error in $expression: ${e.simpleMessage}", "expression")
    } catch (e: EvaluationException) {
        throw ValidationException("cannot evaluate $expression: ${e.simpleMessage}", "expression")
    }

    private fun parse(expression: String): Expression = cache.computeIfAbsent(expression.trim()) { parser.parseExpression(it) }

    private fun context(view: WorkflowStateView) = SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(view).build()

    companion object {
        const val MAX_EXPRESSION_LENGTH = 2000
    }
}
