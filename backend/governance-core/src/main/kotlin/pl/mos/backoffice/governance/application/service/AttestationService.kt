package pl.mos.backoffice.governance.application.service

import pl.mos.backoffice.governance.application.port.`in`.ProvenanceQueries
import pl.mos.backoffice.governance.application.port.`in`.RecordAttestations
import pl.mos.backoffice.governance.application.port.out.AttestationRepository
import pl.mos.backoffice.governance.application.port.out.JobEvidenceSource
import pl.mos.backoffice.governance.application.port.out.JsonCodec
import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.AttestationKind
import pl.mos.backoffice.governance.domain.model.GovernancePermissions
import pl.mos.backoffice.governance.domain.model.InToto
import pl.mos.backoffice.governance.domain.model.VerificationResult
import pl.mos.backoffice.governance.domain.service.Digests
import pl.mos.backoffice.governance.domain.service.Dsse
import pl.mos.backoffice.governance.domain.service.LedgerUsage
import pl.mos.backoffice.governance.domain.service.ProvenanceStatements
import pl.mos.backoffice.governance.domain.service.StatementDraft
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import pl.mos.backoffice.shared.provenance.ProvenanceNode
import pl.mos.backoffice.shared.provenance.RunProvenance
import pl.mos.backoffice.shared.provenance.RunProvenanceSource
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import java.time.Duration
import java.util.UUID

/**
 * Creates signed in-toto attestations (ADR 0025): one per finished agent job that produced a commit or a
 * diff, and one per finished run. Creation is idempotent on the subject key, so the event listener, the
 * runner's job-finished event and the catch-up sweep may all call it.
 */
class AttestationService(
    private val source: RunProvenanceSource,
    private val evidence: JobEvidenceSource,
    private val ledger: UsageLedger,
    private val tasks: TaskCatalog?,
    private val repository: AttestationRepository,
    private val signer: EnvelopeSigner,
    private val json: JsonCodec,
    private val audit: AuditPort,
    private val clock: Clock,
    private val lookback: Duration = Duration.ofDays(7),
) : RecordAttestations {

    @SystemOperation("invoked by the attestation trigger on WorkflowRunFinished / AgentJobFinished and by the catch-up sweep")
    override fun attestRun(runId: UUID): List<Attestation> {
        val run = source.find(runId) ?: return emptyList()
        val created = mutableListOf<Attestation>()
        run.nodes.filter { it.runnerJobId != null && it.status in FINISHED_NODE }.forEach { node ->
            attestNode(run, node)?.let(created::add)
        }
        if (!run.terminal) return created
        val runKey = Attestation.runKey(run.runId)
        if (repository.existsBySubjectKey(runKey)) return created
        val jobs = repository.findByRunId(run.runId).filter { it.kind == AttestationKind.AGENT_JOB }.sortedBy { it.createdAt }
        val draft = StatementDraft(
            subjects = ProvenanceStatements.runSubjects(run, jobs.flatMap { it.subjects }),
            predicate = ProvenanceStatements.runPredicate(run, runUsage(run.runId), jobs.map { it.id.toString() }, clock.now()),
        )
        store(run, AttestationKind.RUN, null, runKey, draft)?.let(created::add)
        return created
    }

    @SystemOperation("invoked by the attestation trigger when the runner reports a finished job")
    override fun attestJob(runId: UUID, jobId: UUID): Attestation? {
        val run = source.find(runId) ?: return null
        val node = run.nodes.lastOrNull { it.runnerJobId == jobId && it.status in FINISHED_NODE } ?: return null
        return attestNode(run, node)
    }

    @SystemOperation("scheduled catch-up sweep, guarded by an advisory lock")
    override fun attestPending(limit: Int): Int =
        repository.runsMissingAttestation(clock.now().minus(lookback), limit).count { runId ->
            attestRun(runId).any { it.kind == AttestationKind.RUN }
        }

    private fun attestNode(run: RunProvenance, node: ProvenanceNode): Attestation? {
        val key = Attestation.jobKey(run.runId, node.executionId)
        if (repository.existsBySubjectKey(key)) return null
        val jobEvidence = node.runnerJobId?.let { runCatching { evidence.find(it) }.getOrNull() }
        val subjects = ProvenanceStatements.jobSubjects(run, node, jobEvidence)
        if (subjects.isEmpty()) return null // nothing to attest: the job produced neither a commit nor a diff
        // Resolved now, i.e. the template version current at attestation time (the job ran moments before).
        val template = node.templateKey?.let { key0 -> runCatching { tasks?.resolve(key0, run.projectId) }.getOrNull() }
        val draft = StatementDraft(
            subjects,
            ProvenanceStatements.jobPredicate(run, node, jobEvidence, jobUsage(run, node), template?.lineage ?: emptyList(), clock.now(), template?.version),
        )
        return store(run, AttestationKind.AGENT_JOB, node, key, draft)
    }

    private fun store(run: RunProvenance, kind: AttestationKind, node: ProvenanceNode?, key: String, draft: StatementDraft): Attestation? {
        val payload = json.encode(draft.toStatement())
        val envelope = signer.signEnvelope(Dsse.IN_TOTO_PAYLOAD_TYPE, payload)
        val attestation = Attestation(
            id = UUID.randomUUID(),
            runId = run.runId,
            projectId = run.projectId,
            kind = kind,
            nodeId = node?.nodeId,
            nodeExecutionId = node?.executionId,
            jobId = node?.runnerJobId,
            subjectKey = key,
            subjects = draft.subjects,
            predicateType = InToto.PREDICATE_AGENT_RUN,
            keyId = envelope.signatures.first().keyId,
            envelope = envelope,
            statementSha256 = Digests.sha256Hex(payload),
            createdAt = clock.now(),
        )
        if (!repository.insertIfAbsent(attestation)) return null
        audit.record(
            AuditEntry(
                operation = "ATTESTATION_CREATE", entityType = "Attestation", entityId = attestation.id.toString(), projectId = run.projectId,
                details = mapOf("kind" to kind.name, "runId" to run.runId.toString(), "nodeId" to node?.nodeId, "keyId" to attestation.keyId),
            ),
        )
        return attestation
    }

    private fun jobUsage(run: RunProvenance, node: ProvenanceNode): LedgerUsage {
        val records = runCatching { ledger.entries(UsageQuery(from = null, to = null, runId = run.runId), 0, MAX_LEDGER_ROWS).content }.getOrDefault(emptyList())
            .filter { it.context.stepId == node.nodeId }
            .filter { r -> node.startedAt?.let { !r.occurredAt.isBefore(it) } ?: true }
            .filter { r -> node.finishedAt?.let { !r.occurredAt.isAfter(it.plus(LEDGER_GRACE)) } ?: true }
        if (records.isEmpty()) return LedgerUsage.NONE
        return LedgerUsage(
            usage = records.fold(TokenUsage.ZERO) { acc, r -> acc + r.usage },
            cost = Money.sum(records.map { it.cost }),
            models = records.groupBy { it.modelRef.label }.entries.sortedByDescending { e -> e.value.sumOf { it.cost.amount } }.map { it.key },
        )
    }

    private fun runUsage(runId: UUID): LedgerUsage = runCatching {
        val summary = ledger.summary(UsageQuery(from = null, to = null, runId = runId), UsageGroupBy.MODEL)
        LedgerUsage(summary.totalUsage, summary.total, summary.groups.sortedByDescending { it.cost.amount }.map { it.label })
    }.getOrDefault(LedgerUsage.NONE)

    companion object {
        private val FINISHED_NODE = setOf("SUCCEEDED", "FAILED")
        private const val MAX_LEDGER_ROWS = 1000
        private val LEDGER_GRACE: Duration = Duration.ofMinutes(5)
    }
}

