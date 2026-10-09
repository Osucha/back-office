package pl.mos.backoffice.runnercontroller.security

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Canonical JSON used for `AgentJobAssignment.specDigest` (docs/architecture/10-agent-execution.md,
 * "Assignment verification"). It is RFC 8785 (JCS) with one addition:
 *
 * 1. object members whose value is `null` are removed (so "absent" and "null" hash the same, whatever
 *    serialisation inclusion either side uses); `null` array elements are kept;
 * 2. object members are sorted by key, comparing UTF-16 code units (JCS 3.2.3);
 * 3. no insignificant whitespace;
 * 4. strings are escaped minimally: `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, other code points below
 *    U+0020 as `\u00xx` (lowercase hex); everything else literally;
 * 5. numbers: integers as plain decimal digits; other numbers in the ECMAScript `Number.prototype.toString`
 *    form JCS prescribes (shortest round-trip, exponent only below 1e-6 or from 1e21);
 * 6. the result is encoded as UTF-8.
 *
 * The digest input is the object `{"policy": <policy>, "spec": <spec>}` (policy omitted when absent) and
 * the digest is the lowercase hex SHA-256 of those bytes, optionally prefixed with `sha256:`.
 */
object CanonicalJson {

    fun write(node: JsonNode?): String = StringBuilder().also { append(it, node) }.toString()

    fun specDigestInput(spec: JsonNode, policy: JsonNode?): ObjectNode {
        val root = JsonNodeFactory.instance.objectNode()
        if (policy != null && !policy.isNull) root.set("policy", policy)
        root.set("spec", spec)
        return root
    }

    fun specDigest(spec: JsonNode, policy: JsonNode?): String = sha256Hex(write(specDigestInput(spec, policy)).toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun append(sb: StringBuilder, node: JsonNode?) {
        when {
            node == null || node.isNull || node.isMissingNode -> sb.append("null")
            node.isObject -> {
                sb.append('{')
                var first = true
                node.propertyNames().sorted().forEach { key ->
                    val value = node.get(key)
                    if (value == null || value.isNull) return@forEach
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, value)
                }
                sb.append('}')
            }
            node.isArray -> {
                sb.append('[')
                node.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    append(sb, v)
                }
                sb.append(']')
            }
            node.isString -> appendString(sb, node.stringValue())
            node.isBoolean -> sb.append(if (node.booleanValue()) "true" else "false")
            node.isIntegralNumber -> sb.append(node.bigIntegerValue().toString())
            node.isNumber -> sb.append(ecmaNumber(node.decimalValue()))
            else -> appendString(sb, node.asString())
        }
    }

    internal fun ecmaNumber(value: BigDecimal): String {
        if (value.signum() == 0) return "0"
        val stripped = value.stripTrailingZeros()
        if (stripped.scale() <= 0 && stripped.abs() < BigDecimal("1e21")) return stripped.toBigIntegerExact().toString()
        val d = value.toDouble()
        val abs = Math.abs(d)
        if (abs >= 1e-6 && abs < 1e21) {
            // Shortest round-trip digits, plain notation.
            return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
        }
        // Exponent form: d.ddde+XX / d.ddde-XX
        val s = d.toString().replace("E", "e") // Java: 1.0E-7 / 1.0E21
        val (mantissa, exp) = s.split('e')
        val m = mantissa.removeSuffix(".0")
        val e = exp.toInt()
        return m + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
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
