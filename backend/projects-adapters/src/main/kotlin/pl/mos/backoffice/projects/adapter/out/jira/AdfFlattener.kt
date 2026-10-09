package pl.mos.backoffice.projects.adapter.out.jira

import pl.mos.backoffice.shared.prompt.UntrustedText

/**
 * Flattens Atlassian Document Format (ADF) to markdown: paragraphs, headings, bullet/ordered lists,
 * code blocks, block quotes, rules, tables (pipe rows) and inline text with bold/italic/code/link/
 * strike marks. Unknown nodes fall back to their text content. Plain strings pass through.
 */
object AdfFlattener {

    fun toMarkdown(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Map<*, *> -> render(value, 0).trim().replace(Regex("\n{3,}"), "\n\n")
        else -> value.toString()
    }

    /**
     * Plain-text ADF for comments posted by Back Office: paragraphs of bare `text` nodes, never marks
     * (links), `mention`, `inlineCard`, `emoji`, `status` or macro nodes, so a comment built from issue or
     * model text cannot notify people or render clickable links; hidden/bidi characters are removed (ADR 0029).
     */
    fun toAdf(raw: String): Map<String, Any?> = UntrustedText.sanitize(raw, MAX_COMMENT_CHARS).let { text -> mapOf(
        "type" to "doc",
        "version" to 1,
        "content" to text.split(Regex("\n{2,}")).filter { it.isNotBlank() }.map { paragraph ->
            mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to paragraph.trim())))
        }.ifEmpty { listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text.ifEmpty { " " })))) },
    ) }

    /** Jira Cloud rejects comments above 32 767 characters. */
    const val MAX_COMMENT_CHARS = 32_000

    private fun render(node: Map<*, *>, depth: Int, ordered: Boolean = false, index: Int = 0): String {
        val children = node["content"] as? List<*> ?: emptyList<Any?>()
        val attrs = node["attrs"] as? Map<*, *> ?: emptyMap<String, Any?>()
        fun inner(sep: String = "") = children.mapNotNull { (it as? Map<*, *>)?.let { c -> render(c, depth + 1) } }.joinToString(sep)
        val type = node["type"]?.toString()
        return when (type) {
            "doc" -> inner("\n")
            "paragraph" -> inner() + "\n\n"
            "heading" -> "#".repeat(((attrs["level"] as? Number)?.toInt() ?: 1).coerceIn(1, 6)) + " " + inner() + "\n\n"
            "text" -> applyMarks(node["text"]?.toString() ?: "", node["marks"] as? List<*>)
            "hardBreak" -> "\n"
            "rule" -> "---\n\n"
            "codeBlock" -> "```" + (attrs["language"]?.toString() ?: "") + "\n" + inner() + "\n```\n\n"
            "blockquote" -> inner().trim().lines().joinToString("\n") { "> $it" } + "\n\n"
            "bulletList", "orderedList" -> renderList(children, depth, ordered = type == "orderedList")
            "listItem" -> renderListItem(children, depth, ordered, index)
            "table" -> renderTable(children, depth)
            "tableRow", "tableCell", "tableHeader" -> inner(" ")
            "mention" -> "@" + (attrs["text"]?.toString()?.removePrefix("@") ?: attrs["id"]?.toString() ?: "user")
            "emoji" -> attrs["shortName"]?.toString() ?: attrs["text"]?.toString() ?: ""
            "inlineCard", "blockCard", "embedCard" -> (attrs["url"]?.toString() ?: "") + blockSuffix(type != "inlineCard")
            "mediaSingle", "mediaGroup", "media" -> (attrs["alt"]?.toString()?.let { "[$it]" } ?: "[attachment]") + blockSuffix(type != "media")
            "panel", "expand", "nestedExpand", "layoutSection", "layoutColumn", "taskList", "decisionList" -> inner()
            "taskItem" -> "- [" + (if (attrs["state"] == "DONE") "x" else " ") + "] " + inner() + "\n"
            "decisionItem" -> "- (decision) " + inner() + "\n"
            "status" -> "[" + (attrs["text"]?.toString() ?: "") + "]"
            "date" -> renderDate(attrs)
            else -> inner()
        }
    }

    private fun blockSuffix(block: Boolean): String = if (block) "\n\n" else ""

    /** Bullet or ordered list; items are numbered from 1, a top-level list ends with a blank line. */
    private fun renderList(children: List<*>, depth: Int, ordered: Boolean): String =
        children.mapIndexedNotNull { i, it -> (it as? Map<*, *>)?.let { c -> render(c, depth + 1, ordered = ordered, index = i + 1) } }
            .joinToString("") + (if (depth == 0) "\n" else "")

    /** One list item, indented by nesting level; nested lists keep their own line structure. */
    private fun renderListItem(children: List<*>, depth: Int, ordered: Boolean, index: Int): String {
        val indent = "  ".repeat((depth - 1).coerceAtLeast(0))
        val marker = if (ordered) "$index. " else "- "
        val body = children.mapNotNull { (it as? Map<*, *>) }.joinToString("") { c ->
            when (c["type"]) {
                "bulletList", "orderedList" -> render(c, depth + 1)
                else -> render(c, depth + 1).trim() + "\n"
            }
        }
        return indent + marker + body.trimStart()
    }

    /** Pipe table; the first row is the header. */
    private fun renderTable(children: List<*>, depth: Int): String {
        val rows = children.mapNotNull { it as? Map<*, *> }.map { row ->
            (row["content"] as? List<*> ?: emptyList<Any?>()).mapNotNull { it as? Map<*, *> }.map { cell -> render(cell, depth + 1).trim().replace("\n", " ") }
        }
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("| " + rows.first().joinToString(" | ") + " |")
            appendLine("|" + rows.first().joinToString("|") { "---" } + "|")
            rows.drop(1).forEach { appendLine("| " + it.joinToString(" | ") + " |") }
            appendLine()
        }
    }

    private fun renderDate(attrs: Map<*, *>): String =
        (attrs["timestamp"] as? Number)?.toLong()?.let { java.time.Instant.ofEpochMilli(it).toString().substring(0, 10) } ?: ""

    private fun applyMarks(text: String, marks: List<*>?): String {
        var result = text
        marks?.mapNotNull { it as? Map<*, *> }?.forEach { mark ->
            val attrs = mark["attrs"] as? Map<*, *>
            result = when (mark["type"]) {
                "strong" -> "**$result**"
                "em" -> "_${result}_"
                "code" -> "`$result`"
                "strike" -> "~~$result~~"
                "link" -> "[$result](${attrs?.get("href") ?: ""})"
                "underline" -> result
                else -> result
            }
        }
        return result
    }
}
