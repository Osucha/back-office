package pl.mos.backoffice.shared.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR 0062: parameters as start form fields, ISSUE_KEY values, and the legacy step fields read as the new ones. */
class ParameterBindingTest {

    @Test
    fun `a parameter becomes a start form field of its kind and keeps its declaration`() {
        val multi = TemplateParameter("input", "Input", TemplateParameterType.MULTILINE, help = "paste it")
        assertThat(multi.asRunInput()).isEqualTo(RunInputField("input", "Input", RunInputType.MULTILINE, help = "paste it", parameter = multi))
        assertThat(TemplateParameter("key", "Key", TemplateParameterType.ISSUE_KEY, required = true).asRunInput().type).isEqualTo(RunInputType.ISSUE_KEY)
        assertThat(TemplateParameter("n", "N", TemplateParameterType.NUMBER).asRunInput().type).isEqualTo(RunInputType.TEXT)
        val choice = TemplateParameter("level", "Level", TemplateParameterType.CHOICE, options = listOf("low", "high"))
        assertThat(choice.asRunInput().help).isEqualTo("One of: low, high.")
        val long = choice.copy(help = "h".repeat(TemplateParameter.MAX_HELP_LENGTH))
        assertThat(long.asRunInput().help).describedAs("no room for the options").isEqualTo(long.help)
    }

    @Test
    fun `an ISSUE_KEY parameter accepts only issue keys`() {
        val key = TemplateParameter("key", "Key", TemplateParameterType.ISSUE_KEY)
        assertThat(key.valueProblem("ABC-123")).isNull()
        assertThat(key.valueProblem(" abc-1 ")).isNull()
        assertThat(key.valueProblem("ABC")).contains("is not an issue key")
        assertThat(key.problems()).isEmpty()
    }

    @Test
    fun `bindings know whether the run sets the value`() {
        val p = TemplateParameter("p", "P")
        assertThat(ResolvedParameter(p, null, ParameterSource.STEP, ParameterBinding.START).boundAtRun).isTrue()
        assertThat(ResolvedParameter(p, null, ParameterSource.STEP, ParameterBinding.artifact("spec")).boundAtRun).isTrue()
        assertThat(ResolvedParameter(p, "x", ParameterSource.STEP, ParameterBinding.value("x")).boundAtRun).isFalse()
        assertThat(ResolvedParameter(p, "x", ParameterSource.TEMPLATE).boundAtRun).isFalse()
    }

    @Test
    fun `legacy step texts join into the notes and legacy values become VALUE bindings the new ones win over`() {
        assertThat(TaskOverrides.joinedAppend("notes", " sys ", null, "", "notes")).isEqualTo("notes\n\nsys")
        assertThat(TaskOverrides.joinedAppend(null, " ")).isNull()
        assertThat(TaskOverrides.withLegacyValues(mapOf("a" to ParameterBinding.START), mapOf("a" to "x", "b" to 2, "c" to " ", "d" to null)))
            .isEqualTo(mapOf("b" to ParameterBinding.value(2), "a" to ParameterBinding.START))
        assertThat(TaskOverrides.withLegacyValues(emptyMap(), null)).isEmpty()
    }
}
