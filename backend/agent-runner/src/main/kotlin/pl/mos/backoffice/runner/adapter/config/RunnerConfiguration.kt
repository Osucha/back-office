package pl.mos.backoffice.runner.adapter.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.runner.RunnerProperties
import pl.mos.backoffice.runner.adapter.`in`.web.AssignmentSigner
import pl.mos.backoffice.runner.adapter.`in`.web.RunnerProtocolController
import pl.mos.backoffice.runner.adapter.`in`.web.RunnerTokenFilter
import pl.mos.backoffice.runner.adapter.out.BlobResultArchive
import pl.mos.backoffice.runner.adapter.out.InProcessLeaseSignal
import pl.mos.backoffice.runner.adapter.out.SpringTransactions
import pl.mos.backoffice.runner.adapter.out.persistence.JdbcFleetStores
import pl.mos.backoffice.runner.adapter.out.persistence.JdbcRunWorkspaceStore
import pl.mos.backoffice.runner.application.port.`in`.MaintainAgentJobs
import pl.mos.backoffice.runner.application.port.out.LeaseSignal
import pl.mos.backoffice.runner.application.port.out.ResultArchive
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.application.service.AgentJobMaintenanceService
import pl.mos.backoffice.runner.application.service.AgentJobService
import pl.mos.backoffice.runner.application.service.FleetAdminService
import pl.mos.backoffice.runner.application.service.FleetSettings
import pl.mos.backoffice.runner.application.service.JobLifecycle
import pl.mos.backoffice.runner.application.service.PersonalRunnerService
import pl.mos.backoffice.runner.application.service.PersonalRunnerSettings
import pl.mos.backoffice.runner.application.service.ProjectSandboxPolicyService
import pl.mos.backoffice.runner.application.service.RunnerProtocolService
import pl.mos.backoffice.runner.application.service.SandboxCallbackService
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.BudgetRefusals
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.platform.BlobStore
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import tools.jackson.databind.json.JsonMapper

/**
 * Wires the agent execution control plane (ADR 0024). Optional collaborators from other modules:
 * `WorkQueue` (continuations), `BlobStore` (large results, uploads), `EnvelopeSigner` (assignment signatures),
 * `ModelCatalog` (job cost from the result) - each is used when present.
 */
@Configuration
@EnableConfigurationProperties(RunnerProperties::class)
class RunnerConfiguration {

    private val log = LoggerFactory.getLogger(RunnerConfiguration::class.java)

    @Bean
    fun fleetSettings(props: RunnerProperties, @Value("\${backoffice.public-url:http://localhost:8080}") publicUrl: String) = FleetSettings(
        apiUrl = props.apiUrl.ifBlank { publicUrl },
        leaseDuration = props.leaseDuration,
        restartGrace = props.restartGrace,
        onlineWindow = props.onlineWindow,
        queueTimeout = props.queueTimeout,
        maxUnacknowledgedLeases = props.maxUnacknowledgedLeases,
        registrationTokenValidity = props.registrationTokenValidity,
        uploadUrlTtl = props.uploadUrlTtl,
        maxUploadBytes = props.maxUploadBytes,
        inlineResultMaxBytes = props.inlineResultMaxBytes,
        extraAllowedModels = props.extraAllowedModels,
        platformPolicy = props.platformPolicy.toPolicy(),
        maxLlmRequestsPerJob = props.maxLlmRequestsPerJob,
        personal = props.personal.let {
            PersonalRunnerSettings(it.controllerImage, it.runnerImage, it.egressProxyImage, it.allowedImages, it.maxConcurrentJobs.coerceAtLeast(1))
        },
    ).also { log.info("runner fleet control plane: api-url={} lease={} queue-timeout={}", it.apiUrl, it.leaseDuration, it.queueTimeout) }

    @Bean
    fun jdbcFleetStores(db: NamedParameterJdbcTemplate, json: JsonMapper) = JdbcFleetStores(db, json)

    /** ADR 0044: persistent workspaces of runs. */
    @Bean
    fun runWorkspaceStore(db: NamedParameterJdbcTemplate) = JdbcRunWorkspaceStore(db)

