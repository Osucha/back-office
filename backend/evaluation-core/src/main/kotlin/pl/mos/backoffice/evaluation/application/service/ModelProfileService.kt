package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ListProviders
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles.ModelTestResult
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles.ProfileCommand
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmMessage
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.Pricing
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerTarget
import java.util.UUID
import pl.mos.backoffice.shared.ValidationException

/**
 * Model catalogue use cases. Deleting a profile that any benchmark refers to is refused so historical
 * leaderboards keep resolving; disable the profile instead. Reads need `models.view`; changes and the
 * smoke test need `models.manage` and are audited (`MODEL_PROFILE_CREATE/UPDATE/DELETE`, `MODEL_TEST`).
 */
class ModelProfileService(
    private val profiles: ModelProfileRepository,
    private val benchmarks: BenchmarkRepository,
    private val gateway: LlmGateway,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    /** ADR 0048: where Claude Code login models run; null = no runner wiring (they never pass the check). */
    private val runner: AgentRunner? = null,
) : ManageModelProfiles {

    override fun create(command: ProfileCommand): ModelProfile {
        val user = gate.require(EvaluationPermissions.MODELS_MANAGE)
        val saved = profiles.save(
            ModelProfile.create(
                displayName = command.displayName,
                ref = command.ref,
                pricing = command.pricing,
                capabilities = command.capabilities,
                contextWindow = command.contextWindow,
                notes = command.notes,
                enabled = command.enabled,
                now = clock.now(),
                actor = user.actor,
                runtime = command.runtime,
            ),
        )
        audit.record(entry("MODEL_PROFILE_CREATE", saved, snapshot(saved)))
        return saved
    }

    override fun update(id: ModelProfileId, command: ProfileCommand): ModelProfile {
        val user = gate.require(EvaluationPermissions.MODELS_MANAGE)
        val existing = load(id)
        val saved = profiles.save(
            existing.update(
                displayName = command.displayName,
                ref = command.ref,
                pricing = command.pricing,
                capabilities = command.capabilities,
                contextWindow = command.contextWindow,
                notes = command.notes,
                enabled = command.enabled,
                now = clock.now(),
                actor = user.actor,
                runtime = command.runtime,
            ),
        )
        val changes = diff(existing, saved)
        if (changes.isNotEmpty()) audit.record(entry("MODEL_PROFILE_UPDATE", saved, changes))
        return saved
    }

    override fun get(id: ModelProfileId): ModelProfile {
        gate.require(EvaluationPermissions.MODELS_VIEW)
        return load(id)
    }

    override fun list(enabled: Boolean?): List<ModelProfile> {
        gate.require(EvaluationPermissions.MODELS_VIEW)
        return profiles.findAll(enabled).sortedBy { it.displayName.lowercase() }
    }

    override fun delete(id: ModelProfileId) {
        gate.require(EvaluationPermissions.MODELS_MANAGE)
        val existing = load(id)
        if (benchmarks.existsReferencingModel(id)) {
            throw InvalidStateException("model profile $id is referenced by a benchmark; disable it instead of deleting")
        }
        profiles.delete(id)
        audit.record(entry("MODEL_PROFILE_DELETE", existing, mapOf("displayName" to existing.displayName, "model" to existing.ref.label)))
    }

    override fun test(id: ModelProfileId): ModelTestResult {
        val user = gate.require(EvaluationPermissions.MODELS_MANAGE)
        return probe(load(id), user.userId?.value)
    }

    override fun preflight(ids: List<ModelProfileId>): List<Pair<ModelProfile, ModelTestResult>> {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        if (ids.size > MAX_PREFLIGHT) throw ValidationException("at most $MAX_PREFLIGHT models", "modelProfileIds")
        return ids.distinct().map { id -> load(id).let { it to probe(it, user.userId?.value) } }
    }

    private fun probe(profile: ModelProfile, userId: UUID?): ModelTestResult {
        val result = if (profile.viaClaudeCodeLogin) loginCheck(userId) else callModel(profile)
        audit.record(
            entry("MODEL_TEST", profile, mapOf("model" to profile.ref.label, "ok" to result.ok, "latencyMs" to result.latencyMs, "error" to result.error))
                .copy(result = if (result.ok) AuditResult.SUCCESS else AuditResult.FAILURE),
        )
        return result
    }

    /** ADR 0048: a Claude Code login model needs the caller's personal runner with their own login, online. */
    private fun loginCheck(userId: UUID?): ModelTestResult {
        val personal = runner?.executionTargets(userId)?.firstOrNull { it.target == RunnerTarget.PERSONAL }
        val error = when {
            personal == null || !personal.exists -> "you have no personal runner; set one up under My account, Claude Code login models run there"
            !personal.localLogin -> "your personal runner does not use your own Claude Code login; switch its LLM credentials to Claude Code login under My account"
            personal.onlineRunners == 0 -> "your personal runner is offline; start it before the benchmark"
            else -> null
        }
        return ModelTestResult(
            ok = error == null,
            latencyMs = 0,
            usage = TokenUsage.ZERO,
            cost = Money.ZERO,
            sample = if (error == null) "Claude Code with your own login on your personal runner; the model answers in the benchmark" else null,
            error = error,
        )
    }

    private fun callModel(profile: ModelProfile): ModelTestResult {
        val request = LlmRequest(
            messages = listOf(LlmMessage.user(TEST_PROMPT)),
            usageContext = UsageContext(kind = UsageKind.MODEL_TEST),
            optionsOverride = profile.ref.options.copy(maxOutputTokens = TEST_MAX_TOKENS),
        )
        val started = clock.now()
        return try {
            val result = gateway.chat(profile.ref, request)
            ModelTestResult(
                ok = true,
                latencyMs = result.latency.toMillis(),
                usage = result.usage,
                cost = profile.pricing.costOf(result.usage),
                sample = result.content.take(500),
                error = null,
            )
        } catch (e: RuntimeException) {
            ModelTestResult(
                ok = false,
                latencyMs = clock.now().toEpochMilli() - started.toEpochMilli(),
                usage = TokenUsage.ZERO,
                cost = Money.ZERO,
                sample = null,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun load(id: ModelProfileId): ModelProfile = profiles.findById(id) ?: throw NotFoundException("ModelProfile", id)

    private fun entry(operation: String, p: ModelProfile, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "ModelProfile", entityId = p.id.toString(), details = details)

    companion object {
        const val TEST_PROMPT = "Reply with the single word OK."
        const val TEST_MAX_TOKENS = 64
        const val MAX_PREFLIGHT = 20

        fun snapshot(p: ModelProfile): Map<String, Any?> = mapOf(
            "displayName" to p.displayName,
            "provider" to p.ref.provider.name,
            "connection" to p.ref.connection,
            "modelId" to p.ref.modelId,
            "runtime" to p.runtime.name,
            "pricing" to pricing(p.pricing),
            "enabled" to p.enabled,
        )

        /** `{field: {old, new}}` including every pricing component, so price changes are traceable. */
        fun diff(old: ModelProfile, new: ModelProfile): Map<String, Any?> = AuditableChanges.changed(
            "displayName" to (old.displayName to new.displayName),
            "provider" to (old.ref.provider.name to new.ref.provider.name),
            "connection" to (old.ref.connection to new.ref.connection),
            "modelId" to (old.ref.modelId to new.ref.modelId),
            "runtime" to (old.runtime.name to new.runtime.name),
            "options" to (old.ref.options.toString() to new.ref.options.toString()),
            "pricing.inputPerMTok" to (old.pricing.inputPerMTok.toPlainString() to new.pricing.inputPerMTok.toPlainString()),
            "pricing.outputPerMTok" to (old.pricing.outputPerMTok.toPlainString() to new.pricing.outputPerMTok.toPlainString()),
            "pricing.cacheReadPerMTok" to (old.pricing.cacheReadPerMTok?.toPlainString() to new.pricing.cacheReadPerMTok?.toPlainString()),
            "pricing.cacheWritePerMTok" to (old.pricing.cacheWritePerMTok?.toPlainString() to new.pricing.cacheWritePerMTok?.toPlainString()),
            "pricing.currency" to (old.pricing.currency to new.pricing.currency),
            "capabilities" to (old.capabilities.map { it.name }.sorted() to new.capabilities.map { it.name }.sorted()),
            "contextWindow" to (old.contextWindow to new.contextWindow),
            "notes" to (old.notes to new.notes),
            "enabled" to (old.enabled to new.enabled),
        )

        private fun pricing(p: Pricing): Map<String, Any?> = mapOf(
            "inputPerMTok" to p.inputPerMTok.toPlainString(),
            "outputPerMTok" to p.outputPerMTok.toPlainString(),
            "cacheReadPerMTok" to p.cacheReadPerMTok?.toPlainString(),
            "cacheWritePerMTok" to p.cacheWritePerMTok?.toPlainString(),
            "currency" to p.currency,
        )
    }
}

/** Provider list with server-side credential status; base URLs shown for self-hosted / compatible endpoints. Needs `models.view`. */
class ProviderService(
    private val gateway: LlmGateway,
    private val baseUrls: Map<Provider, String?>,
    private val gate: AuthorizationGate,
) : ListProviders {
    override fun list(): List<ListProviders.ProviderStatus> {
        gate.require(EvaluationPermissions.MODELS_VIEW)
        return Provider.entries.map { provider ->
            ListProviders.ProviderStatus(
                provider = provider,
                configured = gateway.isConfigured(provider),
                baseUrl = baseUrls[provider]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
