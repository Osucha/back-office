package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.service.StepParameters
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.tasks.ParameterBinding
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType

/** ADR 0062: the instructions, then the standard data sections the instructions do not place, the diff last. */
class TaskMessageTest {

    private val project = Fixtures.project()
    private val message = TaskMessage(TemplateRenderer())
    private val input = TemplateParameter("input", "Input material", TemplateParameterType.MULTILINE)

    private fun task(instructions: String?, consumes: List<String> = listOf("spec", "diff", "issue"), bound: Boolean = true) = ResolvedTask(
        "t", "T", TestAgents.MODEL_ANSWER, null, instructions, emptyList(), emptyMap(), null, null, emptyList(), consumes, null, listOf("t"), listOf("success"),
        runInputs = if (bound) listOf(input.asRunInput()) else emptyList(),
        parameters = listOf(ResolvedParameter(input, null, ParameterSource.STEP, ParameterBinding.START.takeIf { bound })),
    )

    private fun render(task: ResolvedTask, feedback: Boolean = true, answers: Boolean = true): String {
        val run = Fixtures.runningRun(project, input = RunInput("SHOP-1", mapOf("input" to "Pasted notes")))
            .addArtifact(Artifact(ArtifactId.new(), "write", "spec", ArtifactKind.SPEC, content = "The spec text", createdAt = now))
            .addArtifact(Artifact(ArtifactId.new(), "code", "diff", ArtifactKind.DIFF, "text/x-diff", "+ added line", createdAt = now))
        val node = run.definitionSnapshot.requireNode("implement")
        val state = buildMap<String, Any?> {
            put(RunStateKeys.ISSUE, mapOf("key" to "SHOP-1", "summary" to "Pay", "description" to "Pay by card"))
            if (feedback) put(RunStateKeys.PENDING_FEEDBACK, listOf("Add tests"))
            if (answers) put(RunStateKeys.ANSWERS, mapOf("implement" to "Use the billing module"))
        }
        val model = TemplateModelBuilder().build(
            run, project, node, 1, state, emptyList(), task.runInputs, StepParameters.values(task, run), StepParameters.untrusted(task),
        )
        return message.render(task, model, PromptDataBlocks.random()).text
    }

    @Test
    fun `the standard sections follow the instructions in order, the diff last`() {
        val text = render(task("Do the work."))

        val order = listOf(
            "Do the work.", "## Issue", "## Specification", "## Input material", "## Feedback on the previous attempt", "## Answers of the owner", "## Diff to review",
        ).map { text.indexOf(it) }
        assertThat(order).doesNotContain(-1)
        assertThat(order).isSorted()
        assertThat(text).contains("Pay by card").contains("The spec text").contains("Pasted notes").contains("Add tests").contains("+ added line")
        assertThat(text).describedAs("untrusted values are data blocks").contains("source=\"params.input\"").contains("source=\"artifacts.spec\"")
    }

    @Test
    fun `a section the instructions place themselves is not added again`() {
        val text = render(task("Fix {{issue.key}} with {{artifacts.spec}} and {{ params.input }}. {{#feedback}}{{feedback}}{{/feedback}} {{answers}}"))

        assertThat(text).doesNotContain("## Issue").doesNotContain("## Specification").doesNotContain("## Input material")
            .doesNotContain("## Feedback on the previous attempt").doesNotContain("## Answers of the owner").contains("## Diff to review")
        assertThat(text.split("The spec text")).describedAs("one copy").hasSize(2)
    }

    @Test
    fun `empty sections leave nothing behind, unbound parameters and runInputText users get no parameter section`() {
        val first = render(task("Do it.", consumes = listOf("plan")), feedback = false, answers = false)
        assertThat(first).doesNotContain("## Plan").doesNotContain("## Feedback").doesNotContain("## Answers")
        assertThat(render(task("Do it.", bound = false))).doesNotContain("## Input material")
        assertThat(render(task("Do it.\n{{#runInputText}}{{runInputText}}{{/runInputText}}"))).doesNotContain("## Input material").contains("Pasted notes")
    }

    @Test
    fun `a template without instructions gets the step name as a heading`() {
        assertThat(TaskMessage.template(task(null))).startsWith(TaskMessage.NO_INSTRUCTIONS)
        assertThat(render(task(" "))).startsWith("Task: ")
    }

    @Test
    fun `placeholders are matched exactly and artefact headings read as words`() {
        assertThat(TaskMessage.places("{{ artifacts.spec | trim }}", "artifacts.spec")).isTrue()
        assertThat(TaskMessage.places("{{#artifacts.spec}}x{{/artifacts.spec}}", "artifacts.spec")).isTrue()
        assertThat(TaskMessage.places("{{artifacts.spec-v2}}", "artifacts.spec")).isFalse()
        assertThat(TaskMessage.places("{{feedbackHistory}}", "feedback")).isFalse()
        assertThat(TaskMessage.artifactTitle("impact-analysis")).isEqualTo("Impact analysis")
        assertThat(TaskMessage.artifactTitle("release_notes")).isEqualTo("Release notes")
        assertThat(TaskMessage.template(task("x", consumes = listOf("bad name")))).doesNotContain("bad name")
    }
}
