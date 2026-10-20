package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TemplateRendererTest {

    private val renderer = TemplateRenderer()

    @Test
    fun `renders nested paths and filters`() {
        val model = mapOf("issue" to mapOf("key" to "SHOP-123", "summary" to "  Add cart  "), "list" to listOf("a", "b"))
        val result = renderer.render("feature/{{issue.key | lower}}: {{ issue.summary | trim | upper }} {{list.1}}", model)
        assertThat(result.text).isEqualTo("feature/shop-123: ADD CART b")
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `missing values render empty and warn`() {
        val result = renderer.render("Spec: {{artifacts.spec}}!", mapOf("artifacts" to emptyMap<String, Any?>()))
        assertThat(result.text).isEqualTo("Spec: !")
        assertThat(result.warnings).containsExactly("missing value for {{artifacts.spec}}")
    }

    @Test
    fun `numbers, lists and maps are stringified`() {
        val model = mapOf("score" to 87.0, "ratio" to 0.85, "items" to listOf("one", "two"), "review" to mapOf("verdict" to "APPROVE"))
        val result = renderer.render("{{score}} {{ratio}} {{items}} {{review}}", model)
        assertThat(result.text).isEqualTo("87 0.85 one\ntwo verdict: APPROVE")
    }

    @Test
    fun `sections keep their content only for present values, inverted sections only for absent ones`() {
        val template = "A{{#issue.key}} issue {{issue.key}}{{/issue.key}}{{^issue.key}} no issue{{/issue.key}}{{#feedback}} fb {{feedback}}{{/feedback}}."
        val withIssue = renderer.render(template, mapOf("issue" to mapOf("key" to "SHOP-1"), "feedback" to "  "))
        assertThat(withIssue.text).isEqualTo("A issue SHOP-1.")
        assertThat(withIssue.warnings).isEmpty()

        val without = renderer.render(template, mapOf("issue" to emptyMap<String, Any?>(), "feedback" to "redo it"))
        assertThat(without.text).isEqualTo("A no issue fb redo it.")
        assertThat(without.warnings).describedAs("a skipped section reports no missing values").isEmpty()
    }

    @Test
    fun `sections nest, treat empty lists and false as absent and collapse the blank lines they leave`() {
        val template = "Head\n\n{{#issue.key}}\nIssue {{issue.key}}\n{{#issue.description}}\n{{issue.description}}\n{{/issue.description}}\n{{/issue.key}}\n\n{{#items}}Items: {{items}}{{/items}}{{#flag}}flag{{/flag}}\n\nTail"
        val result = renderer.render(template, mapOf("issue" to mapOf("key" to "K-1", "description" to ""), "items" to emptyList<String>(), "flag" to false))
        assertThat(result.text).isEqualTo("Head\n\nIssue K-1\n\nTail")
    }

    @Test
    fun `a value that looks like a section tag is inserted literally`() {
        val result = renderer.render("{{#a}}[{{a}}]{{/a}}", mapOf("a" to "{{#b}}x{{/b}}", "b" to "secret"))
        assertThat(result.text).isEqualTo("[{{#b}}x{{/b}}]")
    }
}
