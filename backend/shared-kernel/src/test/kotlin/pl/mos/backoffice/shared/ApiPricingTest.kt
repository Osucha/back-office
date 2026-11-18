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
    // ------------------------------------------------------------------ ADR 0069: GitHub Copilot login models

    private fun copilot(modelId: String, name: String = modelId) =
        profile(modelId, "0", "0", ModelRuntime.COPILOT_LOGIN, Provider.GITHUB_COPILOT, name = name)

    @Test
    fun `a Copilot model takes the price of the same model in the catalogue first`() {
        val sonnet = copilot("claude-sonnet-4.6")
        val catalogue = listOf(profile("claude-sonnet-4-6", "4", "20"), profile("claude-sonnet-5", "9", "99"))
        assertThat(ApiPricing.of(sonnet, catalogue).outputPerMTok).isEqualByComparingTo("20")
        val gpt = copilot("gpt-5.3-codex")
        assertThat(ApiPricing.of(gpt, listOf(profile("gpt-5-3-codex", "2", "16", provider = Provider.OPENAI))).outputPerMTok).isEqualByComparingTo("16")
    }

    @Test
    fun `else the vendor list price`() {
        val pricing = ApiPricing.of(copilot("gpt-5.3-codex"), emptyList())
        assertThat(pricing.inputPerMTok).isEqualByComparingTo("1.75")
        assertThat(pricing.outputPerMTok).isEqualByComparingTo("14")
        assertThat(pricing.cacheReadPerMTok).isEqualByComparingTo("0.175")
        assertThat(ApiPricing.of(copilot("claude-haiku-4.5"), emptyList()).cacheWritePerMTok).isEqualByComparingTo("1.25")
        assertThat(ApiPricing.of(copilot("gemini-3.8-flash"), emptyList()).outputPerMTok).isEqualByComparingTo("3.75")
        assertThat(ApiPricing.of(copilot("grok-4.7"), emptyList()).inputPerMTok).isEqualByComparingTo("2")
        assertThat(ApiPricing.listPrice("github-copilot/Claude-Sonnet-4.6")!!.outputPerMTok).isEqualByComparingTo("15")
    }

    @Test
    fun `an unknown Claude model falls back to its family, anything else to the profile's own pricing`() {
        val unknownClaude = copilot("claude-sonnet-9")
        assertThat(ApiPricing.of(unknownClaude, listOf(profile("claude-sonnet-5", "3", "15"))).outputPerMTok).isEqualByComparingTo("15")
        val unknown = copilot("mystery-model")
        assertThat(ApiPricing.of(unknown, listOf(profile("claude-sonnet-5", "3", "15")))).isEqualTo(unknown.pricing)
        assertThat(ApiPricing.listPrice("mystery-model")).isNull()
    }

    @Test
    fun `the runtimes say who holds the login and who may judge`() {
        assertThat(ModelRuntime.API.ownLogin).isFalse()
        assertThat(ModelRuntime.CLAUDE_CODE_LOGIN.loginHarness).isEqualTo(pl.mos.backoffice.shared.runner.AgentHarness.CLAUDE_CODE)
        assertThat(ModelRuntime.COPILOT_LOGIN.loginHarness).isEqualTo(pl.mos.backoffice.shared.runner.AgentHarness.OPENCODE)
        assertThat(ModelRuntime.COPILOT_LOGIN.requiredProvider).isEqualTo(Provider.GITHUB_COPILOT)
        assertThat(ModelRuntime.COPILOT_LOGIN.mayJudge).isFalse()
        assertThat(ModelRuntime.CLAUDE_CODE_LOGIN.mayJudge).isTrue()
        assertThat(Provider.GITHUB_COPILOT.viaGateway).isFalse()
        assertThat(Provider.ANTHROPIC.viaGateway).isTrue()
    }
}
