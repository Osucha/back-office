package pl.mos.backoffice.llm.gateway

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage

/**
 * LLM usage meters (deploy/observability/dashboards/llm-usage-and-cost.json), exported by the Prometheus registry as
 *
 * - `backoffice_llm_tokens_total{provider,model,type}` tokens by `type` = `input` | `output` | `cache_read` | `cache_write`;
 * - `backoffice_llm_cost_usd_total{provider,model}` cost at the model's catalog pricing.
 *
 * Booked for every call of the in-process gateway and of the sandbox LLM proxy. Tags are bounded on purpose: the
 * provider and the model id only, never a user, project, run or job id (those are in the usage ledger).
 */
class LlmMetrics(private val registry: MeterRegistry?) {

    fun record(model: ModelRef, usage: TokenUsage, cost: Money) {
        val meters = registry ?: return
        val provider = model.provider.name.lowercase()
        val tokens = listOf(
            "input" to usage.inputTokens,
            "output" to usage.outputTokens,
            "cache_read" to usage.cacheReadTokens,
            "cache_write" to usage.cacheWriteTokens,
        )
        tokens.forEach { (type, count) ->
            if (count > 0) {
                Counter.builder(TOKENS).description("LLM tokens by provider, model and type").baseUnit("tokens")
                    .tags(PROVIDER, provider, MODEL, model.modelId, "type", type).register(meters).increment(count.toDouble())
            }
        }
        if (cost.amount.signum() > 0) {
            Counter.builder(COST_USD).description("LLM cost in USD at the model's catalog pricing")
                .tags(PROVIDER, provider, MODEL, model.modelId).register(meters).increment(cost.amount.toDouble())
        }
    }

    companion object {
        const val TOKENS = "backoffice.llm.tokens"
        const val COST_USD = "backoffice.llm.cost.usd"
        private const val PROVIDER = "provider"
        private const val MODEL = "model"

        /** No registry: nothing is recorded (tests, slices without actuator). */
        val NOOP = LlmMetrics(null)
    }
}
