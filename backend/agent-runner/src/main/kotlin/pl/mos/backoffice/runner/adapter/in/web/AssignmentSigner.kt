package pl.mos.backoffice.runner.adapter.`in`.web

import org.slf4j.LoggerFactory
import pl.mos.backoffice.runner.RunnerJobSpecJson
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.application.port.`in`.LeasedJob
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.shared.platform.EnvelopeSigner
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Builds signed assignments (docs/architecture/10-agent-execution.md): `specDigest` is the SHA-256 (hex) of
 * the [CanonicalJson] (RFC 8785, null members dropped) of `{"policy":…,"spec":…}`, and the
 * detached Ed25519 signature of the shared [EnvelopeSigner] covers `jobId|specDigest|leaseExpiresAt`.
 * Without a signer (development) assignments go out unsigned; production controllers reject them.
 */
class AssignmentSigner(private val json: JsonMapper, private val signer: () -> EnvelopeSigner?) {

    private val log = LoggerFactory.getLogger(AssignmentSigner::class.java)

    @Volatile private var warned = false

    fun assignment(job: LeasedJob): AgentJobAssignmentJson {
        val spec = canonical(
            RunnerJobSpecJson.of(job.spec).copy(
                gateway = job.gateway.toJson(),
                // ADR 0040: only the non-default value is sent, so gateway assignments keep their digests
                llmCredentials = job.llmCredentials.takeIf { it != LlmCredentials.GATEWAY }?.name,
                personalRunnerOf = null,
            ),
        )
        val policy = canonical(SandboxPolicyJson.of(job.policy))
        val digest = specDigest(spec, policy)
        val expires = job.leaseExpiresAt.toString()
        val signature = signer()?.sign("${job.jobId}|$digest|$expires".toByteArray())
        if (signature == null && !warned) {
            warned = true
            log.warn("no EnvelopeSigner configured: runner assignments are sent unsigned (development only)")
        }
        return AgentJobAssignmentJson(job.jobId.value, expires, job.jobToken, spec, policy, digest, signature?.signature, signature?.keyId)
    }

    /** Plain maps/lists with sorted keys and without null values. */
    fun canonical(value: Any?): Any? = sortTree(json.convertValue(value, Any::class.java))

    private fun sortTree(node: Any?): Any? = when (node) {
        is Map<*, *> -> TreeMap<String, Any?>().apply {
            node.forEach { (k, v) -> if (v != null) put(k.toString(), sortTree(v)) }
        }
        is List<*> -> node.map(::sortTree)
        else -> node
    }

    companion object {
        /** SHA-256 (hex) of the [CanonicalJson] of `{"policy", "spec"}`; a null policy is left out. */
        fun specDigest(spec: Any?, policy: Any?): String =
            sha256(CanonicalJson.write(mapOf("policy" to policy, "spec" to spec)).toByteArray(Charsets.UTF_8))

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
