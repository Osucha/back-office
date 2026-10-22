package pl.mos.backoffice.evaluation.adapter.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import pl.mos.backoffice.evaluation.adapter.out.executor.AttemptExecutorAdapter
import pl.mos.backoffice.evaluation.adapter.out.executor.BenchmarkAttemptHandler
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.WorkQueue
import java.time.Duration
import pl.mos.backoffice.evaluation.adapter.out.persistence.EvaluationMappers
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt
import pl.mos.backoffice.evaluation.application.port.`in`.ListProviders
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites
import pl.mos.backoffice.evaluation.application.port.`in`.RecoverBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.SeedStarterContent
import pl.mos.backoffice.evaluation.application.port.out.AttemptExecutor
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelDefaultRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.application.service.BenchmarkService
import pl.mos.backoffice.evaluation.application.service.ExecuteAttemptService
import pl.mos.backoffice.evaluation.application.service.ModelAvailabilityService
import pl.mos.backoffice.evaluation.application.service.ModelDefaultsService
import pl.mos.backoffice.evaluation.application.service.PersonalLoginModelsService
import pl.mos.backoffice.shared.llm.PersonalLoginModels
import pl.mos.backoffice.evaluation.application.service.ModelProfileService
import pl.mos.backoffice.evaluation.application.service.ProviderService
import pl.mos.backoffice.evaluation.application.service.SeedStarterContentService
import pl.mos.backoffice.evaluation.application.service.TaskSuiteService
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import tools.jackson.databind.json.JsonMapper

/**
 * Instantiates the Model Evaluation use cases from their ports. Beans provided to other modules:
 * `ManageModelProfiles`, `ListProviders`, `ManageTaskSuites`, `ManageBenchmarks`, `RecoverBenchmarks`,
 * `EvaluationDashboardQuery` (one transactional bean), `SeedStarterContent`, `ExecuteAttempt`,
 * `AttemptExecutor`, the evaluation `PermissionCatalogContribution` and, from the persistence package,
 * `ModelCatalog`. Requires `LlmGateway`, `AgentRunner`, `Clock`, `EventPublisher`, `AuthorizationGate`,
 * `AuditPort` and a Jackson 3 `JsonMapper` from the application.
 */
@Configuration
@EnableConfigurationProperties(EvaluationProperties::class)
class EvaluationConfiguration {

    @Bean
    fun evaluationMappers(jsonMapper: JsonMapper) = EvaluationMappers(jsonMapper)

    /** The evaluation permissions for the identity context's catalogue (ADR 0017). */
    @Bean
    fun evaluationPermissionCatalog(): PermissionCatalogContribution = PermissionCatalogContribution { EvaluationPermissions.definitions }

    /** ADR 0050 step 2: platform and personal default models; also the shared `ModelDefaults` port Projects resolve with. */
    @Bean
    fun modelDefaultsService(
        defaults: ModelDefaultRepository, profiles: ModelProfileRepository, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
        availability: ModelAvailability,
    ) = ModelDefaultsService(defaults, profiles, gate, audit, clock, availability)

    /** ADR 0050 step 3: Claude Code login profiles for a user whose personal runner reported their login. */
    @Bean
    fun personalLoginModels(profiles: ModelProfileRepository, clock: Clock, audit: AuditPort): PersonalLoginModels =
        PersonalLoginModelsService(profiles, clock, audit)

    /** ADR 0050: which models a use may take (shared port, read by Projects and the model pickers). */
    @Bean
    fun modelAvailability(profiles: ModelProfileRepository, gateway: LlmGateway, runner: AgentRunner): ModelAvailability =
        ModelAvailabilityService(profiles, gateway, runner)

    @Bean
    fun manageModelProfiles(
        profiles: ModelProfileRepository, benchmarks: BenchmarkRepository, gateway: LlmGateway, clock: Clock, gate: AuthorizationGate, audit: AuditPort,
        runner: AgentRunner,
    ): ManageModelProfiles = TransactionalManageModelProfiles(ModelProfileService(profiles, benchmarks, gateway, clock, gate, audit, runner))

