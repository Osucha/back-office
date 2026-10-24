package pl.mos.backoffice.shared.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException

class RunInputFieldTest {

    @Test
    fun `a field is optional plain text without help by default`() {
        val field = RunInputField("input", "Input material")

        assertThat(field.type).isEqualTo(RunInputType.TEXT)
        assertThat(field.required).isFalse()
        assertThat(field.help).isNull()
        assertThat(RunInputField.MAX_LENGTH).isEqualTo(20_000)
    }

    @Test
    fun `blank names and labels are refused`() {
        assertThatThrownBy { RunInputField(" ", "Label") }.isInstanceOf(ValidationException::class.java).hasMessageContaining("name")
        assertThatThrownBy { RunInputField("input", "") }.isInstanceOf(ValidationException::class.java).hasMessageContaining("label")
    }

    @Test
    fun `a resolved task declares no run inputs unless given`() {
        val task = ResolvedTask(
            key = "k", name = "K", executor = TaskExecutor.LLM, modelProfileId = null as ModelProfileId?, prompt = TaskPrompt(null, null),
            skills = emptyList(), config = emptyMap(), outputSchema = null, confidenceField = null, produces = emptyList(), consumes = emptyList(),
            interfaces = emptyList(), lineage = listOf("k"), ports = listOf("success"),
        )

        assertThat(task.runInputs).isEmpty()
    }
}
