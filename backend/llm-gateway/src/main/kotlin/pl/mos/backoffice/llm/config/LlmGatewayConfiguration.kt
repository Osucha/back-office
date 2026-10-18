package pl.mos.backoffice.llm.config

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import pl.mos.backoffice.llm.budget.application.port.`in`.EnforceBudgets
import pl.mos.backoffice.llm.budget.domain.BudgetExceededException
import pl.mos.backoffice.llm.gateway.ChatModelFactory
import pl.mos.backoffice.llm.gateway.ChatOptionsMapper
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.llm.gateway.SpringAiChatModelFactory
import pl.mos.backoffice.llm.gateway.SpringAiLlmGateway
import pl.mos.backoffice.llm.gateway.StructuredOutputParser
import pl.mos.backoffice.llm.ratelimit.ProviderRateLimiter
import pl.mos.backoffice.llm.ratelimit.RateBuckets
import java.math.BigDecimal
import javax.sql.DataSource
import pl.mos.backoffice.llm.usage.LlmPermissions
import pl.mos.backoffice.llm.usage.UsageQueryService
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageLedger
import tools.jackson.databind.json.JsonMapper
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.llm.connections.CachedProviderConnections
import pl.mos.backoffice.llm.connections.JdbcLlmProviderConnectionStore
import pl.mos.backoffice.llm.connections.LlmProviderConnectionStore
import pl.mos.backoffice.llm.connections.LlmProviderService
import pl.mos.backoffice.llm.connections.ProviderModelLister
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.SecretVault

