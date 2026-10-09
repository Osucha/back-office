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
        val text = PLACEHOLDER.replace(template) { match ->
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
         * artefacts of agents and LLM steps, CI payloads, merge-request data, run input and parameters,
         * model verdicts and errors. Everything else (`project`, `node`, `run`, `feedback`, `decision`,
         * `confidence`, `lastOutcome`) is written by people with project permissions or by the platform.
         */
        val UNTRUSTED_ROOTS = setOf("issue", "artifacts", "mr", "review", "ci", "input", "parameters", "branch", "lastError")

        fun isUntrusted(path: String): Boolean = path.substringBefore('.').trim() in UNTRUSTED_ROOTS

        private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]*?)\\s*}}")
    }
}
