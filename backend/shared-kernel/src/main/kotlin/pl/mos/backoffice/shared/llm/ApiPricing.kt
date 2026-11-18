package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal

/**
 * ADR 0042/0065: the price of work at API list prices when its harness reported no estimate.
 *
 * A Claude Code login profile keeps a zero price (its price would otherwise turn into spent cost), so its tokens are
 * priced like the catalogue's enabled API profile of the same Claude family (`opus`, `sonnet`, `haiku` in the model id
 * or the display name). Several such profiles: the most expensive one (output price, then input price), then the
 * highest model id (newer versions sort later, e.g. `claude-opus-4-5` after `claude-opus-4-1`), so the choice is
 * deterministic and never underestimates. No such profile, or any other runtime: the profile's own pricing.
 *
 * ADR 0069: a GitHub Copilot login profile is priced like the underlying model at its vendor's API: an enabled API
 * profile of the catalogue with the same model id (`claude-sonnet-4.6` matches `claude-sonnet-4-6`), else the
 * vendor's published list price ([listPrice]), else (a Claude model) the Claude family rule above, else the profile's
 * own pricing when its owner entered one ([resolve]); a Copilot profile without any of them cannot be saved.
 */
object ApiPricing {

    private val FAMILIES = listOf("opus", "sonnet", "haiku")

    /** The Claude family of a model (`opus`, `sonnet`, `haiku`), from its id, else its display name; null when none. */
    fun familyOf(modelId: String, displayName: String? = null): String? =
        FAMILIES.firstOrNull { modelId.lowercase().contains(it) }
            ?: displayName?.let { name -> FAMILIES.firstOrNull { name.lowercase().contains(it) } }

    /** The pricing [profile]'s tokens are worth at API prices, looked up in [catalogue] for a login profile. */
    fun of(profile: ModelProfileSnapshot, catalogue: Collection<ModelProfileSnapshot>): Pricing = when (profile.runtime) {
        ModelRuntime.API -> profile.pricing
        ModelRuntime.CLAUDE_CODE_LOGIN -> claudeFamily(profile, catalogue) ?: profile.pricing
        ModelRuntime.COPILOT_LOGIN -> resolve(profile, catalogue) ?: profile.pricing
    }

    /**
     * ADR 0069: the API price of a GitHub Copilot login profile, or null when none is known: the same model in
     * [catalogue], the vendor list price, the Claude family (a Claude model), the profile's own non-zero pricing.
     */
    fun resolve(profile: ModelProfileSnapshot, catalogue: Collection<ModelProfileSnapshot>): Pricing? =
        sameModel(profile, catalogue)
            ?: listPrice(profile.ref.modelId)
            ?: claudeFamily(profile, catalogue)?.takeIf { profile.ref.modelId.lowercase().startsWith("claude") }
            ?: profile.pricing.takeIf { priced(it) }

    private fun priced(p: Pricing): Boolean = p.inputPerMTok.signum() > 0 || p.outputPerMTok.signum() > 0

    /** The harness's estimate when there is one, else [usage] priced by [pricing] (resolved only then). */
    fun costOf(reportedUsd: BigDecimal?, usage: TokenUsage, pricing: () -> Pricing): Money =
        reportedUsd?.let { Money(it).rounded() } ?: pricing().costOf(usage)

    /**
     * ADR 0069: the vendor's published API list price of a model a GitHub Copilot subscription offers, by its model id
     * (dots and dashes alike, case ignored); null when no price is known. Models priced by prompt length or with a
     * price change announced: the standard (short prompt) price in force on 2026-11-18.
     */
    fun listPrice(modelId: String): Pricing? = LIST_PRICES[normalize(modelId)]

    /** `claude-sonnet-4.6` and `claude-sonnet-4-6` alike: lower case, dots as dashes, no `provider/` prefix. */
    fun normalize(modelId: String): String = modelId.substringAfterLast('/').lowercase().replace('.', '-')

    private fun claudeFamily(profile: ModelProfileSnapshot, catalogue: Collection<ModelProfileSnapshot>): Pricing? {
        val family = familyOf(profile.ref.modelId, profile.displayName) ?: return null
        return mostExpensive(
            catalogue.filter { it.ref.provider == Provider.ANTHROPIC && familyOf(it.ref.modelId, it.displayName) == family },
        )
    }

    private fun sameModel(profile: ModelProfileSnapshot, catalogue: Collection<ModelProfileSnapshot>): Pricing? {
        val id = normalize(profile.ref.modelId)
        return mostExpensive(catalogue.filter { normalize(it.ref.modelId) == id })
    }

