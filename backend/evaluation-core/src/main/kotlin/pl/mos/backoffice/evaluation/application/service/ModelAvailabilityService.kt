package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.ModelRoute
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget

/**
 * ADR 0050: which model profiles a use may take. A shared port, not a use case: the callers (project use cases, the
 * benchmark form) check the caller's permissions; this only reads the catalogue, the gateway's connection status
 * and the caller's personal runner pool.
 */
class ModelAvailabilityService(
    private val profiles: ModelProfileRepository,
    private val gateway: LlmGateway,
    /** Null = no runner wiring: login models are never usable. */
    private val runner: AgentRunner? = null,
) : ModelAvailability {

    override fun options(use: ModelUse): List<ModelOption> {
        val personalRunner = lazy { personalTarget(use) }
        return profiles.findAll(enabled = true)
            .map { evaluate(it, use, personalRunner) }
            .sortedWith(compareBy<ModelOption>({ !it.usable }, { it.personal }, { it.displayName.lowercase() }))
    }

    override fun check(profileId: ModelProfileId, use: ModelUse): ModelOption? =
        profiles.findById(profileId)?.let { evaluate(it, use, lazy { personalTarget(use) }) }

    private fun personalTarget(use: ModelUse): ExecutionTarget? =
        use.userId?.let { user -> runner?.executionTargets(user)?.firstOrNull { it.target == RunnerTarget.PERSONAL } }

    private fun evaluate(profile: ModelProfile, use: ModelUse, personalRunner: Lazy<ExecutionTarget?>): ModelOption {
        val verdict = if (!profile.enabled) {
            Verdict(problem = "the model profile is disabled")
        } else if (profile.runtime == ModelRuntime.CLAUDE_CODE_LOGIN) {
            loginRules(use) ?: runnerRules(personalRunner.value)
        } else {
            Verdict(problem = "the provider connection of this model is not set up or has no API key".takeUnless { gateway.isConfigured(profile.ref) })
        }
        return ModelOption(
            profileId = profile.id,
            displayName = profile.displayName,
            ref = profile.ref,
            runtime = profile.runtime,
            route = if (profile.runtime == ModelRuntime.CLAUDE_CODE_LOGIN) ModelRoute.OWN_LOGIN else ModelRoute.GATEWAY,
            personal = profile.runtime == ModelRuntime.CLAUDE_CODE_LOGIN,
            usable = verdict.problem == null,
            problem = verdict.problem,
            note = verdict.note,
        )
    }

    private data class Verdict(val problem: String? = null, val note: String? = null)

    companion object {
        /** The rules of a login model that do not depend on the runner; null = they allow it. */
        private fun loginRules(use: ModelUse): Verdict? = when {
            use.stepKind == StepKind.LLM_CALL ->
                Verdict(problem = "a Claude Code login model runs coding-agent steps only; LLM steps need a model of a connected provider")
            !use.privateProject ->
                Verdict(problem = "your own Claude login works only in your private projects and personal benchmarks")
            use.harness != null && use.harness != AgentHarness.CLAUDE_CODE ->
                Verdict(problem = "a Claude Code login model needs Claude Code as the coding agent of the step")
            else -> null
        }

        private fun runnerRules(target: ExecutionTarget?): Verdict = when {
            target == null || !target.exists ->
                Verdict(problem = "you have no personal runner; set one up under My account, Personal runner")
            !target.localLogin ->
                Verdict(problem = "your personal runner does not use your own Claude login; switch its LLM credentials under My account")
            target.onlineRunners == 0 ->
                Verdict(note = "your personal runner is offline: its jobs wait until it connects")
            else -> Verdict()
        }
    }
}
