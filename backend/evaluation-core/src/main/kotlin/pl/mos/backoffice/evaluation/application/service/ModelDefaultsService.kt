package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelDefaults
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelDefaults.Defaults
import pl.mos.backoffice.evaluation.application.port.out.ModelDefaultRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.domain.model.ModelDefault
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.DefaultModel
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.util.UUID

/**
 * ADR 0050 step 2: the platform defaults and every user's own default model per kind of step ([ManageModelDefaults]),
 * and the shared [ModelDefaults] port the Projects context resolves a step's model with after the project defaults:
 * the starting user's own default first, then the platform's. A default whose profile was deleted or disabled is
 * ignored.
 */
class ModelDefaultsService(
    private val defaults: ModelDefaultRepository,
    private val profiles: ModelProfileRepository,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    /** Null = no availability wiring: [options] is empty. */
    private val availability: ModelAvailability? = null,
) : ManageModelDefaults, ModelDefaults {

    override fun get(): Defaults {
        val user = gate.require(EvaluationPermissions.MODELS_VIEW)
        return defaultsOf(user.userId?.value)
    }

    override fun setPlatform(stepKind: StepKind, profileId: ModelProfileId?): Defaults {
        val user = gate.require(EvaluationPermissions.MODELS_MANAGE)
        val profile = profileId?.let { checked(it, stepKind, user.userId?.value) }
        if (profile?.personal == true) throw ValidationException("a platform default must be a platform model profile, not a personal one", "profileId")
        store(null, stepKind, profile, user.actor)
        return defaultsOf(user.userId?.value)
    }

    override fun setMine(stepKind: StepKind, profileId: ModelProfileId?): Defaults {
        val user = gate.require(EvaluationPermissions.MODELS_VIEW)
        val owner = user.userId?.value ?: throw ValidationException("personal defaults need a signed-in user", "profileId")
        store(owner, stepKind, profileId?.let { checked(it, stepKind, owner) }, user.actor)
        return defaultsOf(owner)
    }

    override fun options(stepKind: StepKind, harness: AgentHarness?, platform: Boolean): List<ModelOption> {
        val user = gate.require(EvaluationPermissions.MODELS_VIEW)
        val options = availability?.options(ModelUse(stepKind, harness, privateProject = !platform, userId = user.userId?.value)).orEmpty()
        return if (platform) options.filterNot { it.personal } else options
    }

    override fun resolve(userId: UUID?, stepKind: StepKind): DefaultModel? {
        userId?.let { usable(it, stepKind) }?.let { return DefaultModel(it.id, personal = true) }
        return usable(null, stepKind)?.let { DefaultModel(it.id, personal = false) }
    }

    private fun usable(owner: UUID?, stepKind: StepKind): ModelProfile? =
        defaults.find(owner, stepKind)?.let { profiles.findById(it.profileId) }?.takeIf { it.enabled && it.visibleTo(owner) }

    private fun defaultsOf(userId: UUID?): Defaults {
        fun of(owner: UUID?) = defaults.findFor(owner).mapNotNull { d ->
            profiles.findById(d.profileId)?.takeIf { it.enabled && it.visibleTo(userId) }?.let { d.stepKind to it }
        }.toMap()
        return Defaults(platform = of(null), mine = userId?.let { of(it) }.orEmpty())
    }

    /** The profile exists, is visible to [owner] (null = only platform profiles), enabled and fits the kind of step. */
    private fun checked(id: ModelProfileId, stepKind: StepKind, owner: UUID?): ModelProfile {
        val profile = profiles.findById(id)?.takeIf { it.visibleTo(owner) } ?: throw NotFoundException("ModelProfile", id)
        if (!profile.enabled) throw ValidationException("the model profile ${profile.displayName} is disabled", "profileId")
        if (stepKind == StepKind.LLM_CALL && profile.runtime == ModelRuntime.CLAUDE_CODE_LOGIN) {
            throw ValidationException("${profile.displayName} is a Claude Code login model; the default for LLM steps must be callable through the API", "profileId")
        }
        return profile
    }

    private fun store(owner: UUID?, stepKind: StepKind, profile: ModelProfile?, actor: pl.mos.backoffice.shared.security.Actor) {
        if (profile == null) defaults.delete(owner, stepKind) else defaults.save(ModelDefault(owner, stepKind, profile.id, clock.now(), actor))
        audit.record(
            AuditEntry(
                operation = "MODEL_DEFAULT_SET", entityType = "ModelDefault", entityId = "${owner ?: "platform"}:${stepKind.name}",
                details = mapOf("scope" to if (owner == null) "platform" else "personal", "stepKind" to stepKind.name, "model" to profile?.displayName),
            ),
        )
    }
}
