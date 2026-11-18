package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.PersonalLoginModels
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.usage.Pricing
import java.util.UUID

/**
 * ADR 0050 step 3: when a user's personal runner reports their Claude login, they get one personal Claude Code login
 * profile per model alias Claude Code accepts (Opus, Sonnet, Haiku), unless they already have one for that alias. ADR
 * 0069: a reported GitHub Copilot login gives a few Copilot login profiles the same way. A
 * deleted profile comes back only when the login is reported again after it was gone. Called by the runner fleet as
 * system work, never through a controller.
 */
class PersonalLoginModelsService(
    private val profiles: ModelProfileRepository,
    private val clock: Clock,
    private val audit: AuditPort,
) : PersonalLoginModels {

    override fun ensure(userId: UUID): Int = ensureFor(userId, ModelRuntime.CLAUDE_CODE_LOGIN)

    /**
     * ADR 0069: [ModelRuntime.COPILOT_LOGIN] creates a few GitHub Copilot model profiles ([COPILOT_MODELS]); others are
     * added by the user (any model id their Copilot offers).
     */
    override fun ensureFor(userId: UUID, runtime: ModelRuntime): Int {
        val models = when (runtime) {
            ModelRuntime.CLAUDE_CODE_LOGIN -> ALIASES
            ModelRuntime.COPILOT_LOGIN -> COPILOT_MODELS
            ModelRuntime.API -> return 0
        }
        val provider = runtime.requiredProvider ?: return 0
        val existing = profiles.findAll()
            .filter { it.ownerUserId == userId && it.runtime == runtime }
            .map { it.ref.modelId.lowercase() }
            .toSet()
        val now = clock.now()
        val note = if (runtime == ModelRuntime.COPILOT_LOGIN) COPILOT_NOTE else NOTE
        val created = models.filterKeys { it !in existing }.map { (modelId, name) ->
            profiles.save(
                ModelProfile.create(
                    displayName = name,
                    ref = ModelRef(provider, modelId),
                    pricing = Pricing.FREE,
                    capabilities = setOf(Capability.CODING),
                    notes = note,
                    now = now,
                    actor = Actor.SYSTEM,
                    runtime = runtime,
                    ownerUserId = userId,
                ),
            )
        }
        val reason = if (runtime == ModelRuntime.COPILOT_LOGIN) "personal runner reported a GitHub Copilot login" else "personal runner reported a Claude login"
        created.forEach {
            audit.record(
                AuditEntry(
                    operation = "MODEL_PROFILE_CREATE", entityType = "ModelProfile", entityId = it.id.toString(),
                    details = mapOf("displayName" to it.displayName, "model" to it.ref.label, "runtime" to it.runtime.name, "personal" to true, "reason" to reason),
                ),
            )
        }
        return created.size
    }

    companion object {
        /** The model aliases Claude Code accepts as `--model`, with the profile name a user sees. */
        val ALIASES = linkedMapOf(
            "opus" to "Claude Opus (my login)",
            "sonnet" to "Claude Sonnet (my login)",
            "haiku" to "Claude Haiku (my login)",
        )
        const val NOTE = "Created when your personal runner reported your Claude login (ADR 0050): Claude Code with your own " +
            "subscription, for coding steps of your private projects and your benchmarks. Nothing is billed."

        /**
         * ADR 0069: GitHub Copilot models created when a personal runner first reports a Copilot login: one coding model
         * per vendor Copilot offers (ids of OpenCode's `github-copilot` provider and of GitHub's list of supported models).
         */
        val COPILOT_MODELS = linkedMapOf(
            "claude-sonnet-4.6" to "Claude Sonnet 4.6 (Copilot)",
            "gpt-5.3-codex" to "GPT-5.3-Codex (Copilot)",
            "gemini-3.8-flash" to "Gemini 3.8 Flash (Copilot)",
            "grok-4.7" to "Grok 4.7 (Copilot)",
        )
        const val COPILOT_NOTE = "Created when your personal runner reported your GitHub Copilot login (ADR 0069): OpenCode with your " +
            "own Copilot subscription, for your private projects and your benchmarks (never the judge). Nothing is billed; the " +
            "model must be enabled in your Copilot settings."

        /** ADR 0069: the problem of a Copilot login model while the personal runner is online without the login. */
        const val COPILOT_LOGIN_MISSING = "your personal runner is online but has no GitHub Copilot login; sign in with OpenCode " +
            "(opencode auth login, GitHub Copilot) and start the runner with your OpenCode auth file mounted (My account, Personal runner)"
    }
}
