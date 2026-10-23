package pl.mos.backoffice.runner.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkspaceSyncTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun row(name: String, runner: UUID? = me, active: UUID? = null, releaseAfter: Instant? = null, released: Instant? = null) =
        RunWorkspace(name, UUID.randomUUID(), "main", "pool", runner, active, now, now, releaseAfter, released)

    @Test
    fun `unknown, released and foreign volumes are deleted, live ones kept`() {
        val rows = listOf(row("live"), row("released", released = now), row("foreign", runner = other))
        val d = WorkspaceSync.decide(me, listOf("live", "released", "foreign", "unknown"), rows, now)
        assertThat(d.delete).containsExactlyInAnyOrder("released", "foreign", "unknown")
        assertThat(d.release).isEmpty()
        assertThat(d.forget).isEmpty()
    }

    @Test
    fun `a held workspace of a revoked runner of the same pool is adopted, of a live or foreign-pool runner deleted`() {
        val rows = listOf(row("orphan", runner = other), row("live-elsewhere", runner = UUID.randomUUID()), row("unbound", runner = null))
        val d = WorkspaceSync.decide(me, listOf("orphan", "live-elsewhere", "unbound"), rows, now, "pool", setOf(other))
        assertThat(d.adopt).containsExactlyInAnyOrder("orphan", "unbound")
        assertThat(d.delete).containsExactly("live-elsewhere")
        val otherPool = WorkspaceSync.decide(me, listOf("orphan"), rows, now, "another-pool", setOf(other))
        assertThat(otherPool.adopt).isEmpty()
        assertThat(otherPool.delete).containsExactly("orphan")
        val due = WorkspaceSync.decide(me, listOf("orphan"), listOf(row("orphan", runner = other, releaseAfter = now)), now, "pool", setOf(other))
        assertThat(due.release).containsExactly("orphan")
        assertThat(due.delete).containsExactly("orphan")
    }

    @Test
    fun `a due workspace is released and deleted unless a job uses it`() {
        val rows = listOf(row("due", releaseAfter = now), row("busy", active = UUID.randomUUID(), releaseAfter = now), row("later", releaseAfter = now.plusSeconds(60)))
        val d = WorkspaceSync.decide(me, listOf("due", "busy", "later"), rows, now)
        assertThat(d.delete).containsExactly("due")
        assertThat(d.release).containsExactly("due")
    }

    @Test
    fun `rows of this runner without a volume are released when due, otherwise forgotten`() {
        val rows = listOf(row("gone-due", releaseAfter = now.minusSeconds(1)), row("gone"), row("gone-busy", active = UUID.randomUUID()))
        val d = WorkspaceSync.decide(me, emptyList(), rows, now)
        assertThat(d.delete).isEmpty()
        assertThat(d.release).containsExactly("gone-due")
        assertThat(d.forget).containsExactly("gone")
    }
}
