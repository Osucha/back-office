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
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.usage.Pricing
import java.util.UUID

/**
 * ADR 0050 step 3: when a user's personal runner reports their Claude login, they get one personal Claude Code login
 * profile per model alias Claude Code accepts (Opus, Sonnet, Haiku), unless they already have one for that alias. A
 * deleted profile comes back only when the login is reported again after it was gone. Called by the runner fleet as
 * system work, never through a controller.
 */
class PersonalLoginModelsService(
    private val profiles: ModelProfileRepository,
    private val clock: Clock,
    private val audit: AuditPort,
) : PersonalLoginModels {

    override fun ensure(userId: UUID): Int {
        val existing = profiles.findAll()
            .filter { it.ownerUserId == userId && it.runtime == ModelRuntime.CLAUDE_CODE_LOGIN }
            .map { it.ref.modelId.lowercase() }
            .toSet()
        val now = clock.now()
        val created = ALIASES.filterKeys { it !in existing }.map { (alias, name) ->
            profiles.save(
                ModelProfile.create(
                    displayName = name,
                    ref = ModelRef(Provider.ANTHROPIC, alias),
                    pricing = Pricing.FREE,
                    capabilities = setOf(Capability.CODING),
                    notes = NOTE,
                    now = now,
                    actor = Actor.SYSTEM,
                    runtime = ModelRuntime.CLAUDE_CODE_LOGIN,
                    ownerUserId = userId,
                ),
            )
        }
        created.forEach {
            audit.record(
                AuditEntry(
                    operation = "MODEL_PROFILE_CREATE", entityType = "ModelProfile", entityId = it.id.toString(),
                    details = mapOf("displayName" to it.displayName, "model" to it.ref.label, "runtime" to it.runtime.name, "personal" to true, "reason" to "personal runner reported a Claude login"),
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
    }
}
