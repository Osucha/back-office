package pl.mos.backoffice.evaluation.application.port.`in`

import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness

/**
 * ADR 0050 step 2: default models per kind of step. The platform defaults are set with `models.manage` and must be
 * platform profiles; a user's own defaults (`models.view`) may be platform profiles or their own personal ones. An
 * LLM step's default must be callable through the API (not a Claude Code login profile). Changes are audited
 * (`MODEL_DEFAULT_SET`).
 */
interface ManageModelDefaults {
    /** The platform defaults and the caller's own; a default whose profile is gone or disabled is left out. */
    fun get(): Defaults

    /** Sets or (with null) clears a platform default. */
    fun setPlatform(stepKind: StepKind, profileId: ModelProfileId?): Defaults

    /** Sets or (with null) clears the caller's own default. */
    fun setMine(stepKind: StepKind, profileId: ModelProfileId?): Defaults

    /**
     * The models that can be picked as a default for [stepKind] (`models.view`): for [platform] the platform profiles as a
     * shared project would use them, otherwise as the caller's own private work would (personal ones included).
     */
    fun options(stepKind: StepKind, harness: AgentHarness?, platform: Boolean): List<ModelOption>

    data class Defaults(val platform: Map<StepKind, ModelProfile>, val mine: Map<StepKind, ModelProfile>)
}