/**
 * Wires the gateway. Provider credentials are read from the standard Spring AI properties
 * (`spring.ai.anthropic.api-key`, `spring.ai.openai.api-key`, `spring.ai.google.genai.api-key`,
 * `spring.ai.mistralai.api-key`, `spring.ai.ollama.base-url`) and `backoffice.llm.openai-compatible.*`.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties::class)
class LlmGatewayConfiguration {

    /** ADR 0045: provider connections added in Back Office (API keys sealed with the installation's secret key). */
    @Bean
    fun llmProviderConnectionStore(jdbc: JdbcClient): LlmProviderConnectionStore = JdbcLlmProviderConnectionStore(jdbc)

    @Bean
    fun cachedProviderConnections(store: LlmProviderConnectionStore, vault: ObjectProvider<SecretVault>) =
        CachedProviderConnections(store, vault.getIfAvailable())

    @Bean
    fun providerModelLister(json: JsonMapper) = ProviderModelLister(json)

    @Bean
    fun llmProviderService(
        store: LlmProviderConnectionStore, vault: ObjectProvider<SecretVault>, gate: AuthorizationGate, audit: ObjectProvider<AuditPort>, clock: Clock,
        cache: CachedProviderConnections, registry: ProviderRegistry, lister: ProviderModelLister,
    ) = LlmProviderService(store, vault.getIfAvailable(), gate, audit.getIfAvailable { AuditPort.NOOP }, clock, cache, registry, lister)

    /**
     * Provider endpoints (ADR 0045): connections added in Back Office first, the environment variables of each type as a
     * fallback (the configuration before 0045, still used by Helm values).
     */
    @Bean
    fun providerRegistry(environment: Environment, properties: LlmProperties, connections: CachedProviderConnections): ProviderRegistry = ProviderRegistry(
        mapOf(
            Provider.ANTHROPIC to ProviderSettings(apiKey = environment.getProperty("spring.ai.anthropic.api-key"), baseUrl = environment.getProperty("spring.ai.anthropic.base-url")),
            Provider.OPENAI to ProviderSettings(apiKey = environment.getProperty("spring.ai.openai.api-key"), baseUrl = environment.getProperty("spring.ai.openai.base-url")),
            Provider.GOOGLE to ProviderSettings(apiKey = environment.getProperty("spring.ai.google.genai.api-key")),
            Provider.MISTRAL to ProviderSettings(apiKey = environment.getProperty("spring.ai.mistralai.api-key"), baseUrl = environment.getProperty("spring.ai.mistralai.base-url")),
            // Only an explicit OLLAMA_BASE_URL counts as configured: spring.ai.ollama.base-url always has the
            // localhost default, which would show Ollama as available on every server.
            Provider.OLLAMA to ProviderSettings(baseUrl = environment.getProperty("OLLAMA_BASE_URL")),
            Provider.OPENAI_COMPATIBLE to ProviderSettings(apiKey = properties.openaiCompatible.apiKey, baseUrl = properties.openaiCompatible.baseUrl),
        ),
        connections,
    )

    @Bean
    fun chatModelFactory(properties: LlmProperties, observationRegistry: ObjectProvider<ObservationRegistry>): ChatModelFactory =
        SpringAiChatModelFactory(observationRegistry.getIfAvailable { ObservationRegistry.NOOP }, properties.defaultTimeout)

    /**
     * Requests/tokens per minute per provider from `backoffice.llm.rate-limits.*` (ADR 0020), shared by every
     * replica through PostgreSQL buckets (`llm_rate_bucket`, ADR 0023); `backoffice.llm.rate-limit-store=local`
     * keeps them per process.
     */
    @Bean
    fun providerRateLimiter(properties: LlmProperties, clock: Clock, dataSource: DataSource, environment: org.springframework.core.env.Environment): ProviderRateLimiter {
        val local = environment.getProperty("backoffice.llm.rate-limit-store", "postgres").equals("local", ignoreCase = true)
        val buckets = if (local) RateBuckets.local(clock) else RateBuckets.postgres(dataSource, clock)
        return ProviderRateLimiter(properties.providerLimits(), clock, buckets) { wait -> Thread.sleep(wait.toMillis().coerceAtLeast(1)) }
    }

    /** Gate-checked read side of the ledger behind `/api/v1/usage` endpoints. */
    @Bean
    fun usageQueryService(ledger: UsageLedger, gate: AuthorizationGate): UsageQueryService = UsageQueryService(ledger, gate)

    /** The llm module permissions for the identity context's catalogue (ADR 0017). */
    @Bean
    fun llmPermissionCatalog(): PermissionCatalogContribution = PermissionCatalogContribution { LlmPermissions.definitions }

    @Bean
    fun llmGateway(
        registry: ProviderRegistry,
        factory: ChatModelFactory,
        properties: LlmProperties,
        catalog: ModelCatalog,
        ledger: UsageLedger,
        clock: Clock,
        jsonMapper: JsonMapper,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        rateLimiter: ProviderRateLimiter,
        budgets: ObjectProvider<EnforceBudgets>,
        gate: AuthorizationGate,
    ): LlmGateway = SpringAiLlmGateway(
        registry = registry,
        factory = factory,
        optionsMapper = ChatOptionsMapper(properties.defaultMaxOutputTokens, properties.nativeStructuredOutput),
        parser = StructuredOutputParser(jsonMapper),
        catalog = catalog,
        ledger = ledger,
        clock = clock,
        properties = properties,
        observationRegistry = observationRegistry.getIfAvailable { ObservationRegistry.NOOP },
        rateLimiter = rateLimiter,
        budgetPrecheck = budgetPrecheck(budgets.getIfAvailable(), gate),
    )

    /** In-process calls stop at the same hard limits as the sandbox proxy (ADR 0025). */
    private fun budgetPrecheck(enforce: EnforceBudgets?, gate: AuthorizationGate): (UsageContext, BigDecimal, Long) -> Unit {
        if (enforce == null) return { _, _, _ -> }
        return { context, cost, tokens ->
            try {
                gate.asSystem("LLM budget check") { enforce.precheck(null, context, cost, tokens) }
            } catch (e: BudgetExceededException) {
                // A workflow node or benchmark attempt fails with this reason instead of spending past a hard stop.
                throw IntegrationException("budget-exceeded", e.message ?: "budget exceeded", e, retryable = false)
            }
        }
    }
}
