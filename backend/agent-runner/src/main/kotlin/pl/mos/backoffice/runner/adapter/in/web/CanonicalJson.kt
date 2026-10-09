package pl.mos.backoffice.runner.adapter.`in`.web

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Canonical JSON of the `specDigest` (docs/architecture/10-agent-execution.md, "Assignment verification"):
 * RFC 8785 (JCS) with object members whose value is `null` removed. It works on the plain tree Jackson
 * produces with `convertValue(x, Any::class.java)` (maps, lists, strings, numbers, booleans, null).
 *
 * runner-controller has its own implementation over `JsonNode`; both must reproduce
 * `docs/api/fixtures/spec-digest-vectors.json`, so serialiser settings on either side cannot drift.
 */
object CanonicalJson {

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                // String.compareTo compares UTF-16 code units, as JCS 3.2.3 requires.
                value.entries.filter { it.value != null }.sortedBy { it.key.toString() }.forEach { (k, v) ->
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    append(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> append(sb, value.asList())
            is String -> appendString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(value.toString())
            is BigDecimal -> sb.append(number(value))
            is Number -> sb.append(number(BigDecimal(value.toDouble().toString())))
            else -> appendString(sb, value.toString())
        }
    }

    /** ECMAScript `Number.prototype.toString` form: integers plainly, shortest round-trip otherwise. */
    internal fun number(value: BigDecimal): String {
        if (value.signum() == 0) return "0"
        val stripped = value.stripTrailingZeros()
        if (stripped.scale() <= 0 && stripped.abs() < BigDecimal("1e21")) return stripped.toBigIntegerExact().toString()
        val d = value.toDouble()
        val abs = Math.abs(d)
        if (abs >= 1e-6 && abs < 1e21) return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
        val (mantissa, exp) = d.toString().replace("E", "e").split('e')
        val e = exp.toInt()
        return mantissa.removeSuffix(".0") + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
