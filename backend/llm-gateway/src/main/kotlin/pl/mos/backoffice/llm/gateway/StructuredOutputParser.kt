package pl.mos.backoffice.llm.gateway

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** Extracts the JSON object from a model answer, tolerating Markdown fences and prose around it. */
class StructuredOutputParser(private val json: JsonMapper) {

    class ParseFailure(message: String) : RuntimeException(message)

    fun parse(content: String): Map<String, Any?> {
        val candidate = extractObject(content) ?: throw ParseFailure("no JSON object found in the answer")
        return try {
            @Suppress("UNCHECKED_CAST")
            json.readValue(candidate, Map::class.java) as Map<String, Any?>
        } catch (e: JacksonException) {
            throw ParseFailure("invalid JSON: ${e.originalMessage}")
        }
    }

    /** Strips ``` fences and returns the substring from the first `{` to the last `}`. */
    fun extractObject(content: String): String? {
        var text = content.trim()
        FENCE.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    companion object {
        private val FENCE = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
    }
}