/** Read side of provenance: attestations of a run, one envelope, offline-style verification, the JWK set. */
class ProvenanceQueryService(
    private val source: RunProvenanceSource,
    private val repository: AttestationRepository,
    private val signer: EnvelopeSigner,
    private val json: JsonCodec,
    private val gate: AuthorizationGate,
) : ProvenanceQueries {

    override fun listForRun(runId: UUID): List<Attestation> {
        val attestations = repository.findByRunId(runId)
        val projectId = attestations.firstOrNull()?.projectId ?: source.find(runId)?.projectId ?: throw NotFoundException("Run", runId)
        gate.require(GovernancePermissions.PROJECT_VIEW, projectId)
        return attestations.sortedWith(compareBy({ it.kind != AttestationKind.AGENT_JOB }, { it.createdAt }))
    }

    override fun get(attestationId: UUID): Attestation {
        val attestation = repository.findById(attestationId) ?: throw NotFoundException("Attestation", attestationId)
        gate.require(GovernancePermissions.PROJECT_VIEW, attestation.projectId)
        return attestation
    }

    @PublicOperation("verification only uses the public keys and the envelope the caller already holds")
    override fun verify(envelope: DsseEnvelope): VerificationResult {
        if (envelope.signatures.isEmpty()) return VerificationResult(false, null, null, "the envelope has no signatures")
        val payload = runCatching { Digests.decodeBase64(envelope.payloadBase64) }.getOrNull()
            ?: return VerificationResult(false, null, null, "payload is not valid base64")
        val validSignature = envelope.signatures.firstOrNull { sig ->
            runCatching { signer.verify(DsseEnvelope(envelope.payloadType, envelope.payloadBase64, listOf(sig))) }.getOrDefault(false)
        }
        val statement = runCatching { json.decodeObject(payload) }.getOrNull()
        if (validSignature == null) {
            val known = signer.publicJwks().mapNotNull { it["kid"]?.toString() }.toSet()
            val reason = if (envelope.signatures.none { it.keyId in known }) "signed with an unknown key" else "signature does not match the payload"
            return VerificationResult(false, null, statement, reason)
        }
        if (envelope.payloadType != Dsse.IN_TOTO_PAYLOAD_TYPE || statement?.get("_type") != InToto.STATEMENT_TYPE) {
            return VerificationResult(true, validSignature.keyId, statement, "signature is valid but the payload is not an in-toto Statement v1")
        }
        return VerificationResult(true, validSignature.keyId, statement, null)
    }

    @PublicOperation("public keys are public (also served unauthenticated at /.well-known/back-office/keys)")
    override fun publicKeys(): List<Map<String, Any?>> = signer.publicJwks()
}
