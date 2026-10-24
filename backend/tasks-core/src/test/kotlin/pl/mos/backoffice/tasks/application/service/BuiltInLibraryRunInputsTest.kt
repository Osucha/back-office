package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.TaskResolver
import pl.mos.backoffice.tasks.support.Library
import java.time.Instant

/** ADR 0052: the built-ins declare the run inputs they read, and their prompts show them as a "Run input" list. */
class BuiltInLibraryRunInputsTest {

    private val now = Instant.parse("2026-10-24T10:00:00Z")
    private val templates = BuiltInLibrary.templates(now).associateBy { it.key }
    private val input = RunInputField("input", "Input material", RunInputType.MULTILINE, required = false, help = templates.getValue(BuiltInLibrary.BASE_AGENT).runInputs.firstOrNull()?.help)
    private val issueKey = RunInputField("issueKey", "Jira issue key", RunInputType.ISSUE_KEY, required = true, help = templates.getValue(BuiltInLibrary.FETCH_ISSUE).runInputs.firstOrNull()?.help)

    private fun declared(key: String) = templates.getValue(key).runInputs

    @Test
    fun `base-agent and the generic tasks declare the optional input material`() {
        assertThat(declared(BuiltInLibrary.BASE_AGENT)).containsExactly(input)
        assertThat(declared(BuiltInLibrary.LLM_TASK)).containsExactly(input)
        assertThat(declared(BuiltInLibrary.AGENT_TASK)).containsExactly(input)
    }

    @Test
    fun `the Jira steps that read the run's issue key require it`() {
        listOf(BuiltInLibrary.FETCH_ISSUE, BuiltInLibrary.NOTIFY_JIRA, BuiltInLibrary.TRANSITION_ISSUE).forEach {
            assertThat(declared(it)).describedAs(it).containsExactly(issueKey)
        }
    }

    @Test
    fun `spec-kit-plan declares the technical hints and inherits the input material`() {
        assertThat(declared(BuiltInLibrary.SPEC_KIT_PLAN).single()).satisfies({
            assertThat(it.name).isEqualTo("techHints")
            assertThat(it.type).isEqualTo(RunInputType.MULTILINE)
            assertThat(it.required).isFalse()
        })
        val lib = Library()
        BuiltInLibrary.interfaces(now).forEach { lib.interfaces.save(it) }
        BuiltInLibrary.skills(now).forEach { lib.skills.save(it) }
        templates.values.forEach { lib.templates.save(it) }
        val plan = TaskResolver(lib.lookup).resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!
        assertThat(plan.runInputs.map { it.name }).containsExactly("input", "techHints")
    }

    @Test
    fun `no built-in prompt reads run parameters directly any more, the run input list is rendered instead`() {
        templates.values.forEach { t ->
            listOfNotNull(t.prompt.system, t.prompt.user).forEach { prompt ->
                assertThat(prompt).describedAs(t.key).doesNotContain("{{parameters.").doesNotContain("{{#parameters.")
            }
        }
        listOf(BuiltInLibrary.BASE_AGENT_SYSTEM, BuiltInLibrary.LLM_TASK_SYSTEM, BuiltInLibrary.AGENT_TASK_SYSTEM).forEach {
            assertThat(it).contains("{{#runInputText}}").contains("## Run input").contains("{{runInputText}}")
        }
    }

    @Test
    fun `the checksum covers run inputs, so the seed upgrades untouched built-ins`() {
        val fetch: TaskTemplate = templates.getValue(BuiltInLibrary.FETCH_ISSUE)
        val withoutInputs = fetch.update(fetch.content.copy(runInputs = emptyList()), now)

        assertThat(BuiltInChecksum.of(withoutInputs)).isNotEqualTo(BuiltInChecksum.of(fetch))
        assertThat(BuiltInChecksum.of(fetch.update(fetch.content, now))).isEqualTo(BuiltInChecksum.of(fetch))
    }
}
