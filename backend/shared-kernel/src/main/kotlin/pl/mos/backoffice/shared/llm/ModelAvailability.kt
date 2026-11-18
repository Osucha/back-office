package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.runner.AgentHarness
import java.util.UUID

/**
 * How a model is reached. `API`: through the LLM gateway and a provider connection. `CLAUDE_CODE_LOGIN` (ADR 0048):
 * Claude Code on the personal runner of the user who starts the work, signed in with that user's own subscription
 * (LOCAL_LOGIN); no API key, nothing billed. [ModelRef.modelId] is what Claude Code gets as `--model`.
 * `COPILOT_LOGIN` (ADR 0069): OpenCode on that personal runner with the user's own GitHub Copilot login (provider
 * [Provider.GITHUB_COPILOT], OpenCode's built-in `github-copilot` provider); the same rules, except that it never judges.
 *
 * [loginHarness] is the coding agent that holds the login (null for `API`): a login model runs only in that agent.
 */
enum class ModelRuntime(val loginHarness: AgentHarness?) {
    API(null),
    CLAUDE_CODE_LOGIN(AgentHarness.CLAUDE_CODE),
    COPILOT_LOGIN(AgentHarness.OPENCODE),
    ;

    /** ADR 0048/0069: runs on the user's personal runner with their own login, never through the gateway; nothing billed. */
    val ownLogin: Boolean get() = loginHarness != null

    /** ADR 0069: the provider a profile of this runtime must use; null = any gateway provider (`API`). */
    val requiredProvider: Provider?
        get() = when (this) {
            API -> null
            CLAUDE_CODE_LOGIN -> Provider.ANTHROPIC
            COPILOT_LOGIN -> Provider.GITHUB_COPILOT
        }

    /** ADR 0050/0069: may judge a benchmark (a Copilot login may not: the judge must not depend on a subscription's model list). */
    val mayJudge: Boolean get() = this != COPILOT_LOGIN
}

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
 * ADR 0050 step 3: makes sure a user whose personal runner reported their Claude login has the Claude Code login
 * model profiles (Opus, Sonnet, Haiku); existing ones are kept. Returns how many were created. ADR 0069: [ensureFor]
 * does the same for another login ([ModelRuntime.COPILOT_LOGIN]: a few GitHub Copilot models).
 */
fun interface PersonalLoginModels {
    fun ensure(userId: UUID): Int

    /** The login profiles of [runtime] (an own-login runtime); [ModelRuntime.CLAUDE_CODE_LOGIN] = [ensure]. */
    fun ensureFor(userId: UUID, runtime: ModelRuntime): Int = if (runtime == ModelRuntime.CLAUDE_CODE_LOGIN) ensure(userId) else 0
}

/** ADR 0050 step 2: a default model and whether it is the user's own default or the platform's. */
data class DefaultModel(val profileId: ModelProfileId, val personal: Boolean)

/**
 * ADR 0050 step 2: the default models used when neither a step, its template nor the project names one: the user's
 * personal default for the kind of step, then the platform default. Implemented by the Evaluation context.
 */
interface ModelDefaults {
    fun resolve(userId: UUID?, stepKind: StepKind): DefaultModel?
}

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
