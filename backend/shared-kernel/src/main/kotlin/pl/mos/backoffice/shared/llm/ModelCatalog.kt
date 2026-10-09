package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.usage.Pricing

/**
 * Read-only view of a model profile as needed outside the Evaluation context (which owns the
 * catalogue). Projects use it to resolve the model of a pipeline step; the gateway uses it for pricing.
 */
data class ModelProfileSnapshot(
    val id: ModelProfileId,
    val displayName: String,
    val ref: ModelRef,
    val pricing: Pricing,
    val enabled: Boolean,
)

/** Outbound port implemented by the Evaluation context adapters. */
interface ModelCatalog {
    fun find(id: ModelProfileId): ModelProfileSnapshot?
    fun findAllEnabled(): List<ModelProfileSnapshot>

    /** Best-effort lookup of pricing for a raw model reference (used when a runner reports usage without cost). */
    fun findPricing(ref: ModelRef): Pricing?
}
