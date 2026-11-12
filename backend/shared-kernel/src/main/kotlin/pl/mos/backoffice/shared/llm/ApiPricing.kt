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
 */
object ApiPricing {

    private val FAMILIES = listOf("opus", "sonnet", "haiku")

    /** The Claude family of a model (`opus`, `sonnet`, `haiku`), from its id, else its display name; null when none. */
    fun familyOf(modelId: String, displayName: String? = null): String? =
        FAMILIES.firstOrNull { modelId.lowercase().contains(it) }
            ?: displayName?.let { name -> FAMILIES.firstOrNull { name.lowercase().contains(it) } }

    /** The pricing [profile]'s tokens are worth at API prices, looked up in [catalogue] for a login profile. */
    fun of(profile: ModelProfileSnapshot, catalogue: Collection<ModelProfileSnapshot>): Pricing {
        if (profile.runtime != ModelRuntime.CLAUDE_CODE_LOGIN) return profile.pricing
        val family = familyOf(profile.ref.modelId, profile.displayName) ?: return profile.pricing
        return catalogue
            .filter { it.enabled && it.runtime == ModelRuntime.API && it.ref.provider == Provider.ANTHROPIC }
            .filter { it.pricing.inputPerMTok.signum() > 0 || it.pricing.outputPerMTok.signum() > 0 }
            .filter { familyOf(it.ref.modelId, it.displayName) == family }
            .maxWithOrNull(
                compareBy<ModelProfileSnapshot> { it.pricing.outputPerMTok }
                    .thenBy { it.pricing.inputPerMTok }
                    .thenBy { it.ref.modelId }
                    .thenBy { it.id.value.toString() },
            )
            ?.pricing ?: profile.pricing
    }

    /** The harness's estimate when there is one, else [usage] priced by [pricing] (resolved only then). */
    fun costOf(reportedUsd: BigDecimal?, usage: TokenUsage, pricing: () -> Pricing): Money =
        reportedUsd?.let { Money(it).rounded() } ?: pricing().costOf(usage)
}
