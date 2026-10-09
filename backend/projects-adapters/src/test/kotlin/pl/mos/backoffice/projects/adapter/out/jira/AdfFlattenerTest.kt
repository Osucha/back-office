package pl.mos.backoffice.projects.adapter.out.jira

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADF -> markdown of issue descriptions (the node handlers of [AdfFlattener.toMarkdown]). */
class AdfFlattenerTest {

    private fun node(type: String, vararg content: Map<String, Any?>, attrs: Map<String, Any?>? = null): Map<String, Any?> =
        buildMap {
            put("type", type)
            if (content.isNotEmpty()) put("content", content.toList())
            if (attrs != null) put("attrs", attrs)
        }

    private fun text(value: String, vararg marks: Map<String, Any?>): Map<String, Any?> =
        if (marks.isEmpty()) mapOf("type" to "text", "text" to value) else mapOf("type" to "text", "text" to value, "marks" to marks.toList())

    private fun paragraph(vararg content: Map<String, Any?>) = node("paragraph", *content)

    @Test
    fun `plain values pass through`() {
        assertThat(AdfFlattener.toMarkdown(null)).isEmpty()
        assertThat(AdfFlattener.toMarkdown("already text")).isEqualTo("already text")
        assertThat(AdfFlattener.toMarkdown(42)).isEqualTo("42")
    }

    @Test
    fun `nested bullet and ordered lists are indented and numbered`() {
        val doc = node(
            "doc",
            node(
                "bulletList",
                node("listItem", paragraph(text("a"))),
                node(
                    "listItem",
                    paragraph(text("b")),
                    node("orderedList", node("listItem", paragraph(text("c"))), node("listItem", paragraph(text("d")))),
                ),
            ),
        )

        assertThat(AdfFlattener.toMarkdown(doc)).isEqualTo("- a\n  - b\n      1. c\n      2. d")
    }

    @Test
    fun `tables become pipe tables with a header separator`() {
        val doc = node(
            "doc",
            node(
                "table",
                node("tableRow", node("tableHeader", paragraph(text("H1"))), node("tableHeader", paragraph(text("H2")))),
                node("tableRow", node("tableCell", paragraph(text("x"))), node("tableCell", paragraph(text("y")))),
            ),
        )

        assertThat(AdfFlattener.toMarkdown(doc)).isEqualTo("| H1 | H2 |\n|---|---|\n| x | y |")
        assertThat(AdfFlattener.toMarkdown(node("doc", node("table")))).isEmpty()
    }

    @Test
    fun `inline nodes and marks`() {
        val doc = node(
            "doc",
            paragraph(
                text("Hi "),
                text("bold", mapOf("type" to "strong")),
                text(" "),
                text("site", mapOf("type" to "link", "attrs" to mapOf("href" to "https://x.example"))),
                text(" "),
                node("mention", attrs = mapOf("text" to "@ann", "id" to "42")),
                text(" "),
                node("emoji", attrs = mapOf("shortName" to ":smile:")),
                text(" "),
                node("status", attrs = mapOf("text" to "DONE")),
                text(" "),
                node("date", attrs = mapOf("timestamp" to 0L)),
                text(" "),
                node("inlineCard", attrs = mapOf("url" to "https://card.example")),
            ),
        )

        assertThat(AdfFlattener.toMarkdown(doc))
            .isEqualTo("Hi **bold** [site](https://x.example) @ann :smile: [DONE] 1970-01-01 https://card.example")
    }

    @Test
    fun `block nodes are separated by one blank line`() {
        val doc = node(
            "doc",
            node("heading", text("T"), attrs = mapOf("level" to 9)),
            node("codeBlock", text("val x"), attrs = mapOf("language" to "kotlin")),
            node("blockquote", paragraph(text("q"))),
            node("rule"),
            node("taskList", node("taskItem", text("t"), attrs = mapOf("state" to "DONE"))),
            node("mediaSingle", node("media"), attrs = mapOf("alt" to "pic")),
            node("blockCard", attrs = mapOf("url" to "https://b.example")),
        )

        assertThat(AdfFlattener.toMarkdown(doc))
            .isEqualTo("###### T\n\n```kotlin\nval x\n```\n\n> q\n\n---\n\n- [x] t\n\n[pic]\n\nhttps://b.example")
    }
}
