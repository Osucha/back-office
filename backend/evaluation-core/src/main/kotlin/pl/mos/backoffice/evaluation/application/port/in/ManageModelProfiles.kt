package pl.mos.backoffice.evaluation.application.port.`in`

import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.Pricing

/** Use case: maintain the model catalogue and smoke-test a profile against its provider. */
interface ManageModelProfiles {
    fun create(command: ProfileCommand): ModelProfile
    fun update(id: ModelProfileId, command: ProfileCommand): ModelProfile
    fun get(id: ModelProfileId): ModelProfile
    fun list(enabled: Boolean? = null): List<ModelProfile>
    /** Fails with `InvalidStateException` when a benchmark references the profile. */
    fun delete(id: ModelProfileId)
    /** Sends a tiny prompt through the gateway and books a `MODEL_TEST` usage record. Never throws on provider errors. */
    fun test(id: ModelProfileId): ModelTestResult

    /**
     * ADR 0046: the same tiny call for every model of a benchmark before it starts (`evaluation.run`), so a model without
     * access or with its quota used up shows up before hundreds of attempts fail. Each id once, in the given order.
     */
    fun preflight(ids: List<ModelProfileId>): List<Pair<ModelProfile, ModelTestResult>>

    data class ProfileCommand(
        val displayName: String,
        val ref: ModelRef,
        val pricing: Pricing,
        val capabilities: Set<Capability> = emptySet(),
        val contextWindow: Int? = null,
        val notes: String? = null,
        val enabled: Boolean = true,
    )

    data class ModelTestResult(
        val ok: Boolean,
        val latencyMs: Long,
        val usage: TokenUsage,
        val cost: Money,
        val sample: String?,
        val error: String?,
    )
}

/** Use case: which providers exist and whether the server holds credentials for them. */
interface ListProviders {
    fun list(): List<ProviderStatus>

    data class ProviderStatus(val provider: Provider, val configured: Boolean, val baseUrl: String?)
}
