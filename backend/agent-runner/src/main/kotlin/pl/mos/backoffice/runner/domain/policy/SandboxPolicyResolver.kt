package pl.mos.backoffice.runner.domain.policy

import pl.mos.backoffice.shared.runner.SandboxEnvironment
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.runner.SandboxTools
import java.math.BigDecimal

/** Where a policy layer comes from, top (widest) to bottom (narrowest). `TEMPLATE` is the policy on the job spec. */
enum class PolicyLevel { PLATFORM, POOL, PROJECT, TEMPLATE, NODE }

data class PolicyLayer(val level: PolicyLevel, val policy: SandboxPolicy)

/**
 * Outcome of a resolution. [errors] make the policy unusable for the pool (e.g. an image outside the pool's
 * allowlist, contradicting pool selectors, unparseable quantities); [warnings] record requests that were
 * narrowed away (a secret the pool does not allow). [sources] maps field paths to the level that set them.
 */
data class ResolvedPolicy(
    val policy: SandboxPolicy,
    val sources: Map<String, String>,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Narrowing-only merge of sandbox policies (ADR 0024, docs/architecture/10-agent-execution.md "Sandbox policy").
 * Layers are applied top to bottom; a field left empty at a level inherits, and a lower level can only
 * restrict what the levels above allow:
 *
 * | field | rule |
 * |---|---|
 * | `isolation` | strongest wins (DEV < STANDARD < HIGH) |
 * | `resources.*`, `timeoutSeconds`, `maxCostUsd`, `maxTokens` | minimum |
 * | `network.allow` | pattern intersection with every upper level, then the mandatory hosts (LLM gateway, git host) |
 * | `tools.allow`, `environment.caches` | pattern / set intersection |
 * | `tools.deny`, `tools.ask` | union (ask entries that are denied are dropped) |
 * | `secrets` | intersection of the requested lists, then subset of the pool's allowed secrets (the POOL layer's list) |
 * | `environment.image`, `environment.setupCommand` | most specific level wins; the image must match the pool's `allowedImages` |
 * | `poolSelector` | union; the same key with different values is an error |
 */
class SandboxPolicyResolver {