    @Bean
    fun listProviders(gateway: LlmGateway, environment: Environment, gate: AuthorizationGate): ListProviders = ProviderService(
        gateway,
        mapOf(
            Provider.OLLAMA to environment.getProperty("spring.ai.ollama.base-url"),
            Provider.OPENAI_COMPATIBLE to environment.getProperty("backoffice.llm.openai-compatible.base-url"),
        ),
        gate,
    )

    @Bean
    fun manageTaskSuites(suites: TaskSuiteRepository, benchmarks: BenchmarkRepository, clock: Clock, gate: AuthorizationGate, audit: AuditPort): ManageTaskSuites =
        TransactionalManageTaskSuites(TaskSuiteService(suites, benchmarks, clock, gate, audit))

    /** One bean serving `ManageBenchmarks`, `RecoverBenchmarks` and `EvaluationDashboardQuery`. */
    @Bean
    fun benchmarkService(
        benchmarks: BenchmarkRepository,
        suites: TaskSuiteRepository,
        profiles: ModelProfileRepository,
        executor: AttemptExecutor,
        runner: AgentRunner,
        clock: Clock,
        gate: AuthorizationGate,
        audit: AuditPort,
    ): TransactionalBenchmarks = TransactionalBenchmarks(BenchmarkService(benchmarks, suites, profiles, executor, runner, clock, gate, audit))

    @Bean
    fun executeAttempt(
        benchmarks: BenchmarkRepository,
        suites: TaskSuiteRepository,
        profiles: ModelProfileRepository,
        gateway: LlmGateway,
        runner: AgentRunner,
        clock: Clock,
    ): ExecuteAttempt = ExecuteAttemptService(benchmarks, suites, profiles, gateway, runner, clock)


    @Bean
    fun attemptRetryPolicy(properties: EvaluationProperties) =
        AttemptExecutorAdapter.RetryPolicy(properties.retry.maxAttempts, properties.retry.initialBackoff, properties.retry.maxBackoff)

    /** Attempts become `BENCHMARK_ATTEMPT` work items (ADR 0023). */
    @Bean
    fun attemptExecutor(queue: WorkQueue, retryPolicy: AttemptExecutorAdapter.RetryPolicy): AttemptExecutorAdapter = AttemptExecutorAdapter(queue, retryPolicy)

    @Bean
    fun benchmarkAttemptHandler(executeAttempt: ExecuteAttempt, events: EventPublisher, retryPolicy: AttemptExecutorAdapter.RetryPolicy) =
        BenchmarkAttemptHandler(executeAttempt, events, retryPolicy)

    @Bean
    fun seedStarterContent(profiles: ModelProfileRepository, suites: TaskSuiteRepository, clock: Clock): SeedStarterContent =
        SeedStarterContentService(profiles, suites, clock)
}

/**
 * On startup: seed the catalogue when allowed and empty (ShedLock `evaluation-seed`, so concurrently starting
 * replicas do not race), then - on worker replicas, ShedLock `evaluation-recovery` - re-queue attempts of
 * running benchmarks. With the durable queue this only matters for attempts that lost their work item (e.g.
 * in flight during the upgrade); re-queueing is idempotent (dedupe key per attempt).
 */
@Component
class EvaluationStartupListener(
    private val properties: EvaluationProperties,
    private val seed: SeedStarterContent,
    private val recover: RecoverBenchmarks,
    private val lock: ObjectProvider<ClusterLock>,
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(EvaluationStartupListener::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        val cluster = lock.getIfAvailable { ClusterLock.LOCAL }
        if (properties.seedStarterContent) {
            cluster.runExclusively("evaluation-seed", Duration.ofMinutes(5), Duration.ofSeconds(30)) {
                val result = seed.seedIfEmpty()
                if (result.profilesCreated > 0 || result.suitesCreated > 0) {
                    log.info("seeded starter content profiles={} suites={}", result.profilesCreated, result.suitesCreated)
                }
            }
        }
        val worker = environment.getProperty("backoffice.platform.worker-enabled", Boolean::class.java, true)
        if (properties.recoverOnStartup && worker) {
            cluster.runExclusively("evaluation-recovery", Duration.ofMinutes(10), Duration.ofMinutes(10)) {
                val requeued = recover.recover()
                if (requeued > 0) log.info("re-queued benchmark attempts after restart count={}", requeued)
            }
        }
    }
}
