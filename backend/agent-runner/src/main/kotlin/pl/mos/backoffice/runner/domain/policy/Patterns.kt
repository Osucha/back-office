package pl.mos.backoffice.runner.domain.policy

import java.math.BigDecimal

/** Kubernetes resource quantities, just enough to compare them (`500m`, `2`, `1.5`, `4Gi`, `512M`, `1073741824`). */
object Quantities {
    private val CPU = Regex("^([0-9]+(?:\\.[0-9]+)?)(m?)$")
    private val BYTES = Regex("^([0-9]+(?:\\.[0-9]+)?)(Ki|Mi|Gi|Ti|Pi|Ei|k|K|M|G|T|P|E)?$")

    /** Millicores, or null when [value] is not a CPU quantity. */
    fun cpuMillis(value: String): Long? {
        val m = CPU.matchEntire(value.trim()) ?: return null
        val number = BigDecimal(m.groupValues[1])
        return if (m.groupValues[2] == "m") number.toLong() else number.multiply(BigDecimal(1000)).toLong()
    }

    /** Bytes, or null when [value] is not a memory/storage quantity. */
    fun bytes(value: String): Long? {
        val m = BYTES.matchEntire(value.trim()) ?: return null
        val factor: Long = when (m.groupValues[2]) {
            "" -> 1L
            "Ki" -> 1L shl 10
            "Mi" -> 1L shl 20
            "Gi" -> 1L shl 30
            "Ti" -> 1L shl 40
            "Pi" -> 1L shl 50
            "Ei" -> 1L shl 60
            "k", "K" -> 1_000L
            "M" -> 1_000_000L
            "G" -> 1_000_000_000L
            "T" -> 1_000_000_000_000L
            "P" -> 1_000_000_000_000_000L
            else -> 1_000_000_000_000_000_000L
        }
        return BigDecimal(m.groupValues[1]).multiply(BigDecimal(factor)).toLong()
    }
}

/**
 * Egress allowlist patterns `host[:port]` (openapi `SandboxPolicy.network.allow`): `*` stands for exactly one
 * DNS label (`*.gradle.org` matches `plugins.gradle.org`, not `a.b.gradle.org`); without a port any port is
 * allowed. The Smokescreen sidecar applies the same semantics.
 */
object HostPatterns {
    private val LABEL = Regex("^(\\*|[a-z0-9]([a-z0-9-]*[a-z0-9])?)$")

    data class HostPattern(val labels: List<String>, val port: Int?)

    fun parse(pattern: String): HostPattern? {
        val text = pattern.trim().lowercase()
        if (text.isEmpty() || text.contains('/') || text.contains('@')) return null
        val hostPart: String
        val port: Int?
        val colon = text.lastIndexOf(':')
        if (colon >= 0) {
            hostPart = text.substring(0, colon)
            port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            hostPart = text
            port = null
        }
        val labels = hostPart.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        return HostPattern(labels, port)
    }

    fun isValid(pattern: String): Boolean = parse(pattern) != null

    /** True when every host:port matched by [inner] is also matched by [outer]. */
    fun covers(outer: String, inner: String): Boolean {
        val o = parse(outer) ?: return false
        val i = parse(inner) ?: return false
        if (o.labels.size != i.labels.size) return false
        if (o.port != null && o.port != i.port) return false
        return o.labels.zip(i.labels).all { (ol, il) -> ol == "*" || ol == il }
    }

    /** Pattern-set intersection: the entries of either list that the other list covers. */
    fun intersect(upper: List<String>, lower: List<String>): List<String> =
        (lower.filter { l -> upper.any { covers(it, l) } } + upper.filter { u -> lower.any { covers(it, u) } })
            .map { it.trim().lowercase() }.distinct()

    /** The host of an http(s) or ssh-style git URL (`https://gitlab.example.com/g/p.git`, `git@host:g/p.git`). */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val text = url.trim()
        val afterScheme = text.substringAfter("://", text)
        val authority = afterScheme.substringBefore('/').substringAfterLast('@')
        val host = if ("://" in text) authority else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotBlank() && parse(it) != null }
    }
}

/** Glob patterns with `*` = any sequence (tool command patterns, image references). */
object GlobPatterns {
    fun toRegex(glob: String): Regex =
        Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })

    /** [value] matched literally against [glob]. */
    fun matches(glob: String, value: String): Boolean = toRegex(glob.trim()).matches(value.trim())

    /** Every string matched by [inner] is matched by [outer] (approximation: [inner] read literally). */
    fun covers(outer: String, inner: String): Boolean = matches(outer, inner)

    fun intersect(upper: List<String>, lower: List<String>): List<String> =
        (lower.filter { l -> upper.any { covers(it, l) } } + upper.filter { u -> lower.any { covers(it, u) } })
            .map { it.trim() }.distinct()
}
