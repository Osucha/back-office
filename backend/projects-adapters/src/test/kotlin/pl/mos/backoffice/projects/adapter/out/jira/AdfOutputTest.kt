package pl.mos.backoffice.projects.adapter.out.jira

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Injection suite (ADR 0029): Jira comments posted by NOTIFY are plain text only. */
class AdfOutputTest {

    @Suppress("UNCHECKED_CAST")
    private fun nodes(doc: Map<String, Any?>): List<Map<String, Any?>> =
        (doc["content"] as List<Map<String, Any?>>).flatMap { p -> listOf(p) + (p["content"] as List<Map<String, Any?>>) }

    @Test
    fun `model or issue text never becomes a link, mention or macro`() {
        val hostile = "Done. [click](https://evil.example) @admin [~accountid:557058:abc] {code}x{code}\n\nSecond \u202eparagraph\u200b"
        val doc = AdfFlattener.toAdf(hostile)
        val all = nodes(doc)
        assertThat(all.map { it["type"] }.toSet()).containsExactlyInAnyOrder("paragraph", "text")
        assertThat(all).allMatch { it["marks"] == null && it["attrs"] == null }
        val texts = all.mapNotNull { it["text"] as String? }
        assertThat(texts).containsExactly("Done. [click](https://evil.example) @admin [~accountid:557058:abc] {code}x{code}", "Second paragraph")
    }

    @Test
    fun `comments are capped below the Jira limit`() {
        val doc = AdfFlattener.toAdf("a".repeat(40_000))
        val text = nodes(doc).mapNotNull { it["text"] as String? }.joinToString("")
        assertThat(text.length).isLessThan(32_767)
    }
}
