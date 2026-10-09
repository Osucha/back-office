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
}
