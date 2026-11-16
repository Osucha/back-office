package pl.mos.backoffice.runner

import com.osucha.portpermissions.test.assertSystemOnly
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.RegistrationTokenStore
import pl.mos.backoffice.runner.application.port.out.ResultArchive
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.application.service.AgentJobMaintenanceService
import pl.mos.backoffice.runner.application.service.JobLifecycle
import pl.mos.backoffice.runner.application.service.RunnerProtocolService
import pl.mos.backoffice.runner.application.service.SandboxCallbackService
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.runner.RunnerResult
import java.util.UUID

/**
 * ADR 0067: the runner protocol, the sandbox callbacks and the lease scan are system operations. Called by a user who
 * holds every permission (i.e. outside `asSystem`), they refuse before touching any store.
 */
class RunnerSystemOperationsTest {

    private val pools = mockk<RunnerPoolStore>(relaxed = true)
    private val nodes = mockk<RunnerNodeStore>(relaxed = true)
    private val registrations = mockk<RegistrationTokenStore>(relaxed = true)
    private val jobs = mockk<AgentJobStore>(relaxed = true)
    private val archive = mockk<ResultArchive>(relaxed = true)
    private val lifecycle = mockk<JobLifecycle>(relaxed = true)
    private val tx = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val runner = mockk<RunnerNode>(relaxed = true)
    private val jobId = RunnerJobId(UUID.randomUUID())

    private fun touched(): Boolean = runCatching { confirmVerified(pools, nodes, registrations, jobs, archive) }.isFailure

    @Test
    fun `the runner protocol refuses to lease outside asSystem`() {
        assertSystemOnly(touched = ::touched) { guard ->
            RunnerProtocolService(pools, nodes, registrations, jobs, lifecycle, tx, AuditPort.NOOP, signingKeys = { emptyList() }, guard = guard)
                .lease(runner, 1, null, null)
        }
    }

    @Test
    fun `a sandbox callback refuses to post a result outside asSystem`() {
        val credential = mockk<JobCredential>(relaxed = true)
        assertSystemOnly(touched = ::touched) { guard ->
            SandboxCallbackService(jobs, archive, lifecycle, tx, { }, null, guard).postResult(credential, jobId, mockk<RunnerResult>(relaxed = true))
        }
    }

    @Test
    fun `the lease scan refuses to run outside asSystem`() {
        assertSystemOnly(touched = ::touched) { guard -> AgentJobMaintenanceService(jobs, lifecycle, tx, guard).scan() }
    }
}
