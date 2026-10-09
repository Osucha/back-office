package pl.mos.backoffice.governance.adapter.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.governance.adapter.`in`.events.AttestationTrigger
import pl.mos.backoffice.governance.adapter.`in`.mcp.BackOfficeMcpTools
import pl.mos.backoffice.governance.adapter.`in`.mcp.McpPersonalTokenFilter
import pl.mos.backoffice.governance.adapter.out.evidence.JdbcJobEvidenceSource
import pl.mos.backoffice.governance.adapter.out.insights.JdbcDeliveryInsightsReadModel
import pl.mos.backoffice.governance.adapter.out.json.JacksonJsonCodec
import pl.mos.backoffice.governance.adapter.out.persistence.JdbcAttestationRepository
import pl.mos.backoffice.governance.adapter.out.signing.Ed25519EnvelopeSigner
import pl.mos.backoffice.governance.adapter.out.signing.SigningProperties
import pl.mos.backoffice.governance.application.port.`in`.DeliveryInsightsQuery
import pl.mos.backoffice.governance.application.port.`in`.RecordAttestations
import pl.mos.backoffice.governance.application.service.AttestationService
import pl.mos.backoffice.governance.application.service.DeliveryInsightsService
import pl.mos.backoffice.governance.application.service.ProvenanceQueryService
import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.provenance.RunProvenanceSource
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.tasks.application.port.`in`.ManageSkills
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskTemplates

/**
 * Wiring of the Governance context (ADR 0025): the Ed25519 signer (the shared `EnvelopeSigner`, also used by the
 * runner control plane for job assignments), attestations, delivery insights.
 */
@Configuration
@EnableConfigurationProperties(SigningProperties::class)
class GovernanceConfiguration {

    @Bean
    fun governanceJsonCodec() = JacksonJsonCodec()

    @Bean
    fun envelopeSigner(properties: SigningProperties): Ed25519EnvelopeSigner = Ed25519EnvelopeSigner.fromProperties(properties)

    @Bean
    fun attestationRepository(jdbc: JdbcClient, json: JacksonJsonCodec) = JdbcAttestationRepository(jdbc, json)

    @Bean
    fun jobEvidenceSource(jdbc: JdbcClient, json: JacksonJsonCodec) = JdbcJobEvidenceSource(jdbc, json)

    @Bean
    fun deliveryInsightsReadModel(jdbc: JdbcClient) = JdbcDeliveryInsightsReadModel(jdbc)

    @Bean
    fun attestationService(
        source: RunProvenanceSource,
        evidence: JdbcJobEvidenceSource,
        ledger: UsageLedger,
        tasks: ObjectProvider<TaskCatalog>,
        repository: JdbcAttestationRepository,
        signer: Ed25519EnvelopeSigner,
        json: JacksonJsonCodec,
        audit: AuditPort,
        clock: Clock,
    ) = AttestationService(source, evidence, ledger, tasks.ifAvailable, repository, signer, json, audit, clock)

    @Bean
    fun provenanceQueryService(
        source: RunProvenanceSource,
        repository: JdbcAttestationRepository,
        signer: Ed25519EnvelopeSigner,
        json: JacksonJsonCodec,
        gate: AuthorizationGate,
    ) = ProvenanceQueryService(source, repository, signer, json, gate)

    @Bean
    fun deliveryInsightsService(readModel: JdbcDeliveryInsightsReadModel, gate: AuthorizationGate, clock: Clock) = DeliveryInsightsService(readModel, gate, clock)

    @Bean
    @ConditionalOnProperty(prefix = "backoffice.provenance", name = ["enabled"], matchIfMissing = true)
    fun attestationTrigger(record: RecordAttestations, gate: AuthorizationGate, jdbc: JdbcClient, transactions: PlatformTransactionManager) =
        AttestationTrigger(record, gate, jdbc, TransactionTemplate(transactions))
}

/**
 * The MCP server's tools and the PAT-only guard of its endpoint. Disabled with `backoffice.mcp.enabled=false`
 * (then also set `spring.ai.mcp.server.enabled=false`).
 */
@Configuration
@ConditionalOnProperty(prefix = "backoffice.mcp", name = ["enabled"], matchIfMissing = true)
class McpServerConfiguration {

    @Bean
    fun backOfficeMcpTools(
        gate: AuthorizationGate,
        runs: RunQueries,
        startRun: StartRun,
        approvals: ApprovalQueries,
        decideApproval: DecideApproval,
        projects: ManageProjects,
        workflows: ManageWorkflows,
        templates: ManageTaskTemplates,
        skills: ManageSkills,
        insights: DeliveryInsightsQuery,
        audit: AuditPort,
    ) = BackOfficeMcpTools(gate, runs, startRun, approvals, decideApproval, projects, workflows, templates, skills, insights, audit)

    /** Before Spring Security's chain (order -100) so non-PAT credentials never reach the MCP transport. */
    @Bean
    fun mcpPersonalTokenFilter(@Value("\${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") endpoint: String) =
        FilterRegistrationBean(McpPersonalTokenFilter(endpoint)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 30
            addUrlPatterns(endpoint, "$endpoint/*")
        }
}