    /**
     * @param allowedImages the pool's image patterns; null when no pool is known (effective view of a project
     *   without a pool): the image is then not checked. Empty = only images set by PLATFORM or POOL.
     * @param mandatoryHosts always allowed egress (LLM gateway / API host, git host), never narrowed away.
     */
    fun resolve(layers: List<PolicyLayer>, allowedImages: List<String>? = null, mandatoryHosts: List<String> = emptyList()): ResolvedPolicy {
        val ordered = layers.sortedBy { it.level.ordinal }
        val sources = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // isolation: strongest wins
        var isolation: SandboxIsolation? = null
        ordered.forEach { layer ->
            val requested = layer.policy.isolation ?: return@forEach
            if (isolation == null || strength(requested) > strength(isolation!!)) {
                isolation = requested
                sources["isolation"] = layer.level.name
            }
        }

        // resources, timeout, caps: minimum
        val cpu = minimum(ordered, "resources.cpu", sources, errors, { it.resources.cpu }) { Quantities.cpuMillis(it)?.let(BigDecimal::valueOf) }
        val memory = minimum(ordered, "resources.memory", sources, errors, { it.resources.memory }) { Quantities.bytes(it)?.let(BigDecimal::valueOf) }
        val storage = minimum(ordered, "resources.ephemeralStorage", sources, errors, { it.resources.ephemeralStorage }) { Quantities.bytes(it)?.let(BigDecimal::valueOf) }
        val pids = minimum(ordered, "resources.pids", sources, errors, { it.resources.pids }) { BigDecimal.valueOf(it.toLong()) }?.first
        val timeout = minimum(ordered, "timeoutSeconds", sources, errors, { it.timeoutSeconds }) { BigDecimal.valueOf(it.toLong()) }?.first
        val maxCost = minimum(ordered, "maxCostUsd", sources, errors, { it.maxCostUsd }) { it }?.first
        val maxTokens = minimum(ordered, "maxTokens", sources, errors, { it.maxTokens }) { BigDecimal.valueOf(it) }?.first
        listOfNotNull(pids?.toLong(), timeout?.toLong(), maxTokens).filter { it <= 0 }.forEach { errors += "limits must be positive (got $it)" }
        if (maxCost != null && maxCost.signum() < 0) errors += "maxCostUsd must not be negative"

        // network: intersection, then mandatory hosts
        ordered.flatMap { l -> l.policy.networkAllow.filterNot(HostPatterns::isValid).map { "network.allow: invalid pattern '$it' (${l.level})" } }.let(errors::addAll)
        val network = narrowList(ordered, "network.allow", sources, { it.networkAllow }, HostPatterns::intersect)
        val mandatory = mandatoryHosts.map { it.lowercase() }.filter(HostPatterns::isValid)
        val networkAllow = (network + mandatory.filterNot { m -> network.any { HostPatterns.covers(it, m) } }).distinct()

        // tools
        val deny = union(ordered, "tools.deny", sources) { it.tools.deny }
        val ask = union(ordered, "tools.ask", sources) { it.tools.ask }.filterNot { it in deny }
        val allow = narrowList(ordered, "tools.allow", sources, { it.tools.allow }, GlobPatterns::intersect).filterNot { it in deny }

        // secrets: what the job asks for, limited to what the pool (and platform) allow
        val secrets = resolveSecrets(ordered, sources, warnings)

        // environment
        val image = mostSpecific(ordered, "environment.image", sources) { it.environment.image?.takeIf(String::isNotBlank) }
        val setup = mostSpecific(ordered, "environment.setupCommand", sources) { it.environment.setupCommand?.takeIf(String::isNotBlank) }
        val caches = narrowList(ordered, "environment.caches", sources, { it.environment.caches }) { upper, lower -> lower.filter { it in upper } }
        if (image != null && allowedImages != null) checkImage(image, allowedImages, ordered)?.let(errors::add)

        // pool selector: union, conflicts are errors
        val selector = linkedMapOf<String, String>()
        ordered.forEach { layer ->
            layer.policy.poolSelector.forEach { (k, v) ->
                val existing = selector[k]
                if (existing != null && existing != v) {
                    errors += "poolSelector: '$k' is '$existing' at a higher level but '$v' at ${layer.level}"
                } else if (existing == null) {
                    selector[k] = v
                    sources["poolSelector.$k"] = layer.level.name
                }
            }
        }

        val policy = SandboxPolicy(
            isolation = isolation,
            resources = SandboxResources(cpu?.first, memory?.first, storage?.first, pids),
            timeoutSeconds = timeout,
            networkAllow = networkAllow,
            tools = SandboxTools(allow = allow, deny = deny, ask = ask),
            secrets = secrets,
            environment = SandboxEnvironment(image = image, setupCommand = setup, caches = caches),
            maxCostUsd = maxCost,
            maxTokens = maxTokens,
            poolSelector = selector,
        )
        return ResolvedPolicy(policy, sources, warnings, errors.distinct())
    }

    /** Validation of a policy stored at one level (e.g. the project policy on PUT): syntax only. */
    fun validate(policy: SandboxPolicy): List<String> =
        resolve(listOf(PolicyLayer(PolicyLevel.PROJECT, policy))).errors

    private fun strength(isolation: SandboxIsolation): Int = when (isolation) {
        SandboxIsolation.DEV -> 0
        SandboxIsolation.STANDARD -> 1
        SandboxIsolation.HIGH -> 2
    }