    @Bean
    fun runnerTransactions(transactionManager: PlatformTransactionManager): Transactions = SpringTransactions(TransactionTemplate(transactionManager))

    @Bean
    fun leaseSignal(): LeaseSignal = InProcessLeaseSignal()

    @Bean
    fun resultArchive(blobStore: ObjectProvider<BlobStore>, json: JsonMapper): ResultArchive = BlobResultArchive({ blobStore.getIfAvailable() }, json)

    @Bean
    fun jobLifecycle(
        stores: JdbcFleetStores,
        workQueue: ObjectProvider<WorkQueue>,
        signal: LeaseSignal,
        events: EventPublisher,
        clock: Clock,
        settings: FleetSettings,
        workspaces: JdbcRunWorkspaceStore,
    ): JobLifecycle {
        val queue = workQueue.getIfAvailable()
        if (queue == null) log.warn("no WorkQueue bean: agent job continuations will not be enqueued")
        return JobLifecycle(stores, stores, stores, stores, queue, signal, events, clock, settings, workspaces)
    }

    /** The shared `AgentRunner` port and `JobTokenVerifier`. */
    @Bean
    fun agentJobService(stores: JdbcFleetStores, lifecycle: JobLifecycle, tx: Transactions, archive: ResultArchive) =
        AgentJobService(stores, stores, lifecycle, tx, archive)

    @Bean
    fun fleetAdminService(stores: JdbcFleetStores, lifecycle: JobLifecycle, tx: Transactions, gate: AuthorizationGate, audit: ObjectProvider<AuditPort>) =
        FleetAdminService(stores, stores, stores, stores, lifecycle, tx, gate, audit.getIfAvailable { AuditPort.NOOP })

    @Bean
    fun personalRunnerService(stores: JdbcFleetStores, lifecycle: JobLifecycle, tx: Transactions, gate: AuthorizationGate, audit: ObjectProvider<AuditPort>) =
        PersonalRunnerService(stores, stores, stores, stores, lifecycle, tx, gate, audit.getIfAvailable { AuditPort.NOOP })

    @Bean
    fun projectSandboxPolicyService(stores: JdbcFleetStores, lifecycle: JobLifecycle, tx: Transactions, gate: AuthorizationGate, audit: ObjectProvider<AuditPort>) =
        ProjectSandboxPolicyService(stores, stores, lifecycle, tx, gate, audit.getIfAvailable { AuditPort.NOOP })

    /** ADR 0050 step 4: model calls of Claude Code login models, answered by PROMPT jobs on the user's personal runner. */
    @Bean
    fun ownLoginLlm(runner: AgentJobService): pl.mos.backoffice.shared.llm.OwnLoginLlm {
        val json = tools.jackson.databind.json.JsonMapper.builder().build()
        @Suppress("UNCHECKED_CAST")
        return pl.mos.backoffice.runner.application.service.ClaudeCodePromptLlm(runner, { text -> json.readValue(text, Map::class.java) as Map<String, Any?> })
    }

    @Bean
    fun runnerProtocolService(
        stores: JdbcFleetStores,
        lifecycle: JobLifecycle,
        tx: Transactions,
        audit: ObjectProvider<AuditPort>,
        signer: ObjectProvider<EnvelopeSigner>,
        budgetRefusals: ObjectProvider<BudgetRefusals>,
        loginModels: ObjectProvider<pl.mos.backoffice.shared.llm.PersonalLoginModels>,
        repositoryTokens: ObjectProvider<pl.mos.backoffice.shared.runner.RepositoryTokenSource>,
        sealer: pl.mos.backoffice.runner.application.port.out.CredentialSealer,
    ) = RunnerProtocolService(
        stores, stores, stores, stores, lifecycle, tx, audit.getIfAvailable { AuditPort.NOOP },
        signingKeys = { signer.getIfAvailable()?.publicJwks().orEmpty() },
        budgetRefusals = budgetRefusals.getIfAvailable { BudgetRefusals.NONE },
        loginModels = loginModels.getIfAvailable(),
        repositoryTokens = repositoryTokens.getIfAvailable(),
        sealer = sealer,
    )

