package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class TaskMappersTest {

    private val mappers = TaskMappers(JsonMapper.builder().build())

    @Test
    fun `template run inputs survive domain to entity to domain, every type and a missing help`() {
        val inputs = listOf(
            RunInputField("input", "Input material", RunInputType.MULTILINE, required = false, help = "anything the agent should read"),
            RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = null),
            RunInputField("branch", "Branch", RunInputType.TEXT),
        )
        val template = TaskTemplate.create(
            TaskTemplate.Content(key = "story-writer", name = "Story writer", executor = TaskExecutor.LLM, runInputs = inputs),
            Instant.parse("2026-10-24T10:00:00Z"),
        )

        val entity = mappers.toEntity(template)
        val back = mappers.toDomain(entity)

        assertThat(back.runInputs).isEqualTo(inputs)
        assertThat(back).isEqualTo(template)
    }

    @Test
    fun `a row without run inputs reads as an empty list`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain", executor = TaskExecutor.LLM), Instant.parse("2026-10-24T10:00:00Z"))

        val entity = mappers.toEntity(template)

        assertThat(entity.runInputs).isEqualTo("[]")
        assertThat(mappers.toDomain(entity).runInputs).isEmpty()
    }
}
