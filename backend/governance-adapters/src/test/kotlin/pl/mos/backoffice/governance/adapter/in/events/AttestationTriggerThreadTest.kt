package pl.mos.backoffice.governance.adapter.`in`.events

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.application.port.`in`.system.RecordAttestations
import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.FakeAccessGuard
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * ADR 0067: a finished run is attested on the trigger's own virtual thread; `asSystem` is thread-bound, so the trigger
 * enters it on that thread, not on the thread that published the event.
 */
class AttestationTriggerThreadTest {

    private val guard = FakeAccessGuard.denyAll()
    private val attested = CompletableFuture<Pair<String, PrincipalKind>>()

    private val record = object : RecordAttestations {
        override fun attestRun(runId: UUID): List<Attestation> {
            guard.requireSystem()
            attested.complete(Thread.currentThread().name to guard.currentUser().kind)
            return emptyList()
        }
        override fun attestJob(runId: UUID, jobId: UUID): Attestation? = null
        override fun attestPending(limit: Int): Int = 0
    }

    @Test
    fun `a finished run is attested as the system on the trigger's thread`() {
        val trigger = AttestationTrigger(record, guard, mockk(), mockk())
        val event = object : DomainEvent {
            override val type = AttestationTrigger.RUN_FINISHED
            override val occurredAt: Instant = Instant.EPOCH
            override val payload: Map<String, Any?> = mapOf("runId" to UUID.randomUUID().toString())
        }

        trigger.onDomainEvent(event)

        val (thread, principal) = attested.get(10, TimeUnit.SECONDS)
        assertThat(thread).startsWith("attestation-")
        assertThat(principal).isEqualTo(PrincipalKind.SYSTEM)
        assertThat(guard.currentUser().kind).describedAs("the publishing thread stays the user").isEqualTo(PrincipalKind.USER)
        trigger.close()
    }
}
