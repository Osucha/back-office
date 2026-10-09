package pl.mos.backoffice.llm.proxy

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.llm.budget.adapter.BudgetTrackingUsageLedger
import pl.mos.backoffice.llm.budget.adapter.JdbcBudgetStore
import pl.mos.backoffice.llm.budget.application.service.BudgetService
import pl.mos.backoffice.llm.config.LlmProperties
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.ledger.JpaUsageLedger
import pl.mos.backoffice.llm.ratelimit.ProviderRateLimiter
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.BudgetRefusals
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.platform.JobTokenVerifier
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.UsageLedger
import tools.jackson.databind.json.JsonMapper

/** Budgets (ADR 0025) and the sandbox LLM proxy (ADR 0024). */
@Configuration
@EnableConfigurationProperties(ProxyProperties::class)
class ProxyConfiguration {

    @Bean
    fun budgetService(db: NamedParameterJdbcTemplate, gate: AuthorizationGate, audit: ObjectProvider<AuditPort>, events: ObjectProvider<EventPublisher>, clock: Clock) =
        BudgetService(JdbcBudgetStore(db), gate, audit.getIfAvailable { AuditPort.NOOP }, events.getIfAvailable { EventPublisher.NOOP }, clock)

    /** Lets the execution plane tag a job that ended after a hard budget stop (shared-kernel port). */
    @Bean
    fun budgetRefusals(db: NamedParameterJdbcTemplate): BudgetRefusals {
        val store = JdbcBudgetStore(db)
        return BudgetRefusals { jobId -> store.findJobRefusal(jobId) }
    }

    /** Every ledger write also moves the budget state; this is the `UsageLedger` other modules get. */
    @Bean
    @Primary
    fun budgetTrackingUsageLedger(jpa: JpaUsageLedger, budgets: BudgetService, gate: AuthorizationGate, transactionManager: PlatformTransactionManager): UsageLedger =
        BudgetTrackingUsageLedger(
            jpa, budgets, gate,
            TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW },
        )

    @Bean
    fun llmProxy(
        registry: ProviderRegistry,
        properties: ProxyProperties,
        llm: LlmProperties,
        rateLimiter: ProviderRateLimiter,
        budgets: BudgetService,
        ledger: UsageLedger,
        catalog: ObjectProvider<ModelCatalog>,
        gate: AuthorizationGate,
        json: JsonMapper,
        clock: Clock,
    ) = LlmProxy(registry, properties, llm.openaiCompatible.baseUrl, rateLimiter, budgets, ledger, catalog.getIfAvailable(), gate, json, clock)

    @Bean
    fun jobTokenFilter(verifier: ObjectProvider<JobTokenVerifier>): FilterRegistrationBean<JobTokenFilter> =
        FilterRegistrationBean(JobTokenFilter { verifier.getIfAvailable() }).apply {
            addUrlPatterns("/api/v1/llm/*")
            order = 0
        }
}
