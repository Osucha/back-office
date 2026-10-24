package pl.mos.backoffice.tasks.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.InterfaceCheck
import pl.mos.backoffice.tasks.domain.service.TaskResolution
import java.time.Instant

class RestMappersTest {

    private val issue = RunInputFieldDto("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1")
    private val input = RunInputFieldDto("input", "Input material", RunInputType.MULTILINE, required = false, help = null)

    @Test
    fun `template run inputs go from the request to the domain and back to the response`() {
        val dto = TaskTemplateInputDto(key = "writer", name = "Writer", executor = TaskExecutor.LLM, scope = ScopeDto(ScopeTypeDto.GLOBAL), runInputs = listOf(issue, input))

        val content = RestMappers.templateContent(dto)
        assertThat(content.runInputs).containsExactly(
            RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1"),
            RunInputField("input", "Input material", RunInputType.MULTILINE),
        )

        val response = RestMappers.template(TaskTemplate.create(content, Instant.parse("2026-10-24T10:00:00Z")))
        assertThat(response.runInputs).containsExactly(issue, input)
    }

    @Test
    fun `a request without run inputs declares none`() {
        val dto = TaskTemplateInputDto(key = "writer", name = "Writer", executor = TaskExecutor.LLM, scope = ScopeDto(ScopeTypeDto.GLOBAL))

        assertThat(RestMappers.templateContent(dto).runInputs).isEmpty()
    }

    @Test
    fun `the resolved view carries the merged run inputs`() {
        val resolution = TaskResolution(
            key = "writer", name = "Writer", description = "", executor = TaskExecutor.LLM, modelProfileId = null,
            prompt = TaskPrompt(null, null), skills = emptyList(), config = emptyMap(), outputSchema = null, confidenceField = null,
            produces = emptyList(), consumes = emptyList(), interfaces = emptyList(), lineage = listOf("writer", "base-agent"),
            ports = listOf("success", "failure"), tags = emptyList(), abstract = false, projectId = null, interfaceCheck = InterfaceCheck.OK,
            runInputs = listOf(RunInputField("input", "Input material", RunInputType.MULTILINE)),
        )

        assertThat(RestMappers.resolved(resolution).runInputs).containsExactly(input)
    }
}
