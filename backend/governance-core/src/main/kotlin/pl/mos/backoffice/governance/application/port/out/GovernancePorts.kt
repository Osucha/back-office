package pl.mos.backoffice.governance.application.port.out

import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.JobEvidence
import pl.mos.backoffice.shared.security.ProjectFilter
import java.time.Instant
import java.util.UUID

interface AttestationRepository {
    /** Inserts unless an attestation with the same `subjectKey` exists; returns false when it already existed. */
    fun insertIfAbsent(attestation: Attestation): Boolean
    fun findById(id: UUID): Attestation?
    fun findByRunId(runId: UUID): List<Attestation>
    fun existsBySubjectKey(subjectKey: String): Boolean
    /** Finished runs (finished after [since]) that have no `RUN` attestation yet, oldest first. */
    fun runsMissingAttestation(since: Instant, limit: Int): List<UUID>
}

/** Spec and result of an agent job as stored by the execution plane (runner v1 `runner_job`, v2 `agent_job`). */
fun interface JobEvidenceSource {
    fun find(jobId: UUID): JobEvidence?
}

/** JSON encoding of plain maps/lists/strings/numbers/booleans (the statement and predicates). */
interface JsonCodec {
    fun encode(value: Any?): ByteArray
    fun decodeObject(bytes: ByteArray): Map<String, Any?>
}

/** SQL read model over the run, gate, CI and usage tables. */
fun interface DeliveryInsightsReadModel {
    fun compute(projects: ProjectFilter, from: Instant, to: Instant): DeliveryInsights
}