    private fun mostExpensive(candidates: Collection<ModelProfileSnapshot>): Pricing? = candidates
        .filter { it.enabled && it.runtime == ModelRuntime.API }
        .filter { priced(it.pricing) }
        .maxWithOrNull(
            compareBy<ModelProfileSnapshot> { it.pricing.outputPerMTok }
                .thenBy { it.pricing.inputPerMTok }
                .thenBy { it.ref.modelId }
                .thenBy { it.id.value.toString() },
        )
        ?.pricing

    private fun price(input: String, output: String, cacheRead: String, cacheWrite: String? = null) =
        Pricing(BigDecimal(input), BigDecimal(output), BigDecimal(cacheRead), cacheWrite?.let(::BigDecimal))

    /**
     * USD per million tokens (input, output, cache read, cache write; OpenAI, Google and xAI charge no cache write, so
     * it is priced as input), as published by the vendors on 2026-11-18:
     * - Anthropic: https://platform.claude.com/docs/en/about-claude/pricing (base input, output, cache hits, 5 minute
     *   cache writes; Claude Haiku 5.5 and Claude Sonnet 5 at the price for prompts up to their threshold)
     * - OpenAI: https://developers.openai.com/api/docs/pricing (Standard tier, short context up to 272K tokens)
     * - Google: https://aistudio.google.com/docs/pricing (Standard tier; Gemini 4 Argon and Carbon double from
     *   2026-12-01, Gemini 3.6 and 3.8 Flash from 2027-01-01: update then)
     * - xAI: https://docs.x.ai/docs/models (prompts under 200K tokens)
     */
    private val LIST_PRICES: Map<String, Pricing> = mapOf(
        // Anthropic
        "claude-fable-5-2" to price("8", "40", "0.20", "10"),
        "claude-fable-5-1" to price("10", "50", "0.25", "12.50"),
        "claude-fable-5" to price("10", "50", "1", "12.50"),
        "claude-opus-5-5" to price("4", "20", "0.20", "5"),
        "claude-opus-5" to price("5", "25", "0.50", "6.25"),
        "claude-opus-4-8" to price("5", "25", "0.50", "6.25"),
        "claude-opus-4-7" to price("5", "25", "0.50", "6.25"),
        "claude-opus-4-6" to price("5", "25", "0.50", "6.25"),
        "claude-opus-4-5" to price("5", "25", "0.50", "6.25"),
        "claude-sonnet-5-5" to price("2", "10", "0.10", "2.50"),
        "claude-sonnet-5" to price("2", "10", "0.20", "2.50"),
        "claude-sonnet-4-6" to price("3", "15", "0.30", "3.75"),
        "claude-sonnet-4-5" to price("3", "15", "0.30", "3.75"),
        "claude-haiku-5-5" to price("0.10", "0.50", "0.01", "0.125"),
        "claude-haiku-4-5" to price("1", "5", "0.10", "1.25"),
        // OpenAI
        "gpt-6-2-astra" to price("8", "40", "0.40"),
        "gpt-6-2-sol" to price("2", "10", "0.10"),
        "gpt-6-1-sol" to price("2", "10", "0.10"),
        "gpt-6-1-terra" to price("1", "5", "0.05"),
        "gpt-6-astra" to price("10", "50", "1"),
        "gpt-6-sol" to price("2", "10", "0.20"),
        "gpt-6-luna" to price("0.10", "0.50", "0.01"),
        "gpt-5-6-sol" to price("4", "20", "0.40"),
        "gpt-5-6-terra" to price("2", "12", "0.20"),
        "gpt-5-6-luna" to price("0.20", "1.20", "0.02"),
        "gpt-5-5" to price("5", "30", "0.50"),
        "gpt-5-4" to price("2.50", "15", "0.25"),
        "gpt-5-4-mini" to price("0.75", "4.50", "0.075"),
        "gpt-5-4-nano" to price("0.20", "1.25", "0.02"),
        "gpt-5-3-codex" to price("1.75", "14", "0.175"),
        "gpt-5-2" to price("1.75", "14", "0.175"),
        "gpt-5-1" to price("1.25", "10", "0.125"),
        "gpt-5" to price("1.25", "10", "0.125"),
        "gpt-5-mini" to price("0.25", "2", "0.025"),
        // Google
        "gemini-4-argon" to price("2", "10", "0.10"),
        "gemini-4-carbon" to price("2", "10", "0.10"),
        "gemini-4-hydrogen" to price("1.50", "7.50", "0.075"),
        "gemini-3-8-flash" to price("0.75", "3.75", "0.075"),
        "gemini-3-6-flash" to price("0.75", "3.75", "0.075"),
        // xAI
        "grok-4-7" to price("2", "6", "0.50"),
        "grok-4-6" to price("2", "6", "0.50"),
        "grok-4-5" to price("2", "6", "0.30"),
    )
}
