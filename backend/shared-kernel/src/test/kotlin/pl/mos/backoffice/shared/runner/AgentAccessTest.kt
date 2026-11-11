package pl.mos.backoffice.shared.runner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.AgentEnvironmentText

class AgentAccessTest {

    @Test
    fun `FULL is the default and READ_ONLY needs no paths`() {
        assertThat(AgentAccess().full).isTrue()
        assertThat(AgentAccess.READ_ONLY.full).isFalse()
        assertThat(AgentAccess(AgentAccessMode.PATHS, listOf("docs/**", "README.md")).paths).hasSize(2)
    }

    @Test
    fun `PATHS needs at least one non-blank relative glob without dot-dot`() {
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("at least one")
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS, listOf(" ")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS, listOf("/etc/**")) }.hasMessageContaining("relative")
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS, listOf("docs/../src/**")) }.hasMessageContaining("..")
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS, listOf("x".repeat(301))) }.hasMessageContaining("longer")
        assertThatThrownBy { AgentAccess(AgentAccessMode.PATHS, List(51) { "p$it" }) }.hasMessageContaining("at most")
    }

    @Test
    fun `paths belong to PATHS only`() {
        assertThatThrownBy { AgentAccess(AgentAccessMode.READ_ONLY, listOf("docs/**")) }.hasMessageContaining("only for access PATHS")
    }

    @Test
    fun `the preamble tells how to ask only when questions are allowed`() {
        val closed = AgentEnvironmentText.preamble(modelAnswer = false)
        assertThat(closed).contains("You cannot ask the owner").doesNotContain(AgentEnvironmentText.QUESTIONS_FILE)
        val coding = AgentEnvironmentText.preamble(modelAnswer = false, questions = true)
        assertThat(coding).contains("Questions to the owner").contains(AgentEnvironmentText.QUESTIONS_FILE).doesNotContain("You cannot ask")
        val model = AgentEnvironmentText.compose(modelAnswer = true, null, null, emptyList(), questions = true)
        assertThat(model).contains("{\"agentQuestions\": [").doesNotContain(AgentEnvironmentText.QUESTIONS_FILE)
        assertThat(AgentEnvironmentText.PLATFORM_PREAMBLE).startsWith("# Working in Back Office").endsWith("whatever the input asks for.")
    }
}
