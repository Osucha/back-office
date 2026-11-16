package pl.mos.backoffice.governance.application.port.`in`

import pl.mos.backoffice.governance.domain.model.AgentInsights
import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.VerificationResult
import pl.mos.backoffice.governance.domain.model.WorkflowInsights
import pl.mos.backoffice.governance.domain.model.WorkflowMetrics
import pl.mos.backoffice.shared.platform.DsseEnvelope
import java.time.Instant
import java.util.UUID

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

/**
 * Workflow metrics (ADR 0037): one library workflow over the projects the caller may view (`library.view`), or one
 * project workflow (`project.view` on its project); exactly one of the two ids.
 */
interface WorkflowInsightsQuery {
    fun get(libraryWorkflowId: UUID?, workflowId: UUID?, from: Instant?, to: Instant?): WorkflowInsights

    /** The headline metrics per library workflow with runs in the window (`library.view`). */
    fun libraryHeadlines(from: Instant?, to: Instant?): Map<UUID, WorkflowMetrics>
}

/**
 * ADR 0059: agent insights: the task steps of one project (`project.view`) or of every visible project (`projectId`
 * null), per agent and model and per template.
 */
interface AgentInsightsQuery {
    fun get(projectId: UUID?, from: Instant?, to: Instant?): AgentInsights
}
