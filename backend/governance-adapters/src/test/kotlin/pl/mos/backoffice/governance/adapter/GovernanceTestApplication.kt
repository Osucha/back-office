package pl.mos.backoffice.governance.adapter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.provenance.RunProvenance
import pl.mos.backoffice.shared.provenance.RunProvenanceSource
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.UsageGroup
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal application for governance-adapters integration tests. */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.governance"])
class GovernanceTestApplication

class TestGate : AuthorizationGate {
    private val current = ThreadLocal<CurrentUser?>()
    @Volatile var defaultUser: CurrentUser = CurrentUser.SYSTEM.copy(kind = PrincipalKind.USER, userId = UserId(UUID.randomUUID()), login = "owner@example.com", displayName = "Olivia Owner")

    override fun currentUser(): CurrentUser = current.get() ?: defaultUser
    override fun require(permission: String): CurrentUser = currentUser().also { if (!it.can(permission)) throw InsufficientPermissionException(permission) }
    override fun require(permission: String, projectId: UUID): CurrentUser =
        currentUser().also { if (!it.can(permission, projectId)) throw InsufficientPermissionException(permission, projectId) }
    override fun <T> asSystem(reason: String, block: () -> T): T = runAs(CurrentUser.SYSTEM, block)

    fun <T> runAs(user: CurrentUser, block: () -> T): T {
        val previous = current.get()
        current.set(user)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    companion object {
        fun viewer(projectId: UUID) = CurrentUser(PrincipalKind.USER, UserId(UUID.randomUUID()), "v@example.com", "Vic Viewer", emptySet(), mapOf(projectId to setOf("project.view")))
    }
}

class FakeProvenanceSource : RunProvenanceSource {
    val runs = ConcurrentHashMap<UUID, RunProvenance>()
    override fun find(runId: UUID): RunProvenance? = runs[runId]
}

class FakeLedger : UsageLedger {
    val records = CopyOnWriteArrayList<UsageRecord>()
    override fun record(record: UsageRecord) { records += record }
    private fun of(query: UsageQuery) = records.filter { query.runId == null || it.context.runId == query.runId }
    override fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary {
        val list = of(query)
        val groups = list.groupBy { it.modelRef.label }.map { (label, rs) ->
            UsageGroup(label, label, Money.sum(rs.map { it.cost }), rs.fold(TokenUsage.ZERO) { a, r -> a + r.usage }, rs.size.toLong())
        }
        return UsageSummary(query.from, query.to, groupBy, Money.sum(list.map { it.cost }), list.fold(TokenUsage.ZERO) { a, r -> a + r.usage }, groups)
    }
    override fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> = of(query).let { PageOf(it, page, size, it.size.toLong()) }
    override fun totalCost(query: UsageQuery): Money = Money.sum(of(query).map { it.cost })
}

class RecordingAudit : AuditPort {
    val entries = CopyOnWriteArrayList<AuditEntry>()
    override fun record(entry: AuditEntry) { entries += entry }
}

@TestConfiguration(proxyBeanMethods = false)
class GovernanceTestBeans {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")

    @Bean fun clock(): Clock = Clock.SYSTEM
    @Bean fun gate(): TestGate = TestGate()
    @Bean fun provenanceSource(): FakeProvenanceSource = FakeProvenanceSource()
    @Bean fun ledger(): FakeLedger = FakeLedger()
    @Bean fun audit(): RecordingAudit = RecordingAudit()
}
