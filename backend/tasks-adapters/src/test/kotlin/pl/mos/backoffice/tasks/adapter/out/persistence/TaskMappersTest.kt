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

    @Test
    fun `malformed stored run inputs are skipped instead of breaking the template`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain", executor = TaskExecutor.LLM), Instant.parse("2026-10-24T10:00:00Z"))
        val stored = mappers.toEntity(template)
        val broken = TaskTemplateEntity(
            id = stored.id, key = stored.key, name = stored.name, executor = stored.executor, createdAt = stored.createdAt, updatedAt = stored.updatedAt,
            runInputs = """["text", {"label": "No name"}, {"name": "has space", "label": "Bad"}, {"name": "audience", "label": "Audience", "type": "FANCY"},
                {"name": "issueKey", "label": "Issue", "type": "ISSUE_KEY", "required": true}]""",
        )

        val inputs = mappers.toDomain(broken).runInputs

        assertThat(inputs).containsExactly(
            RunInputField("audience", "Audience", RunInputType.TEXT),
            RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY, required = true),
        )
    }
}
