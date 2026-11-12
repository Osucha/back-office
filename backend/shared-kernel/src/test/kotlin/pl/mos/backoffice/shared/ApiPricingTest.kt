package pl.mos.backoffice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.llm.ApiPricing
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal

/** ADR 0065: a login model's tokens at the API prices of its Claude family. */
class ApiPricingTest {

    private fun profile(
        modelId: String,
        input: String,
        output: String,
        runtime: ModelRuntime = ModelRuntime.API,
        provider: Provider = Provider.ANTHROPIC,
        enabled: Boolean = true,
        name: String = modelId,
    ) = ModelProfileSnapshot(ModelProfileId.new(), name, ModelRef(provider, modelId), Pricing(BigDecimal(input), BigDecimal(output)), enabled, runtime)

    private val login = profile("claude-sonnet-5", "0", "0", ModelRuntime.CLAUDE_CODE_LOGIN)

    @Test
    fun `the family is read from the model id, else the display name`() {
        assertThat(ApiPricing.familyOf("claude-opus-4-5")).isEqualTo("opus")
        assertThat(ApiPricing.familyOf("Claude-Haiku-4")).isEqualTo("haiku")
        assertThat(ApiPricing.familyOf("default", "Sonnet via my login")).isEqualTo("sonnet")
        assertThat(ApiPricing.familyOf("gpt-5", "GPT")).isNull()
    }

    @Test
    fun `a login profile takes the most expensive enabled API profile of its family`() {
        val catalogue = listOf(
            login,
            profile("claude-sonnet-4-5", "3", "15"),
            profile("claude-sonnet-5", "3", "18"),
            profile("claude-sonnet-6", "5", "25", enabled = false),
            profile("claude-sonnet-x", "9", "90", runtime = ModelRuntime.CLAUDE_CODE_LOGIN),
            profile("claude-sonnet-proxy", "9", "90", provider = Provider.OPENAI),
            profile("claude-opus-5", "15", "75"),
            profile("claude-sonnet-free", "0", "0"),
        )
        assertThat(ApiPricing.of(login, catalogue).outputPerMTok).isEqualByComparingTo("18")
    }

    @Test
    fun `equal prices are decided by the newest model id`() {
        val older = profile("claude-sonnet-4-5", "3", "15")
        val newer = profile("claude-sonnet-5", "3", "15").copy(pricing = Pricing(BigDecimal("3"), BigDecimal("15"), cacheReadPerMTok = BigDecimal("0.3")))
        assertThat(ApiPricing.of(login, listOf(newer, older))).isEqualTo(newer.pricing)
        assertThat(ApiPricing.of(login, listOf(older, newer))).isEqualTo(newer.pricing)
    }

    @Test
    fun `without a matching API profile, and for API profiles, the profile's own pricing`() {
        assertThat(ApiPricing.of(login, listOf(profile("claude-opus-5", "15", "75")))).isEqualTo(login.pricing)
        val api = profile("claude-sonnet-5", "3", "15")
        assertThat(ApiPricing.of(api, listOf(profile("claude-sonnet-6", "9", "90")))).isEqualTo(api.pricing)
        val unknown = profile("my-model", "0", "0", ModelRuntime.CLAUDE_CODE_LOGIN)
        assertThat(ApiPricing.of(unknown, listOf(profile("claude-sonnet-5", "3", "15")))).isEqualTo(unknown.pricing)
    }

    @Test
    fun `the harness estimate wins, the pricing is only resolved without one`() {
        var resolved = 0
        val pricing = { resolved++; Pricing(BigDecimal("3"), BigDecimal("15")) }
        assertThat(ApiPricing.costOf(BigDecimal("0.42"), TokenUsage(1_000_000, 0), pricing)).isEqualTo(Money.usd("0.42"))
        assertThat(resolved).isZero()
        assertThat(ApiPricing.costOf(null, TokenUsage(1_000_000, 0), pricing)).isEqualTo(Money.usd("3"))
        assertThat(resolved).isEqualTo(1)
    }
}