    /** ADR 0051: HPKE sealing of project repository tokens to runner-controller keys. */
    @Bean fun credentialSealer(): pl.mos.backoffice.runner.application.port.out.CredentialSealer =
        pl.mos.backoffice.runner.adapter.out.crypto.HpkeCredentialSealer()

    @Bean
    fun sandboxCallbackService(
        stores: JdbcFleetStores,
        archive: ResultArchive,
        lifecycle: JobLifecycle,
        tx: Transactions,
        events: EventPublisher,
        catalog: ObjectProvider<ModelCatalog>,
    ) = SandboxCallbackService(stores, archive, lifecycle, tx, events, catalog.getIfAvailable())

    @Bean
    fun agentJobMaintenanceService(stores: JdbcFleetStores, lifecycle: JobLifecycle, tx: Transactions) = AgentJobMaintenanceService(stores, lifecycle, tx)

    @Bean
    fun assignmentSigner(json: JsonMapper, signer: ObjectProvider<EnvelopeSigner>) = AssignmentSigner(json) { signer.getIfAvailable() }

    @Bean
    fun leasePollSettings(props: RunnerProperties) = RunnerProtocolController.LongPollSettings(props.maxLeaseWait, props.leasePollInterval)

    /** Token authentication of the runner-fleet and sandbox callback paths (after the security chain, which permits them). */
    @Bean
    fun runnerTokenFilter(protocol: RunnerProtocolService, verifier: AgentJobService): FilterRegistrationBean<RunnerTokenFilter> =
        FilterRegistrationBean(RunnerTokenFilter(protocol, verifier)).apply {
            addUrlPatterns("/api/v1/runner/v2/*", "/api/v1/internal/runner/*")
            order = FILTER_ORDER
        }

    @Bean
    fun runnerPermissionCatalog(): PermissionCatalogContribution = PermissionCatalogContribution { RunnerPermissions.definitions }

    @Bean
    fun leaseScanScheduler(maintenance: MaintainAgentJobs, gate: AuthorizationGate, props: RunnerProperties) = LeaseScanScheduler(maintenance, gate, props)

    /** ADR 0046: the shared pool and registration token of a local stack (`backoffice.runner.local-fleet`), after the migrations. */
    @Bean
    fun localFleetBootstrap(admin: FleetAdminService, props: RunnerProperties) = ApplicationListener<ApplicationReadyEvent> {
        val fleet = props.localFleet
        if (fleet.registrationToken.isBlank()) return@ApplicationListener
        runCatching { admin.bootstrapLocalFleet(fleet.poolId, fleet.registrationToken, props.personal.allowedImages, fleet.maxConcurrentJobs) }
            .onSuccess { log.info("local fleet: pool {} accepts the configured registration token", fleet.poolId) }
            .onFailure { log.warn("local fleet bootstrap failed: {}", it.toString()) }
    }

    companion object {
        /** After Spring Security's chain (-100). */
        const val FILTER_ORDER = 0
    }
}

/** Runs the lease scan every `backoffice.runner.lease-scan.interval` on every replica; the advisory lock lets one of them work. */
class LeaseScanScheduler(private val maintenance: MaintainAgentJobs, private val gate: AuthorizationGate, private val props: RunnerProperties) {
    private val log = LoggerFactory.getLogger(LeaseScanScheduler::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.runner.lease-scan.interval:PT10S}", initialDelayString = "\${backoffice.runner.lease-scan.interval:PT10S}")
    fun run() {
        if (!props.leaseScan.enabled) return
        try {
            val outcome = gate.asSystem("agent job lease scan") { maintenance.scan() }
            if (outcome.requeued + outcome.lost + outcome.timedOut + outcome.routed > 0) {
                log.info("lease scan: requeued={} lost={} timedOut={} routed={}", outcome.requeued, outcome.lost, outcome.timedOut, outcome.routed)
            }
        } catch (e: RuntimeException) {
            log.warn("lease scan failed: {}", e.toString())
        }
    }
}
