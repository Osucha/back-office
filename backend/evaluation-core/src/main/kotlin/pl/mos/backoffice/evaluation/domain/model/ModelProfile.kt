package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.usage.Pricing
import java.time.Instant

/** Informational tags on a catalogue entry; they do not change how a model is called. */
enum class Capability { CODING, REASONING, LONG_CONTEXT, TOOLS, VISION, CHEAP }


/**
 * Aggregate root: one entry of the model catalogue. Holds how to call a model (`ref`), what it costs
 * (`pricing`, user-maintained) and descriptive metadata. Pipelines and benchmarks refer to profiles by id.
 */
data class ModelProfile(
    val id: ModelProfileId,
    val displayName: String,
    val ref: ModelRef,
    val pricing: Pricing,
    val capabilities: Set<Capability> = emptySet(),
    val contextWindow: Int? = null,
    val notes: String? = null,
    val enabled: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (ADR 0017); null for rows written before actor stamps existed. */
    val updatedBy: Actor? = null,
    val runtime: ModelRuntime = ModelRuntime.API,
) {
    init {
        validate(displayName, pricing, contextWindow)
        if (runtime == ModelRuntime.CLAUDE_CODE_LOGIN && ref.provider != Provider.ANTHROPIC) {
            throw ValidationException("a Claude Code login model must use the ANTHROPIC provider", "ref.provider")
        }
    }

    /** ADR 0048: runs only as Claude Code on a personal runner with its owner's login, never through the gateway. */
    val viaClaudeCodeLogin: Boolean get() = runtime == ModelRuntime.CLAUDE_CODE_LOGIN

    fun update(
        displayName: String,
        ref: ModelRef,
        pricing: Pricing,
        capabilities: Set<Capability>,
        contextWindow: Int?,
        notes: String?,
        enabled: Boolean,
        now: Instant,
        actor: Actor? = null,
        runtime: ModelRuntime = this.runtime,
    ): ModelProfile = copy(
        displayName = displayName,
        ref = ref,
        pricing = pricing,
        capabilities = capabilities,
        contextWindow = contextWindow,
        notes = notes,
        enabled = enabled,
        updatedAt = now,
        updatedBy = actor,
        runtime = runtime,
    )

    /** Read-only view handed to other contexts through the `ModelCatalog` port. */
    fun toSnapshot(): ModelProfileSnapshot = ModelProfileSnapshot(
        id = id,
        displayName = displayName,
        ref = ref,
        pricing = pricing,
        enabled = enabled,
        runtime = runtime,
    )

    companion object {
        fun create(
            displayName: String,
            ref: ModelRef,
            pricing: Pricing,
            capabilities: Set<Capability> = emptySet(),
            contextWindow: Int? = null,
            notes: String? = null,
            enabled: Boolean = true,
            now: Instant,
            id: ModelProfileId = ModelProfileId.new(),
            actor: Actor? = null,
            runtime: ModelRuntime = ModelRuntime.API,
        ): ModelProfile = ModelProfile(
            id = id,
            displayName = displayName,
            ref = ref,
            pricing = pricing,
            capabilities = capabilities,
            contextWindow = contextWindow,
            notes = notes,
            enabled = enabled,
            createdAt = now,
            updatedAt = now,
            updatedBy = actor,
            runtime = runtime,
        )

        private const val MUST_NOT_BE_NEGATIVE = "must not be negative"

        private fun validate(displayName: String, pricing: Pricing, contextWindow: Int?) {
            val errors = mutableListOf<ValidationException.FieldError>()
            if (displayName.isBlank()) errors += ValidationException.FieldError("displayName", "must not be blank")
            if (pricing.inputPerMTok.signum() < 0) errors += ValidationException.FieldError("pricing.inputPerMTok", MUST_NOT_BE_NEGATIVE)
            if (pricing.outputPerMTok.signum() < 0) errors += ValidationException.FieldError("pricing.outputPerMTok", MUST_NOT_BE_NEGATIVE)
            if (pricing.cacheReadPerMTok?.let { it.signum() < 0 } == true) errors += ValidationException.FieldError("pricing.cacheReadPerMTok", MUST_NOT_BE_NEGATIVE)
            if (pricing.cacheWritePerMTok?.let { it.signum() < 0 } == true) errors += ValidationException.FieldError("pricing.cacheWritePerMTok", MUST_NOT_BE_NEGATIVE)
            if (contextWindow != null && contextWindow <= 0) errors += ValidationException.FieldError("contextWindow", "must be positive")
            if (errors.isNotEmpty()) throw ValidationException(errors)
        }
    }
}
