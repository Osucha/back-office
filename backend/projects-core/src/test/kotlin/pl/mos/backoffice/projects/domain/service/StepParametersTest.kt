package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.shared.testing.TestAgents
import pl.mos.backoffice.shared.tasks.ParameterBinding
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType

/** ADR 0062: a step's parameter values in a run: the design's, the run start form's or an artefact's, else the fallback. */
class StepParametersTest {

    private val project = Fixtures.project()

    private fun parameter(name: String, type: TemplateParameterType = TemplateParameterType.TEXT) = TemplateParameter(name, name.uppercase(), type)

    private val parameters = listOf(
        ResolvedParameter(parameter("fixed"), "designed", ParameterSource.STEP, ParameterBinding.value("designed")),
        ResolvedParameter(parameter("preset"), "from template", ParameterSource.TEMPLATE),
        ResolvedParameter(parameter("input", TemplateParameterType.MULTILINE), null, ParameterSource.STEP, ParameterBinding.START, fallback = "default input"),
        ResolvedParameter(parameter("ticket", TemplateParameterType.ISSUE_KEY), null, ParameterSource.STEP, ParameterBinding.START),
        ResolvedParameter(parameter("spec"), null, ParameterSource.STEP, ParameterBinding.artifact("spec"), fallback = "no spec yet"),
    )
    private val task = ResolvedTask(
        "t", "T", TestAgents.MODEL_ANSWER, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, listOf("t"), listOf("success"),
        parameters = parameters,
    )

    private fun artifact(content: String, at: java.time.Instant) = Artifact(ArtifactId.new(), "write", "spec", ArtifactKind.SPEC, content = content, createdAt = at)

    @Test
    fun `the run start form and the latest artefact give the values bound at run time`() {
        val run = Fixtures.runningRun(project, input = RunInput("SHOP-7", mapOf("input" to "pasted notes", "ticket" to "OPS-1")))
            .addArtifact(artifact("first spec", now)).addArtifact(artifact("revised spec", now.plusSeconds(5)))

        assertThat(StepParameters.values(task, run)).isEqualTo(
            mapOf("fixed" to "designed", "preset" to "from template", "input" to "pasted notes", "ticket" to "OPS-1", "spec" to "revised spec"),
        )
        assertThat(StepParameters.untrusted(task)).containsExactlyInAnyOrder("input", "ticket", "spec")
    }

    @Test
    fun `without a value in the run the fallback applies and an ISSUE_KEY parameter takes the run issue key`() {
        val run = Fixtures.runningRun(project, input = RunInput("SHOP-7", mapOf("input" to " ")))

        val values = StepParameters.values(task, run)

        assertThat(values["input"]).describedAs("a blank start value is no value").isEqualTo("default input")
        assertThat(values["ticket"]).isEqualTo("SHOP-7")
        assertThat(values["spec"]).isEqualTo("no spec yet")
        assertThat(StepParameters.values(task, Fixtures.runningRun(project, input = RunInput(null)))["ticket"]).isNull()
    }
}
