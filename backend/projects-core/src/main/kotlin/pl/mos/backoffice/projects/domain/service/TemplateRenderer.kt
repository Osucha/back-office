package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.prompt.UntrustedText

data class RenderedTemplate(val text: String, val warnings: List<String>) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * Minimal Mustache-like renderer used for prompts, branch names, MR titles and gate questions.
 *
 * Syntax: `{{path.to.value}}` with optional filters `{{ path | lower }}`, `| upper`, `| trim`.
 * Paths walk nested maps, lists (numeric index) and simple objects exposed as maps. Missing values
 * render as an empty string and produce a warning so the caller can surface it in the run log.
 *
 * Sections (Mustache style): `{{#path}}...{{/path}}` keeps its content only when the value at `path` is
 * present (not null, not a blank string, not an empty list or map, not `false`); `{{^path}}...{{/path}}`
 * only when it is absent. Sections nest when their paths differ. They are resolved on the template before
 * any value is inserted, so a value can never open or close a section. When a template uses sections, runs
 * of three or more line breaks left by removed sections collapse to one blank line.
 *
 * Rendering is a single pass: values are inserted as literal text and are never scanned for placeholders
 * again, so an issue containing `{{project.constitution}}`, `${...}` or `#{T(...)}` stays literal text.
 * Prompts use [renderPrompt], which wraps every value from an untrusted root ([UNTRUSTED_ROOTS]) in a
 * delimited data block (ADR 0029).
 */
class TemplateRenderer {

    fun render(template: String, model: Map<String, Any?>): RenderedTemplate = render(template, model, null)

    /**
     * Renders a prompt: values under the untrusted roots (issue, artefacts, merge request, CI data, review
     * output, run input...) become data blocks of [blocks] (short identifiers stay inline, sanitised); trusted
     * values (project, node, run, human feedback and decisions) are inserted as written. [maxValueChars] caps
     * every single value.
     */
    fun renderPrompt(template: String, model: Map<String, Any?>, blocks: PromptDataBlocks, maxValueChars: Int = UntrustedText.DEFAULT_MAX_CHARS): RenderedTemplate =
        render(template, model) { path, text ->
            if (isUntrusted(path)) blocks.inlineOrWrap(path, text, maxValueChars) else UntrustedText.sanitize(text, maxValueChars)
        }

    private fun render(template: String, model: Map<String, Any?>, format: ((String, String) -> String)?): RenderedTemplate {
        val warnings = mutableListOf<String>()
        val text = PLACEHOLDER.replace(expandSections(template, model)) { match ->
            val expression = match.groupValues[1]
            val parts = expression.split("|").map { it.trim() }
            val path = parts.first()
            val filters = parts.drop(1)
            if (path.isEmpty()) {
                warnings += "empty placeholder"
                return@replace ""
            }
            val value = resolve(path, model)
            if (value == null) {
                warnings += "missing value for {{${path}}}"
                return@replace ""
            }
            val text = filters.fold(stringify(value)) { acc, filter -> applyFilter(acc, filter, warnings) }
            format?.invoke(path, text) ?: text
        }
        return RenderedTemplate(text, warnings)
    }

    /** Convenience for callers that only want the text. */
    fun renderText(template: String, model: Map<String, Any?>): String = render(template, model).text

    /** Resolves `{{#path}}` / `{{^path}}` sections on the template text (never on inserted values). */
    private fun expandSections(template: String, model: Map<String, Any?>): String {
        if (!template.contains("{{#") && !template.contains("{{^")) return template
        var text = template
        repeat(MAX_SECTION_DEPTH) {
            val next = SECTION.replace(text) { m ->
                val inverted = m.groupValues[1] == "^"
                if (isPresent(resolve(m.groupValues[2], model)) != inverted) m.groupValues[3] else ""
            }
            if (next == text) return BLANK_LINES.replace(text, "\n\n")
            text = next
        }
        return BLANK_LINES.replace(text, "\n\n")
    }

    private fun isPresent(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotBlank()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Boolean -> value
        else -> true
    }

    private fun resolve(path: String, model: Map<String, Any?>): Any? {
        var current: Any? = model
        for (segment in path.split(".")) {
            current = when (current) {
                null -> return null
                is Map<*, *> -> current[segment]
                is List<*> -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
        }
        return current
    }

    private fun stringify(value: Any): String = when (value) {
        is String -> value
        is List<*> -> value.joinToString("\n") { it?.let(::stringify) ?: "" }
        is Map<*, *> -> value.entries.joinToString("\n") { "${it.key}: ${it.value?.let(::stringify) ?: ""}" }
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        is Float -> stringify(value.toDouble())
        else -> value.toString()
    }

    private fun applyFilter(value: String, filter: String, warnings: MutableList<String>): String = when (filter.lowercase()) {
        "lower" -> value.lowercase()
        "upper" -> value.uppercase()
        "trim" -> value.trim()
        else -> {
            warnings += "unknown filter $filter"
            value
        }
    }

    companion object {
        /**
         * Roots of the template model whose values come from outside the trust boundary: issue tracker,
         * artefacts of agents and LLM steps, CI payloads, merge-request data, run input and parameters
         * (including the declared run inputs `runInput` / `runInputText`, ADR 0052), model verdicts and errors. Everything else (`project`, `node`, `run`, `feedback`, `decision`,
         * `confidence`, `lastOutcome`) is written by people with project permissions or by the platform.
         */
        val UNTRUSTED_ROOTS = setOf("issue", "artifacts", "mr", "review", "ci", "input", "parameters", "runInput", "runInputText", "branch", "lastError")

        fun isUntrusted(path: String): Boolean = path.substringBefore('.').trim() in UNTRUSTED_ROOTS

        private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]*?)\\s*}}")

        /** `{{#path}}body{{/path}}` or `{{^path}}body{{/path}}`; the closing tag must name the same path. */
        private val SECTION = Regex("\\{\\{([#^])\\s*([A-Za-z0-9_.-]+)\\s*}}(.*?)\\{\\{/\\s*\\2\\s*}}", RegexOption.DOT_MATCHES_ALL)
        private val BLANK_LINES = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+")
        private const val MAX_SECTION_DEPTH = 8
    }
}
