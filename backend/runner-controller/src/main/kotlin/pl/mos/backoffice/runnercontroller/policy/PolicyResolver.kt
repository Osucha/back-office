package pl.mos.backoffice.runnercontroller.policy

import io.fabric8.kubernetes.api.model.Quantity
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import java.math.BigDecimal
import java.net.URI

/** The assignment cannot run here; reported to Back Office as `FAILED` with reason `policy-violation: ...`. */
class PolicyViolation(message: String) : RuntimeException(message)

data class PolicyDefaults(
    val image: String? = null,
    val cpu: String = "2",
    val memory: String = "4Gi",
    val ephemeralStorage: String = "10Gi",
    val pids: Int = 2048,
    val timeoutSeconds: Int = 3600,
)

/**
 * Resolves the effective sandbox policy of one job on this controller (doc 10, "Sandbox policy"). Back Office
 * already merged platform, pool, project, template and node levels; the controller re-applies the pool
 * ceiling it received from `/runner/v2/config` (defence in depth: a compromised control plane still cannot
 * widen what this cluster runs) and fills in local defaults.
 *
 * Narrowing only: resources and timeout are clamped to the ceiling, isolation can only become stricter,
 * `network.allow` entries outside the ceiling's list are dropped, tool deny lists are united. The image must
 * match one of the pool's `allowedImages` patterns (`*` = any sequence) or the job is rejected.
 */
