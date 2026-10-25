package pl.mos.backoffice.runner

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.application.service.AgentJobMaintenanceService
import pl.mos.backoffice.runner.application.service.FleetSettings
import pl.mos.backoffice.runner.application.service.JobLifecycle
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import java.time.Duration
import java.time.Instant

/** A Back Office restart does not cost a running agent job: its heartbeats could not arrive while Back Office was down. */
class LeaseScanRestartGraceTest {

    private val started = Instant.parse("2026-10-25T10:00:00Z")
    private var now = started
    private val running = mockk<AgentJob>(relaxed = true) {
        every { status } returns AgentJobStatus.RUNNING
        every { lostCount } returns 0
        every { cancelRequested } returns false
        every { spec.repository } returns null
        every { spec.repositories } returns emptyList()
    }
    private val jobs = mockk<AgentJobStore>(relaxed = true) {
        every { tryAdvisoryLock(any()) } returns true
        every { lockExpiredLeases(any(), any()) } returns listOf(running)
        every { lockQueuedPastDeadline(any(), any()) } returns emptyList()
        every { lockUnassigned(any()) } returns emptyList()
    }
    private val lifecycle = mockk<JobLifecycle>(relaxed = true) {
        every { now() } answers { now }
        every { settings } returns FleetSettings(apiUrl = "http://bo.test", restartGrace = Duration.ofMinutes(5))
    }
    private val tx = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val scan = AgentJobMaintenanceService(jobs, lifecycle, tx)

    @Test
    fun `running jobs with expired leases are left alone right after a restart and handled after the grace`() {
        now = started.plusSeconds(150)
        assertThat(scan.scan().requeued).isZero()
        verify(exactly = 0) { lifecycle.requeue(any(), any(), any()) }
        verify(exactly = 0) { lifecycle.finish(any(), any(), any(), any(), any()) }

        now = started.plus(Duration.ofMinutes(6))
        assertThat(scan.scan().requeued).isEqualTo(1)
        verify { lifecycle.requeue(running, any(), true) }
    }
}
