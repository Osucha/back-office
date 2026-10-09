package pl.mos.backoffice.governance.application.port.`in`

import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.VerificationResult
import pl.mos.backoffice.shared.platform.DsseEnvelope
import java.time.Instant
import java.util.UUID

/**
 * System use case: create the provenance attestations of a finished run (one per agent job with a commit or
 * diff, one for the run). Idempotent; called from domain events and from the catch-up sweep.
 */
interface RecordAttestations {
    /** Attests every finished agent job of the run that is not attested yet and, when the run is finished, the run itself. */
    fun attestRun(runId: UUID): List<Attestation>

    /** Attests one agent job (runner job id) of a run, if it finished with a commit or a diff. */
    fun attestJob(runId: UUID, jobId: UUID): Attestation?

    /** Catch-up: attests finished runs without a run attestation. Returns the number of runs attested. */
    fun attestPending(limit: Int = 50): Int
}

/** Queries over attestations (`project.view` of the run's project) and offline-verifiable signatures. */
interface ProvenanceQueries {
    fun listForRun(runId: UUID): List<Attestation>
    fun get(attestationId: UUID): Attestation
    /** Verifies [envelope] against the published keys (current and retired). */
    fun verify(envelope: DsseEnvelope): VerificationResult
    /** JWK set of the signing keys. */
    fun publicKeys(): List<Map<String, Any?>>
}

/** Delivery insights of one project (`project.view`) or of every visible project (`projectId` null). */
interface DeliveryInsightsQuery {
    fun get(projectId: UUID?, from: Instant?, to: Instant?): DeliveryInsights
}
