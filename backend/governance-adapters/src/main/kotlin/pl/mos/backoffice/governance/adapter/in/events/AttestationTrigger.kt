package pl.mos.backoffice.governance.adapter.`in`.events

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.governance.application.port.`in`.RecordAttestations
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Drives [RecordAttestations] (ADR 0025):
 * - `WorkflowRunFinished` (Projects) -> attest the run and its agent jobs;
 * - `AgentJobFinished` / `RunnerJobFinished` (runner control plane, payload `runId` + `jobId`) -> attest that job;
 * - a catch-up sweep every `backoffice.provenance.sweep-interval` attests finished runs of the last 7 days that
 *   still lack a run attestation (missed events, crashes, the runner event not existing yet). Only one replica
 *   sweeps at a time: `pg_try_advisory_xact_lock`.
 * Events are handled after the publishing transaction committed, on a virtual thread, as the system principal.
 */
class AttestationTrigger(
    private val record: RecordAttestations,
    private val gate: AuthorizationGate,
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(AttestationTrigger::class.java)
    private val executor: ExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("attestation-", 0).factory())

    @TransactionalEventListener(classes = [DomainEvent::class], phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onDomainEvent(event: DomainEvent) {
        when (event.type) {
            RUN_FINISHED -> uuid(event.payload["runId"])?.let { runId -> submit("run $runId") { record.attestRun(runId) } }
            in JOB_FINISHED -> {
                val runId = uuid(event.payload["runId"]) ?: return
                val jobId = uuid(event.payload["jobId"]) ?: return
                submit("job $jobId of run $runId") { record.attestJob(runId, jobId) }
            }
        }
    }

    @Scheduled(
        initialDelayString = "\${backoffice.provenance.sweep-initial-delay:PT1M}",
        fixedDelayString = "\${backoffice.provenance.sweep-interval:PT5M}",
    )
    fun sweep() {
        runCatching { sweepNow() }.onFailure { log.warn("attestation sweep failed: {}", it.toString()) }
    }

    /** One sweep; returns the number of runs attested, or -1 when another replica holds the lock. */
    fun sweepNow(): Int = transactions.execute {
        val locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean::class.java).single()
        if (!locked) return@execute -1
        gate.asSystem("provenance attestation sweep") { record.attestPending() }.also { if (it > 0) log.info("attestation sweep attested runs={}", it) }
    } ?: 0

    private fun submit(what: String, block: () -> Any?) {
        executor.execute {
            runCatching { gate.asSystem("provenance attestation") { block() } }
                .onFailure { log.warn("attestation of {} failed (the sweep retries): {}", what, it.toString()) }
        }
    }

    override fun close() {
        executor.shutdown()
    }

    private fun uuid(value: Any?): UUID? = value?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val RUN_FINISHED = "WorkflowRunFinished"
        val JOB_FINISHED = setOf("AgentJobFinished", "RunnerJobFinished")
        /** Arbitrary constant: "BO-ATTS" as a 64-bit key. */
        const val LOCK_KEY = 0x424F2D41545453L
    }
}
