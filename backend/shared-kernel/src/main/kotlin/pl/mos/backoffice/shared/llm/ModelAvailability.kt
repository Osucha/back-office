package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.runner.AgentHarness
import java.util.UUID

/**
 * How a model is reached. `API`: through the LLM gateway and a provider connection. `CLAUDE_CODE_LOGIN` (ADR 0048):
 * Claude Code on the personal runner of the user who starts the work, signed in with that user's own subscription
 * (LOCAL_LOGIN); no API key, nothing billed. [ModelRef.modelId] is what Claude Code gets as `--model`.
 */
enum class ModelRuntime { API, CLAUDE_CODE_LOGIN }

/** ADR 0050: what a step does with its model. `LLM_CALL`: one gateway call (LLM tasks, judges); `CODING_AGENT`: a runner job. */
enum class StepKind { LLM_CALL, CODING_AGENT }

/** ADR 0050: the way a usable model is called. */
enum class ModelRoute { GATEWAY, OWN_LOGIN }

/**
 * ADR 0050: where a model is to be used. [harness] is the coding agent of a `CODING_AGENT` step (null = not known
 * yet); [privateProject] whether the work belongs to a private project (ADR 0038); [userId] who runs it.
 */
data class ModelUse(
    val stepKind: StepKind,
    val harness: AgentHarness? = null,
    val privateProject: Boolean = false,
    val userId: UUID? = null,
)

/**
 * ADR 0050: one model for one use. [usable] false = a step with this model cannot run (the [problem] says why);
 * [note] is a warning that does not stop it (for example a personal runner that is offline: its jobs wait).
 */
data class ModelOption(
    val profileId: ModelProfileId,
    val displayName: String,
    val ref: ModelRef,
    val runtime: ModelRuntime,
    val route: ModelRoute,
    val personal: Boolean,
    val usable: Boolean,
    val problem: String? = null,
    val note: String? = null,
)

/**
 * ADR 0050: which models a user may use for a kind of step, by which route, and why not. Implemented by the
 * Evaluation context (owner of the model catalogue) over [LlmGateway] and the runner port.
 */
interface ModelAvailability {
    /** Every enabled model for [use], usable ones first. */
    fun options(use: ModelUse): List<ModelOption>

    /** One model for [use]; null when the profile does not exist. */
    fun check(profileId: ModelProfileId, use: ModelUse): ModelOption?
}
