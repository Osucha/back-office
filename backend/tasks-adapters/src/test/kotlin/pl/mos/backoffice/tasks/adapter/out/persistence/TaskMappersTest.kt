package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import java.util.UUID
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
            TaskTemplate.Content(key = "story-writer", name = "Story writer", agentKey = "opencode", runInputs = inputs),
            Instant.parse("2026-10-24T10:00:00Z"),
        )

        val entity = mappers.toEntity(template)
        val back = mappers.toDomain(entity)

        assertThat(back.runInputs).isEqualTo(inputs)
        assertThat(back).isEqualTo(template)
    }

    @Test
    fun `a row without run inputs reads as an empty list`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain"), Instant.parse("2026-10-24T10:00:00Z"))

        val entity = mappers.toEntity(template)

        assertThat(entity.runInputs).isEqualTo("[]")
        assertThat(mappers.toDomain(entity).runInputs).isEmpty()
    }

    @Test
    fun `malformed stored run inputs are skipped instead of breaking the template`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain"), Instant.parse("2026-10-24T10:00:00Z"))
        val stored = mappers.toEntity(template)
        val broken = TaskTemplateEntity(
            id = stored.id, key = stored.key, name = stored.name, agentKey = stored.agentKey, createdAt = stored.createdAt, updatedAt = stored.updatedAt,
            runInputs = """["text", {"label": "No name"}, {"name": "has space", "label": "Bad"}, {"name": "audience", "label": "Audience", "type": "FANCY"},
                {"name": "issueKey", "label": "Issue", "type": "ISSUE_KEY", "required": true}]""",
        )

        val inputs = mappers.toDomain(broken).runInputs

        assertThat(inputs).containsExactly(
            RunInputField("audience", "Audience", RunInputType.TEXT),
            RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY, required = true),
        )
    }

    @Test
    fun `template category, rules and removeRules survive the entity`() {
        val template = TaskTemplate.create(
            TaskTemplate.Content(key = "cat", name = "Cat", category = TemplateCategory.PLANNING, rules = listOf("r1", "r2"), removeRules = listOf("r3")),
            Instant.parse("2026-10-24T10:00:00Z"),
        )
        val entity = mappers.toEntity(template)
        assertThat(entity.rules).isEqualTo("[\"r1\",\"r2\"]")
        assertThat(mappers.toDomain(entity)).isEqualTo(template)
    }

    @Test
    fun `a rule survives domain to entity to domain in both scopes`() {
        val now = Instant.parse("2026-10-24T10:00:00Z")
        val global = Rule.create(Rule.Content(key = "be-brief", name = "Be brief", description = "d", content = "Short.", automatic = true, tags = listOf("a")), now, builtIn = true, actor = Actor.SYSTEM)
        val project = Rule.create(Rule.Content(key = "be-brief", name = "P", content = "Shorter.", scope = Scope.Project(UUID.randomUUID())), now)

        assertThat(mappers.toDomain(mappers.toEntity(global))).isEqualTo(global)
        assertThat(mappers.toDomain(mappers.toEntity(project))).isEqualTo(project)
    }

    @Test
    fun `an agent survives domain to entity to domain in both scopes, with and without harness and model`() {
        val now = Instant.parse("2026-11-06T10:00:00Z")
        val coder = Agent.create(Agent.Content(key = "coder", name = "Coder", description = "d", harness = AgentHarness.CLAUDE_CODE, modelProfileId = ModelProfileId.new(), tags = listOf("t")), now)
        val answer = Agent.create(Agent.Content(key = "answer", name = "Answer", scope = Scope.Project(java.util.UUID.randomUUID())), now, builtIn = true)

        assertThat(mappers.toDomain(mappers.toEntity(coder))).isEqualTo(coder)
        assertThat(mappers.toDomain(mappers.toEntity(answer))).isEqualTo(answer)
        assertThat(mappers.toEntity(coder).storedAsGlobalCoder()).isTrue()
        assertThat(mappers.toEntity(answer).harness).isNull()
    }

    private fun AgentEntity.storedAsGlobalCoder() = harness == AgentHarness.CLAUDE_CODE && scopeType == ScopeType.GLOBAL && tags == "[\"t\"]"

    @Test
    fun `a template agent key survives the entity`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain", agentKey = "claude-code"), Instant.parse("2026-10-24T10:00:00Z"))
        assertThat(mappers.toEntity(template).agentKey).isEqualTo("claude-code")
        assertThat(mappers.toDomain(mappers.toEntity(template)).agentKey).isEqualTo("claude-code")
    }
}
