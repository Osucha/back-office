package pl.mos.backoffice.governance.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.governance.adapter.out.json.DsseJson
import pl.mos.backoffice.governance.adapter.out.json.JacksonJsonCodec
import pl.mos.backoffice.governance.application.port.out.AttestationRepository
import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.AttestationKind
import pl.mos.backoffice.governance.domain.model.AttestationSubject
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `attestation` table (V550). Envelopes are stored in their DSSE JSON form
 * (`{payloadType, payload, signatures: [{keyid, sig}]}`), which is also what the API returns.
 * [runsMissingAttestation] reads `workflow_run` of the Projects context (a read model query, like insights).
 */
@Transactional
class JdbcAttestationRepository(private val jdbc: JdbcClient, private val json: JacksonJsonCodec) : AttestationRepository {

    override fun insertIfAbsent(attestation: Attestation): Boolean = jdbc.sql(
        """
        INSERT INTO attestation (id, run_id, project_id, kind, node_id, node_execution_id, job_id, subject_key, subjects,
                                 predicate_type, key_id, envelope, statement_sha256, created_at)
        VALUES (:id, :runId, :projectId, :kind, :nodeId, :nodeExecutionId, :jobId, :subjectKey, CAST(:subjects AS jsonb),
                :predicateType, :keyId, CAST(:envelope AS jsonb), :statementSha256, :createdAt)
        ON CONFLICT (subject_key) DO NOTHING
        """.trimIndent(),
    )
        .param("id", attestation.id)
        .param("runId", attestation.runId)
        .param("projectId", attestation.projectId)
        .param("kind", attestation.kind.name)
        .param("nodeId", attestation.nodeId)
        .param("nodeExecutionId", attestation.nodeExecutionId)
        .param("jobId", attestation.jobId)
        .param("subjectKey", attestation.subjectKey)
        .param("subjects", json.encodeToString(attestation.subjects.map { it.toMap() }))
        .param("predicateType", attestation.predicateType)
        .param("keyId", attestation.keyId)
        .param("envelope", json.encodeToString(DsseJson.toJson(attestation.envelope)))
        .param("statementSha256", attestation.statementSha256)
        .param("createdAt", Timestamp.from(attestation.createdAt))
        .update() == 1

    @Transactional(readOnly = true)
    override fun findById(id: UUID): Attestation? =
        jdbc.sql("SELECT * FROM attestation WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    @Transactional(readOnly = true)
    override fun findByRunId(runId: UUID): List<Attestation> =
        jdbc.sql("SELECT * FROM attestation WHERE run_id = :runId ORDER BY created_at, id").param("runId", runId).query(::map).list()

    @Transactional(readOnly = true)
    override fun existsBySubjectKey(subjectKey: String): Boolean =
        jdbc.sql("SELECT count(*) FROM attestation WHERE subject_key = :key").param("key", subjectKey).query(Long::class.java).single() > 0

    @Transactional(readOnly = true)
    override fun runsMissingAttestation(since: Instant, limit: Int): List<UUID> = jdbc.sql(
        """
        SELECT r.id FROM workflow_run r
         WHERE r.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND r.finished_at >= :since
           AND NOT EXISTS (SELECT 1 FROM attestation a WHERE a.subject_key = 'RUN:' || r.id::text)
         ORDER BY r.finished_at
         LIMIT :limit
        """.trimIndent(),
    ).param("since", Timestamp.from(since)).param("limit", limit).query(UUID::class.java).list().filterNotNull()

    @Suppress("UNUSED_PARAMETER")
    private fun map(rs: ResultSet, row: Int): Attestation {
        val subjects = (json.decodeObject("""{"s":${rs.getString("subjects")}}""")["s"] as List<*>).map { s ->
            val m = s as Map<*, *>
            AttestationSubject(m["name"].toString(), (m["digest"] as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() })
        }
        return Attestation(
            id = rs.getObject("id", UUID::class.java),
            runId = rs.getObject("run_id", UUID::class.java),
            projectId = rs.getObject("project_id", UUID::class.java),
            kind = AttestationKind.valueOf(rs.getString("kind")),
            nodeId = rs.getString("node_id"),
            nodeExecutionId = rs.getObject("node_execution_id", UUID::class.java),
            jobId = rs.getObject("job_id", UUID::class.java),
            subjectKey = rs.getString("subject_key"),
            subjects = subjects,
            predicateType = rs.getString("predicate_type"),
            keyId = rs.getString("key_id"),
            envelope = DsseJson.fromJson(json.decodeObject(rs.getString("envelope"))),
            statementSha256 = rs.getString("statement_sha256"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
