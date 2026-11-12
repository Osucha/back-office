package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.ParameterBinding
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.tasks.domain.model.InstructionsMode
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.TaskResolver
import pl.mos.backoffice.tasks.support.Library
import java.time.Instant

/**
 * ADR 0062: every built-in has one instruction text without role sentences and without the data the platform adds; the
 * former run input `input` (and `techHints`) are parameters a step binds to the run start.
 */
class BuiltInLibraryInstructionsTest {

    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val templates = BuiltInLibrary.templates(now).associateBy { it.key }

    private fun parameterNames(key: String) = templates.getValue(key).parameters.map { it.name }

    @Test
    fun `base-agent and the generic tasks declare the optional input material as a parameter`() {
        val input = BuiltInLibrary.INPUT_PARAMETER
        assertThat(input.name).isEqualTo(BuiltInLibrary.INPUT_PARAMETER_NAME)
        assertThat(input.type).isEqualTo(TemplateParameterType.MULTILINE)
        assertThat(input.required).isFalse()
        assertThat(templates.getValue(BuiltInLibrary.BASE_AGENT).parameters).containsExactly(input)
        assertThat(parameterNames(BuiltInLibrary.LLM_TASK)).containsExactly(BuiltInLibrary.TASK_PARAMETER, "input")
        assertThat(parameterNames(BuiltInLibrary.AGENT_TASK)).containsExactly(BuiltInLibrary.TASK_PARAMETER, "input")
    }

    @Test
    fun `every built-in that asked for the input material declares it, so migrated START bindings keep resolving`() {
        val withInput = templates.values.filter { !it.abstract && it.extendsKey == null && it.key != BuiltInLibrary.PREPARE_WORKSPACE }
        withInput.forEach { assertThat(parameterNames(it.key)).describedAs(it.key).contains("input") }
    }

    @Test
    fun `write-plan asks for the input material and the technical hints when its step binds them to the run start`() {
        assertThat(parameterNames(BuiltInLibrary.WRITE_PLAN)).containsExactly("input", "techHints")
        val lib = Library()
        BuiltInLibrary.rules(now).forEach { lib.rules.save(it) }
        BuiltInLibrary.skills(now).forEach { lib.skills.save(it) }
        templates.values.forEach { lib.templates.save(it) }
        val resolver = TaskResolver(lib.lookup)

        val bound = resolver.resolve(
            BuiltInLibrary.WRITE_PLAN, null,
            TaskOverrides(agentKey = "opencode", parameterBindings = mapOf("input" to ParameterBinding.START, "techHints" to ParameterBinding.START)),
        )!!
        assertThat(bound.runInputs.map { it.name to it.type }).containsExactly("input" to RunInputType.MULTILINE, "techHints" to RunInputType.MULTILINE)
        assertThat(resolver.resolve(BuiltInLibrary.WRITE_PLAN, null, TaskOverrides(agentKey = "opencode"))!!.runInputs).isEmpty()
    }

    @Test
    fun `instructions carry no role sentences and no data the platform adds as standard sections`() {
        templates.values.mapNotNull { t -> t.instructions?.let { t.key to it } }.forEach { (key, text) ->
            assertThat(text).describedAs(key)
                .doesNotContain("You are ")
                .doesNotContain("{{parameters.").doesNotContain("{{#parameters.")
                .doesNotContain("{{runInputText}}").doesNotContain("{{feedback}}")
                .doesNotContain("Follow your rules")
        }
        val concrete = templates.values.filter { !it.abstract && it.extendsKey == null }
        concrete.forEach { assertThat(it.instructions).describedAs(it.key).isNotBlank() }
    }

    @Test
    fun `instructions leave the issue to the platform, the specification templates write into specs`() {
        listOf(BuiltInLibrary.WRITE_USER_STORY, BuiltInLibrary.IMPACT_ANALYSIS, BuiltInLibrary.CODE_REVIEW, BuiltInLibrary.IMPLEMENT_OPENCODE, BuiltInLibrary.WRITE_SPEC).forEach {
            assertThat(templates.getValue(it).instructions).describedAs(it).doesNotContain("{{issue.")
        }
        // ADR 0063: the same documents a careful engineer writes, as output files; the deprecated Spec Kit keys extend them
        mapOf(BuiltInLibrary.WRITE_SPEC to "spec", BuiltInLibrary.WRITE_PLAN to "plan", BuiltInLibrary.WRITE_TASK_LIST to "tasks").forEach { (key, artefact) ->
            val template = templates.getValue(key)
            assertThat(template.outputFiles).containsExactly("specs/**/$artefact.md")
            assertThat(template.produces).containsExactly(artefact)
            assertThat(template.config).containsEntry("codingAgent", true)
            assertThat(template.instructions!!.replace(Regex("\\s+"), " ")).contains("specs/<feature>/").contains("do not commit")
        }
        BuiltInLibrary.SPEC_KIT_REPLACEMENTS.forEach { (old, replacement) ->
            assertThat(templates.getValue(old).extendsKey).isEqualTo(replacement)
            assertThat(templates.getValue(old).tags).contains(BuiltInLibrary.DEPRECATED_TAG)
        }
    }

    @Test
    fun `implement-task-list replaces the instructions of implement-opencode`() {
        val list = templates.getValue(BuiltInLibrary.IMPLEMENT_TASK_LIST)
        assertThat(list.instructionsMode).isEqualTo(InstructionsMode.REPLACE)
        assertThat(list.instructions).contains(BuiltInLibrary.WORK_LOG_FILE).contains("{{#artifacts.review}}")
    }

    @Test
    fun `the checksum covers instructions and parameters, so the seed upgrades untouched built-ins`() {
        val task: TaskTemplate = templates.getValue(BuiltInLibrary.LLM_TASK)

        assertThat(BuiltInChecksum.of(task.update(task.content.copy(parameters = emptyList()), now))).isNotEqualTo(BuiltInChecksum.of(task))
        assertThat(BuiltInChecksum.of(task.update(task.content.copy(instructions = "other"), now))).isNotEqualTo(BuiltInChecksum.of(task))
        assertThat(BuiltInChecksum.of(task.update(task.content.copy(instructionsMode = InstructionsMode.REPLACE), now))).isNotEqualTo(BuiltInChecksum.of(task))
        assertThat(BuiltInChecksum.of(task.update(task.content, now))).isEqualTo(BuiltInChecksum.of(task))
    }
}
