package pl.mos.backoffice.platform.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.platform.adapter.out.jdbc.JdbcOperationsReadModel
import pl.mos.backoffice.platform.cluster.PgClusterSignals
import pl.mos.backoffice.platform.cluster.PlatformHousekeeping
import pl.mos.backoffice.platform.cluster.ShedLockClusterLock
import pl.mos.backoffice.platform.events.OutboxEventPublisher
import pl.mos.backoffice.platform.events.PgNotificationListener
import pl.mos.backoffice.platform.events.PlatformEventStream
import pl.mos.backoffice.platform.metrics.PlatformMetrics
import pl.mos.backoffice.platform.queue.JdbcWorkQueue
import pl.mos.backoffice.platform.queue.WorkerLoop
import pl.mos.backoffice.platform.application.service.OperationsService
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.platform.AgentJobStatistics
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.platform.EventStream
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.security.AuthorizationGate
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * The PostgreSQL backbone (ADR 0023), as an auto-configuration so that every application or module test that
 * has `platform-runtime` on its classpath gets it, and so that a test can replace any piece
 * (`@ConditionalOnMissingBean`). Beans:
 *
 * - [WorkQueue] ([JdbcWorkQueue]), [EventPublisher] ([OutboxEventPublisher]), [EventStream], [ClusterLock]
 *   (ShedLock), [ClusterSignals] (`backoffice_cache`), the LISTEN bridge, metrics and housekeeping;
 * - worker role (`backoffice.platform.worker-enabled`): the [WorkerLoop] over every [WorkHandler] bean;
 * - api role off: a filter that answers everything but `/actuator/...` with 404 (worker-only replicas keep
 *   their web server for probes and the Prometheus scrape).
 */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    ],
)
@EnableConfigurationProperties(PlatformProperties::class)
class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun platformRoles(environment: Environment): PlatformRoles = PlatformRoles.parse(environment.getProperty(PlatformRoles.PROPERTY))

    @Bean
    fun platformMetrics(registry: ObjectProvider<MeterRegistry>, dataSource: DataSource): PlatformMetrics =
        PlatformMetrics(registry.getIfAvailable { SimpleMeterRegistry() }, JdbcClient.create(dataSource))

    @Bean
    @ConditionalOnMissingBean
    fun jdbcWorkQueue(dataSource: DataSource, json: JsonMapper): JdbcWorkQueue = JdbcWorkQueue(JdbcClient.create(dataSource), json)

    @Bean
    @ConditionalOnMissingBean(LockProvider::class)
    fun shedLockProvider(dataSource: DataSource, properties: PlatformProperties): LockProvider = JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(JdbcTemplate(dataSource))
            .withTableName("shedlock")
            .usingDbTime()
            .withLockedByValue(properties.resolvedInstanceId())
            .build(),
    )

    @Bean
    @ConditionalOnMissingBean(ClusterLock::class)
    fun clusterLock(provider: LockProvider): ClusterLock = ShedLockClusterLock(provider)

    @Bean
    @ConditionalOnMissingBean(ClusterSignals::class)
    fun clusterSignals(dataSource: DataSource, properties: PlatformProperties): PgClusterSignals =
        PgClusterSignals(JdbcClient.create(dataSource), properties.resolvedInstanceId())

    @Bean
    @ConditionalOnMissingBean(EventStream::class)
    fun platformEventStream(dataSource: DataSource, json: JsonMapper, metrics: PlatformMetrics): PlatformEventStream =
        PlatformEventStream(JdbcClient.create(dataSource), json, metrics)

    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun outboxEventPublisher(dataSource: DataSource, json: JsonMapper, spring: ApplicationEventPublisher): EventPublisher =
        OutboxEventPublisher(JdbcClient.create(dataSource), json, spring)

    @Bean
    @ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
    fun workerLoop(queue: JdbcWorkQueue, handlers: ObjectProvider<WorkHandler>, properties: PlatformProperties, metrics: PlatformMetrics): WorkerLoop {
        val w = properties.worker
        return WorkerLoop(
            queue, handlers.orderedStream().toList(),
            WorkerLoop.Settings(
                owner = properties.resolvedInstanceId(), concurrency = w.concurrency, lease = w.lease, pollInterval = w.pollInterval,
                claimBatch = w.claimBatch, drainTimeout = w.drainTimeout, initialBackoff = w.initialBackoff, maxBackoff = w.maxBackoff,
            ),
            metrics,
        )
    }

    /**
     * One dedicated connection per replica, outside the pool (PgBouncer transaction mode cannot carry LISTEN), to
     * `backoffice.platform.events.direct-url` when set, else to the data source URL.
     */
    @Bean
    @ConditionalOnProperty(prefix = "backoffice.platform.events", name = ["listen"], havingValue = "true", matchIfMissing = true)
    fun pgNotificationListener(
        connection: ObjectProvider<JdbcConnectionDetails>,
        dataSource: DataSource,
        properties: PlatformProperties,
        environment: Environment,
        metrics: PlatformMetrics,
        events: ObjectProvider<PlatformEventStream>,
        signals: ObjectProvider<PgClusterSignals>,
        worker: ObjectProvider<WorkerLoop>,
    ): PgNotificationListener {
        val details = connection.ifAvailable
        val directUrl = properties.events.directUrl
        val connect: () -> java.sql.Connection = if (directUrl.isNotBlank()) {
            // behind PgBouncer (transaction mode) the pool URL cannot carry LISTEN: connect to the primary directly
            directConnection(
                directUrl,
                details?.username ?: environment.getProperty("spring.datasource.username"),
                details?.password ?: environment.getProperty("spring.datasource.password"),
            )
        } else if (details != null) {
            { DriverManager.getConnection(details.jdbcUrl, details.username, details.password) }
        } else {
            // no connection details (custom DataSource bean): borrow a physical connection from the pool
            { dataSource.connection }
        }
        val listener = PgNotificationListener(
            connect,
            listOf(OutboxEventPublisher.CHANNEL, JdbcWorkQueue.CHANNEL, PgClusterSignals.CHANNEL),
            properties.events.reconnectDelay, properties.events.pollTimeout, metrics,
        )
        val stream = events.ifAvailable
        val cluster = signals.ifAvailable
        val loop = worker.ifAvailable
        listener.onNotification { channel, payloads ->
            when (channel) {
                OutboxEventPublisher.CHANNEL -> stream?.onNotified(payloads)
                PgClusterSignals.CHANNEL -> cluster?.onNotified(payloads)
                JdbcWorkQueue.CHANNEL -> loop?.wakeUp()
            }
        }
        listener.onConnected { first ->
            stream?.onReconnected(first)
            cluster?.onReconnected(first)
            if (!first) loop?.wakeUp()
        }
        return listener
    }

    private fun directConnection(url: String, username: String?, password: String?): () -> java.sql.Connection =
        { DriverManager.getConnection(url, username, password) }

    @Bean
    fun platformHousekeeping(
        dataSource: DataSource,
        properties: PlatformProperties,
        roles: PlatformRoles,
        clusterLock: ClusterLock,
        metrics: PlatformMetrics,
        build: ObjectProvider<BuildProperties>,
    ): PlatformHousekeeping {
        val effective = roles.copy(worker = properties.workerEnabled, api = properties.apiEnabled)
        return PlatformHousekeeping(JdbcClient.create(dataSource), properties, effective, clusterLock, metrics, build.ifAvailable?.version ?: "dev")
    }

    @Bean
    fun operationsService(
        dataSource: DataSource,
        queue: JdbcWorkQueue,
        events: ObjectProvider<EventStream>,
        agentJobs: ObjectProvider<AgentJobStatistics>,
        gate: AuthorizationGate,
        audit: ObjectProvider<AuditPort>,
        properties: PlatformProperties,
    ): OperationsService = OperationsService(
        JdbcOperationsReadModel(JdbcClient.create(dataSource), queue), events.ifAvailable, agentJobs.ifAvailable, gate,
        audit.getIfAvailable { AuditPort.NOOP }, properties.instanceStaleAfter,
    )

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "backoffice.platform", name = ["api-enabled"], havingValue = "false")
    fun workerOnlyFilter(): FilterRegistrationBean<OncePerRequestFilter> =
        FilterRegistrationBean<OncePerRequestFilter>(WorkerOnlyFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }

    /** Replica without the `api` role: only `/actuator/...` (probes, Prometheus) is served. */
    class WorkerOnlyFilter : OncePerRequestFilter() {
        override fun shouldNotFilter(request: HttpServletRequest): Boolean =
            request.requestURI.removePrefix(request.contextPath).startsWith("/actuator")

        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            response.contentType = "application/problem+json"
            response.writer.write("""{"type":"about:blank","title":"Not Found","status":404,"detail":"this replica runs without the api role"}""")
        }
    }
}
