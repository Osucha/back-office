package pl.mos.backoffice.runnercontroller.policy

import tools.jackson.databind.JsonNode

enum class Isolation { DEV, STANDARD, HIGH }

/** `SandboxPolicy` of the contract, parsed leniently (unknown fields ignored, every field optional). */
data class SandboxPolicy(
    val isolation: Isolation? = null,
    val cpu: String? = null,
    val memory: String? = null,
    val ephemeralStorage: String? = null,
    val pids: Int? = null,
    val timeoutSeconds: Int? = null,
    val networkAllow: List<String>? = null,
    val toolsAllow: List<String> = emptyList(),
    val toolsDeny: List<String> = emptyList(),
    val toolsAsk: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val image: String? = null,
    val setupCommand: String? = null,
    val caches: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = SandboxPolicy()

        fun from(node: JsonNode?): SandboxPolicy {
            if (node == null || node.isNull || !node.isObject) return EMPTY
            fun str(n: JsonNode?): String? = n?.takeIf { !it.isNull && !it.isMissingNode }?.asString()?.takeIf { it.isNotBlank() }
            fun list(n: JsonNode?): List<String>? = n?.takeIf { it.isArray }?.mapNotNull { str(it) }
            val resources = node.get("resources")
            val tools = node.get("tools")
            val env = node.get("environment")
            return SandboxPolicy(
                isolation = str(node.get("isolation"))?.let { v -> Isolation.entries.firstOrNull { it.name.equals(v, true) } },
                cpu = str(resources?.get("cpu")),
                memory = str(resources?.get("memory")),
                ephemeralStorage = str(resources?.get("ephemeralStorage")),
                pids = resources?.get("pids")?.takeIf { it.isNumber }?.asInt(),
                timeoutSeconds = node.get("timeoutSeconds")?.takeIf { it.isNumber }?.asInt(),
                networkAllow = list(node.get("network")?.get("allow")),
                toolsAllow = list(tools?.get("allow")).orEmpty(),
                toolsDeny = list(tools?.get("deny")).orEmpty(),
                toolsAsk = list(tools?.get("ask")).orEmpty(),
                secrets = list(node.get("secrets")).orEmpty(),
                image = str(env?.get("image")),
                setupCommand = str(env?.get("setupCommand")),
                caches = list(env?.get("caches")).orEmpty(),
            )
        }
    }
}

/** The policy a sandbox is actually rendered with: every field decided, clamped to the pool ceiling. */
data class ResolvedPolicy(
    val isolation: Isolation,
    val image: String,
    val cpu: String,
    val memory: String,
    val ephemeralStorage: String,
    val pids: Int,
    val timeoutSeconds: Int,
    /** Hosts the egress proxy lets this job reach (normalised, no scheme/port/path). */
    val egressHosts: List<String>,
    val toolsAllow: List<String>,
    val toolsDeny: List<String>,
    val toolsAsk: List<String>,
    val secrets: List<String>,
    val caches: List<String>,
    /** Human-readable notes about narrowing that happened (logged and put into the pod annotations). */
    val notes: List<String> = emptyList(),
) {
    /** The shape the runner reads from `policy.json` (same field names as the contract's `SandboxPolicy`). */
    fun toContractMap(): Map<String, Any?> = mapOf(
        "isolation" to isolation.name,
        "resources" to mapOf("cpu" to cpu, "memory" to memory, "ephemeralStorage" to ephemeralStorage, "pids" to pids),
        "timeoutSeconds" to timeoutSeconds,
        "network" to mapOf("allow" to egressHosts),
        "tools" to mapOf("allow" to toolsAllow, "deny" to toolsDeny, "ask" to toolsAsk),
        "secrets" to secrets,
        "environment" to mapOf("image" to image, "caches" to caches),
    )
}
