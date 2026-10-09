package pl.mos.backoffice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal

class PricingTest {

    @Test
    fun `computes cost from input, output and cache tokens`() {
        val pricing = Pricing(
            inputPerMTok = BigDecimal("5"),
            outputPerMTok = BigDecimal("25"),
            cacheReadPerMTok = BigDecimal("0.5"),
        )
        val cost = pricing.costOf(TokenUsage(inputTokens = 1_000_000, outputTokens = 100_000, cacheReadTokens = 2_000_000))
        assertThat(cost).isEqualTo(Money.usd("8.5"))
    }

    @Test
    fun `cache prices default to input price when not set`() {
        val pricing = Pricing(inputPerMTok = BigDecimal("2"), outputPerMTok = BigDecimal("10"))
        val cost = pricing.costOf(TokenUsage(cacheReadTokens = 500_000, cacheWriteTokens = 500_000))
        assertThat(cost).isEqualTo(Money.usd("2"))
    }

    @Test
    fun `money arithmetic keeps currency and rounds to six decimals`() {
        val a = Money.usd("0.1234567")
        val b = Money.usd("0.0000004")
        assertThat((a + b).amount).isEqualByComparingTo(BigDecimal("0.123457"))
        assertThat(Money.sum(listOf(a, b, Money.ZERO))).isEqualTo((a + b))
    }
}