class PolicyResolver(
    private val defaults: PolicyDefaults,
    private val alwaysAllowHosts: List<String> = emptyList(),
) {
    fun resolve(
        requested: SandboxPolicy,
        ceiling: SandboxPolicy,
        allowedImages: List<String>,
        backend: BackendKind,
        specTimeoutSeconds: Int?,
        /** Every repository of the job (legacy `repository.url` or each `repositories[].url`): mandatory egress hosts. */
        repositoryUrls: List<String?>,
        gatewayUrls: List<String?>,
    ): ResolvedPolicy {
        val notes = mutableListOf<String>()

        val image = requested.image ?: ceiling.image ?: defaults.image
            ?: throw PolicyViolation("no image in the policy and no default image configured")
        if (allowedImages.isNotEmpty() && allowedImages.none { globMatches(it, image) }) {
            throw PolicyViolation("image $image is not in the pool's allowedImages")
        }

        var isolation = strictest(requested.isolation, ceiling.isolation) ?: Isolation.STANDARD
        if (isolation == Isolation.DEV && backend != BackendKind.DOCKER) {
            notes += "isolation DEV is only available on the docker backend; raised to STANDARD"
            isolation = Isolation.STANDARD
        }
        if (backend == BackendKind.DOCKER && isolation != Isolation.DEV) {
            notes += "docker backend runs runc: isolation $isolation is not enforced (development only)"
        }

        val cpu = minQuantity("cpu", requested.cpu, ceiling.cpu, defaults.cpu, notes)
        val memory = minQuantity("memory", requested.memory, ceiling.memory, defaults.memory, notes)
        val storage = minQuantity("ephemeralStorage", requested.ephemeralStorage, ceiling.ephemeralStorage, defaults.ephemeralStorage, notes)
        val pids = listOfNotNull(requested.pids ?: defaults.pids, ceiling.pids).min()
        val timeout = listOfNotNull(requested.timeoutSeconds ?: specTimeoutSeconds ?: defaults.timeoutSeconds, ceiling.timeoutSeconds).min()

        val requestedHosts = (requested.networkAllow ?: ceiling.networkAllow ?: emptyList()).mapNotNull { raw ->
            normaliseHostPattern(raw).also { if (it == null) notes += "network.allow entry '$raw' is not a valid host pattern; dropped" }
        }
        val ceilingHosts = ceiling.networkAllow?.mapNotNull { normaliseHostPattern(it) }
        val permitted = requestedHosts.filter { host ->
            val ok = ceilingHosts == null || ceilingHosts.any { patternCovers(it, host) }
            if (!ok) notes += "network.allow entry '$host' is outside the pool ceiling; dropped"
            ok
        }
        val mandatory = (gatewayUrls + repositoryUrls).mapNotNull { it?.let(::hostOfUrl) } + alwaysAllowHosts.mapNotNull { normaliseHostPattern(it) }
        val egress = (mandatory + permitted).distinct()

        val secrets = if (ceiling.secrets.isEmpty()) requested.secrets else requested.secrets.filter { it in ceiling.secrets }
        val caches = if (ceiling.caches.isEmpty()) requested.caches else requested.caches.filter { it in ceiling.caches }

        return ResolvedPolicy(
            isolation = isolation,
            image = image,
            cpu = cpu,
            memory = memory,
            ephemeralStorage = storage,
            pids = pids,
            timeoutSeconds = timeout,
            egressHosts = egress,
            toolsAllow = requested.toolsAllow.ifEmpty { ceiling.toolsAllow },
            toolsDeny = (ceiling.toolsDeny + requested.toolsDeny).distinct(),
            toolsAsk = (ceiling.toolsAsk + requested.toolsAsk).distinct(),
            secrets = secrets,
            caches = caches,
            notes = notes,
        )
    }

    private fun minQuantity(name: String, requested: String?, ceiling: String?, default: String, notes: MutableList<String>): String {
        val wanted = requested ?: ceiling ?: default
        if (ceiling == null) return wanted
        return if (amount(wanted) > amount(ceiling)) {
            notes += "$name $wanted exceeds the pool ceiling $ceiling; clamped"
            ceiling
        } else {
            wanted
        }
    }

    private fun amount(q: String): BigDecimal = try {
        Quantity.getAmountInBytes(Quantity(q))
    } catch (e: IllegalArgumentException) {
        throw PolicyViolation("invalid quantity '$q': ${e.message}")
    }

    companion object {
        private val ORDER = listOf(Isolation.DEV, Isolation.STANDARD, Isolation.HIGH)

        fun strictest(a: Isolation?, b: Isolation?): Isolation? = listOfNotNull(a, b).maxByOrNull { ORDER.indexOf(it) }

        /** Image pattern match: `*` matches any sequence, everything else literally. */
        fun globMatches(pattern: String, value: String): Boolean {
            val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex("^$regex$").matches(value)
        }

        /**
         * `host[:port]`, `https://host/path` or `*.domain` -> lowercase host without scheme, port, path or
         * trailing dot. Smokescreen ACLs are host based, so ports are dropped. Only a leading `*.` wildcard
         * is valid (Smokescreen's rule); anything else returns null.
         */
        fun normaliseHostPattern(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            s = s.substringAfter("://")
            s = s.substringBefore('/').substringBefore('?')
            s = s.substringAfter('@')
            if (s.startsWith("[")) return s.substringBefore(']').removePrefix("[").takeIf { it.isNotEmpty() }
            if (s.count { it == ':' } == 1) s = s.substringBefore(':')
            s = s.trimEnd('.')
            if (s.isEmpty() || s == "*") return null
            val body = s.removePrefix("*.")
            if ('*' in body) return null
            if (!Regex("^[a-z0-9.-]+$").matches(body) || body.startsWith(".") || body.contains("..")) return null
            return s
        }

        fun hostOfUrl(url: String): String? = runCatching { URI.create(url.trim()).host?.lowercase() }.getOrNull()
            ?: normaliseHostPattern(url)

        /** Does ceiling pattern [outer] allow requested pattern [inner]? */
        fun patternCovers(outer: String, inner: String): Boolean {
            if (outer == inner) return true
            if (!outer.startsWith("*.")) return false
            val suffix = outer.removePrefix("*")
            return inner.removePrefix("*").endsWith(suffix) && inner.removePrefix("*.") != outer.removePrefix("*.")
        }
    }
}
