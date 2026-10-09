package pl.mos.backoffice.llm.usage

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.time.Instant
import java.util.UUID

/** ADR 0017 for `/api/v1/usage` endpoints: platform view, project view, run lookups narrowed to the run's project. */
class UsageQueryServiceTest {

    private val project = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val otherProject = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val run = UUID.randomUUID()
    private val ledger = mockk<UsageLedger>()
    private val queries = mutableListOf<UsageQuery>()

    init {
        val q = slot<UsageQuery>()
        every { ledger.summary(capture(q), any()) } answers {
            queries += q.captured
            UsageSummary(null, null, secondArg(), Money.ZERO, TokenUsage.ZERO, emptyList())
        }
        every { ledger.entries(capture(q), any(), any()) } answers {
            queries += q.captured
            val rows = if (q.captured.runId == run) listOf(record()) else emptyList()
            PageOf(rows, 0, 20, rows.size.toLong())
        }
    }

    private fun record() = UsageRecord(
        occurredAt = Instant.parse("2026-09-27T10:00:00Z"), modelRef = ModelRef(Provider.ANTHROPIC, "m"), usage = TokenUsage(1, 1),
        cost = Money.ZERO, estimatedCost = Money.ZERO, durationMs = 1, context = UsageContext(UsageKind.PIPELINE_STEP, projectId = project, runId = run),
    )

    private fun all() = UsageQuery(null, null)

    @Test
    fun `the module declares usage view and project usage view`() {
        assertThat(LlmPermissions.definitions.associate { it.key to it.scope })
            .containsExactlyInAnyOrderEntriesOf(mapOf("usage.view" to PermissionScope.PLATFORM, "project.usage.view" to PermissionScope.PROJECT))
    }

    @Test
    fun `unfiltered usage needs usage view`() {
        val gate = FakeAuthorizationGate.permitting("usage.view")
        UsageQueryService(ledger, gate).summary(all(), UsageGroupBy.MODEL)
        assertThat(gate.checked).containsExactly("usage.view")

        val member = FakeAuthorizationGate.permitting("project.usage.view", projects = setOf(project))
        assertThatThrownBy { UsageQueryService(ledger, member).summary(all(), UsageGroupBy.MODEL) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { UsageQueryService(ledger, member).entries(UsageQuery(null, null, benchmarkId = UUID.randomUUID()), 0, 20) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `a project filter needs project usage view on that project`() {
        val member = FakeAuthorizationGate.permitting("project.usage.view", projects = setOf(project))
        UsageQueryService(ledger, member).summary(UsageQuery(null, null, projectId = project), UsageGroupBy.DAY)
        assertThat(member.checked).containsExactly("project.usage.view@$project")
        assertThatThrownBy { UsageQueryService(ledger, member).summary(UsageQuery(null, null, projectId = otherProject), UsageGroupBy.DAY) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `entries by run are allowed to members of the run's project and narrowed to it`() {
        val member = FakeAuthorizationGate.permitting("project.usage.view", projects = setOf(project))
        val page = UsageQueryService(ledger, member).entries(UsageQuery(null, null, runId = run), 0, 20)
        assertThat(page.content).hasSize(1)
        assertThat(queries.last().projectId).isEqualTo(project)

        val outsider = FakeAuthorizationGate.permitting("project.usage.view", projects = setOf(otherProject))
        assertThatThrownBy { UsageQueryService(ledger, outsider).entries(UsageQuery(null, null, runId = run), 0, 20) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `a run without ledger rows answers empty for project readers and is denied to everyone else`() {
        val unknownRun = UUID.randomUUID()
        val member = FakeAuthorizationGate.permitting("project.usage.view", projects = setOf(project))
        val summary = UsageQueryService(ledger, member).summary(UsageQuery(null, null, runId = unknownRun), UsageGroupBy.MODEL)
        assertThat(summary.groups).isEmpty()
        verify(exactly = 0) { ledger.summary(any(), any()) }

        assertThatThrownBy { UsageQueryService(ledger, FakeAuthorizationGate.denyAll()).summary(UsageQuery(null, null, runId = unknownRun), UsageGroupBy.MODEL) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }
}