    /**
     * Minimum of an optional scalar across levels; returns (original value, comparable number). The first
     * level that reaches the minimum is the source (a lower level repeating the value did not narrow it).
     */
    private fun <T : Any> minimum(
        ordered: List<PolicyLayer>,
        field: String,
        sources: MutableMap<String, String>,
        errors: MutableList<String>,
        read: (SandboxPolicy) -> T?,
        numeric: (T) -> BigDecimal?,
    ): Pair<T, BigDecimal>? {
        var best: Pair<T, BigDecimal>? = null
        ordered.forEach { layer ->
            val value = read(layer.policy) ?: return@forEach
            val number = numeric(value)
            if (number == null) {
                errors += "$field: cannot parse '$value' (${layer.level})"
                return@forEach
            }
            if (best == null || number < best!!.second) {
                best = value to number
                sources[field] = layer.level.name
            }
        }
        return best
    }

    private fun narrowList(
        ordered: List<PolicyLayer>,
        field: String,
        sources: MutableMap<String, String>,
        read: (SandboxPolicy) -> List<String>,
        intersect: (List<String>, List<String>) -> List<String>,
    ): List<String> {
        var current: List<String>? = null
        ordered.forEach { layer ->
            val values = read(layer.policy).map(String::trim).filter(String::isNotEmpty)
            if (values.isEmpty()) return@forEach
            current = current?.let { intersect(it, values) } ?: values.distinct()
            sources[field] = layer.level.name
        }
        return current ?: emptyList()
    }

    private fun union(ordered: List<PolicyLayer>, field: String, sources: MutableMap<String, String>, read: (SandboxPolicy) -> List<String>): List<String> {
        val result = linkedSetOf<String>()
        ordered.forEach { layer ->
            val values = read(layer.policy).map(String::trim).filter(String::isNotEmpty)
            if (values.isEmpty()) return@forEach
            result += values
            sources[field] = layer.level.name
        }
        return result.toList()
    }

    private fun <T : Any> mostSpecific(ordered: List<PolicyLayer>, field: String, sources: MutableMap<String, String>, read: (SandboxPolicy) -> T?): T? {
        var value: T? = null
        ordered.forEach { layer ->
            read(layer.policy)?.let {
                value = it
                sources[field] = layer.level.name
            }
        }
        return value
    }

    private fun resolveSecrets(ordered: List<PolicyLayer>, sources: MutableMap<String, String>, warnings: MutableList<String>): List<String> {
        val requestedLayers = ordered.filter { it.level.ordinal > PolicyLevel.POOL.ordinal && it.policy.secrets.isNotEmpty() }
        if (requestedLayers.isEmpty()) return emptyList()
        var requested: List<String> = requestedLayers.first().policy.secrets.distinct()
        requestedLayers.drop(1).forEach { layer -> requested = requested.filter { it in layer.policy.secrets } }
        sources["secrets"] = requestedLayers.last().level.name
        val allowedBy = ordered.filter { it.level.ordinal <= PolicyLevel.POOL.ordinal }
        val pool = allowedBy.firstOrNull { it.level == PolicyLevel.POOL }
        val platform = allowedBy.firstOrNull { it.level == PolicyLevel.PLATFORM }?.policy?.secrets.orEmpty()
        return requested.filter { name ->
            val allowedByPool = pool == null || name in pool.policy.secrets
            val allowedByPlatform = platform.isEmpty() || name in platform
            if (!allowedByPool) warnings += "secret '$name' is not allowed by the pool and was dropped"
            if (!allowedByPlatform) warnings += "secret '$name' is not allowed by the platform and was dropped"
            allowedByPool && allowedByPlatform
        }
    }

    private fun checkImage(image: String, allowedImages: List<String>, ordered: List<PolicyLayer>): String? {
        if (allowedImages.isNotEmpty()) {
            return if (allowedImages.any { GlobPatterns.matches(it, image) }) null
            else "environment.image '$image' does not match the pool's allowed images ${allowedImages.joinToString()}"
        }
        val trusted = ordered.filter { it.level.ordinal <= PolicyLevel.POOL.ordinal }.mapNotNull { it.policy.environment.image }
        return if (image in trusted) null else "environment.image '$image' is not allowed: the pool has no allowed image patterns"
    }
}
