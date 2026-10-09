package pl.mos.backoffice.governance.adapter.out.json

import pl.mos.backoffice.governance.application.port.out.JsonCodec
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * JSON of statements and predicates. A private mapper (not the application's) so the signed bytes do not change
 * when `spring.jackson.*` settings change: maps keep insertion order, nulls are written.
 */
class JacksonJsonCodec(private val mapper: JsonMapper = JsonMapper.builder().build()) : JsonCodec {

    private val mapType = object : TypeReference<LinkedHashMap<String, Any?>>() {}

    override fun encode(value: Any?): ByteArray = mapper.writeValueAsBytes(value)

    override fun decodeObject(bytes: ByteArray): Map<String, Any?> = mapper.readValue(bytes, mapType)

    fun decodeObject(text: String): Map<String, Any?> = mapper.readValue(text, mapType)

    fun encodeToString(value: Any?): String = mapper.writeValueAsString(value)

    /** Canonical form for digests: object keys sorted recursively, no insignificant whitespace. */
    fun canonical(value: Any?): ByteArray = mapper.writeValueAsBytes(sorted(value))

    private fun sorted(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.associateTo(LinkedHashMap()) { it.key.toString() to sorted(it.value) }
        is List<*> -> value.map(::sorted)
        else -> value
    }
}
