package pl.mos.backoffice.governance.application.service

import com.osucha.portpermissions.test.assertSystemOnly
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.application.port.out.AttestationRepository
import pl.mos.backoffice.governance.application.port.out.JobEvidenceSource
import pl.mos.backoffice.governance.application.port.out.JsonCodec
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import pl.mos.backoffice.shared.provenance.RunProvenanceSource
import pl.mos.backoffice.shared.usage.UsageLedger
import java.util.UUID

/** ADR 0067: attestations are created by the system (events, sweep); outside `asSystem` nothing is read or signed. */
class AttestationSystemOperationsTest {

    private val source = mockk<RunProvenanceSource>(relaxed = true)
    private val evidence = mockk<JobEvidenceSource>(relaxed = true)
    private val ledger = mockk<UsageLedger>(relaxed = true)
    private val repository = mockk<AttestationRepository>(relaxed = true)
    private val signer = mockk<EnvelopeSigner>(relaxed = true)
    private val json = mockk<JsonCodec>(relaxed = true)

    private fun touched(): Boolean = runCatching { confirmVerified(source, evidence, ledger, repository, signer) }.isFailure

    private fun service(guard: com.osucha.portpermissions.AccessGuard) =
        AttestationService(source, evidence, ledger, null, repository, signer, json, AuditPort.NOOP, Clock.SYSTEM, guard = guard)

    @Test
    fun `the catch-up sweep and run attestation refuse a user holding every permission`() {
        assertSystemOnly(touched = ::touched) { guard -> service(guard).attestPending() }
        assertSystemOnly(touched = ::touched) { guard -> service(guard).attestRun(UUID.randomUUID()) }
    }
}
