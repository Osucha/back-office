package pl.mos.backoffice.governance.adapter.out.evidence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.governance.adapter.out.json.JacksonJsonCodec
import pl.mos.backoffice.governance.application.port.out.JobEvidenceSource
import pl.mos.backoffice.governance.domain.model.JobEvidence
import pl.mos.backoffice.governance.domain.service.Digests
import java.time.Instant
import java.util.UUID

/**
 * Reads what the execution plane stored about a job, schema-agnostically: the row of `agent_job` (runner v2,
 * ADR 0024) or `runner_job` (v1) is fetched with `row_to_json`, and the spec / result documents are searched
 * for the fields the predicate needs (`model`, `prompt`, `skills`, `verifyCommand`, `policy`, `commitSha`,
 * `diff`, `verification.exitCode`, image and egress digests). Unknown tables or columns simply yield fewer
 * fields: the attestation still records what the run knows. Contents (prompts, diffs) are reduced to SHA-256.
 */
class JdbcJobEvidenceSource(private val jdbc: JdbcClient, private val json: JacksonJsonCodec) : JobEvidenceSource {

    private val log = LoggerFactory.getLogger(JdbcJobEvidenceSource::class.java)

    override fun find(jobId: UUID): JobEvidence? {
        for (table in TABLES) {
            if (!tableExists(table)) continue
            val row = runCatching {
                jdbc.sql("SELECT row_to_json(t)::text FROM $table t WHERE t.id = :id").param("id", jobId).query(String::class.java).optional().orElse(null)
            }.onFailure { log.debug("job evidence query on {} failed: {}", table, it.toString()) }.getOrNull() ?: continue
            return toEvidence(jobId, json.decodeObject(row))
        }
        return null
    }

    private fun tableExists(table: String): Boolean =
        jdbc.sql("SELECT to_regclass(:name) IS NOT NULL").param("name", table).query(Boolean::class.java).single()

    internal fun toEvidence(jobId: UUID, row: Map<String, Any?>): JobEvidence {
        val spec = document(row["spec"])
        val result = document(row["result"])
        val completion = document(row["completion"]) + document(row["digests"])
        val model = spec["model"] as? Map<*, *>
        val options = model?.get("options") as? Map<*, *>
        val policy = (spec["policy"] ?: spec["sandboxPolicy"] ?: row["policy"])?.let(::document)?.takeIf { it.isNotEmpty() }
        val network = policy?.get("network") as? Map<*, *>
        val allowed = (policy?.get("networkAllow") ?: network?.get("allow")) as? List<*>
        val environment = policy?.get("environment") as? Map<*, *>
        val verification = result["verification"] as? Map<*, *>
        val skills = (spec["skills"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .filter { it["key"] != null }
            .associate { it["key"].toString() to Digests.sha256Hex(it["content"]?.toString() ?: "") }
        val diff = result["diff"]?.toString()?.takeIf { it.isNotBlank() }
        return JobEvidence(
            jobId = jobId,
            status = (result["status"] ?: row["status"])?.toString(),
            agent = (spec["agent"] ?: row["agent"])?.toString(),
            modelRef = model?.let { m -> m["label"]?.toString() ?: "${m["provider"].toString().lowercase()}/${m["modelId"]}" } ?: row["model_label"]?.toString(),
            effort = options?.get("reasoning")?.toString(),
            promptSha256 = spec["prompt"]?.toString()?.let(Digests::sha256Hex),
            constitutionSha256 = spec["constitution"]?.toString()?.takeIf { it.isNotBlank() }?.let(Digests::sha256Hex),
            skillSha256 = skills,
            verifyCommand = spec["verifyCommand"]?.toString() ?: verification?.get("command")?.toString(),
            verificationExitCode = (verification?.get("exitCode") as? Number)?.toInt(),
            commitSha = result["commitSha"]?.toString()?.takeIf { it.isNotBlank() },
            branch = result["branch"]?.toString(),
            diffSha256 = diff?.let(Digests::sha256Hex) ?: text(result, completion, "diffSha256", "diffDigest"),
            mergeRequestUrl = result["mergeRequestUrl"]?.toString(),
            policyDigest = policy?.let { Digests.sha256Hex(json.canonical(it)) },
            isolation = policy?.get("isolation")?.toString(),
            image = environment?.get("image")?.toString() ?: row["image"]?.toString(),
            imageDigest = text(result, completion, "imageDigest") ?: row["image_digest"]?.toString(),
            allowedHosts = allowed.orEmpty().map { it.toString() },
            egressLogDigest = text(result, completion, "egressLogDigest") ?: row["egress_log_digest"]?.toString(),
            startedAt = instant(row["started_at"]),
            finishedAt = instant(row["finished_at"]) ?: instant(result["finishedAt"]),
        )
    }

    private fun text(a: Map<String, Any?>, b: Map<String, Any?>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (a[k] ?: b[k] ?: (a["digests"] as? Map<*, *>)?.get(k))?.toString()?.takeIf { it.isNotBlank() } }

    /** jsonb columns arrive as objects, text columns holding JSON as strings. */
    @Suppress("UNCHECKED_CAST")
    private fun document(value: Any?): Map<String, Any?> = when (value) {
        null -> emptyMap()
        is Map<*, *> -> value as Map<String, Any?>
        is String -> runCatching { json.decodeObject(value) }.getOrDefault(emptyMap())
        else -> emptyMap()
    }

    private fun instant(value: Any?): Instant? = when (value) {
        null -> null
        is Number -> Instant.ofEpochMilli((value.toDouble() * 1000).toLong())
        else -> runCatching { Instant.parse(value.toString()) }
            .recoverCatching { java.time.OffsetDateTime.parse(value.toString()).toInstant() }
            .getOrNull()
    }

    companion object {
        /** v2 first: after the runner migration both may exist while old jobs age out. */
        val TABLES = listOf("agent_job", "runner_job")
    }
}
