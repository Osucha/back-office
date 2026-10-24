package pl.mos.backoffice.projects.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType

/** ADR 0053: `runInput` lists the current task's declared fields with label and value; `parameters.*` stays. */
class TemplateModelBuilderTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val node = definition.nodes.first { it.id == "specify" }
    private val builder = TemplateModelBuilder()

    private val declared = listOf(
        RunInputField("input", "Input material", RunInputType.MULTILINE),
        RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true),
        RunInputField("techHints", "Technical hints", RunInputType.MULTILINE),
    )

    private fun model(input: RunInput, fields: List<RunInputField> = declared) =
        builder.build(Fixtures.runningRun(project, definition, input), project, node, 1, emptyMap(), runInputs = fields)

    @Test
    fun `declared fields appear with label and value, in declaration order`() {
        val model = model(RunInput("SHOP-1", mapOf("techHints" to "Kotlin only", "input" to "Add a basket page", "other" to "x")))

        assertThat(model["runInput"]).isEqualTo(
            listOf(
                mapOf("name" to "input", "label" to "Input material", "value" to "Add a basket page"),
                mapOf("name" to "issueKey", "label" to "Jira issue", "value" to "SHOP-1"),
                mapOf("name" to "techHints", "label" to "Technical hints", "value" to "Kotlin only"),
            ),
        )
        assertThat(model["runInputText"]).isEqualTo("Input material: Add a basket page\n\nJira issue: SHOP-1\n\nTechnical hints: Kotlin only")
    }

    @Test
    fun `undeclared parameters are not in the list, blank values are left out, parameters stay available`() {
        val model = model(RunInput(null, mapOf("input" to "  ", "other" to "kept")))

        assertThat(model["runInput"]).isEqualTo(emptyList<Any>())
        assertThat(model["runInputText"]).isEqualTo("")
        assertThat(model["parameters"]).isEqualTo(mapOf("input" to "  ", "other" to "kept"))
        @Suppress("UNCHECKED_CAST")
        assertThat((model["input"] as Map<String, Any?>)["parameters"]).isEqualTo(mapOf("input" to "  ", "other" to "kept"))
    }

    @Test
    fun `a task without declared fields has an empty run input list`() {
        val model = builder.build(Fixtures.runningRun(project, definition, RunInput("SHOP-1", mapOf("input" to "text"))), project, node, 1, emptyMap())

        assertThat(model["runInput"]).isEqualTo(emptyList<Any>())
        assertThat(model["parameters"]).isEqualTo(mapOf("input" to "text"))
    }
}
