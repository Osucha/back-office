package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.tasks.domain.model.InstructionsMode
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
    fun `ADR 0062 - template instructions and their mode survive domain to entity to domain`() {
        val template = TaskTemplate.create(
            TaskTemplate.Content(key = "story-writer", name = "Story writer", instructions = "Write the story.", instructionsMode = InstructionsMode.REPLACE),
            Instant.parse("2026-10-24T10:00:00Z"),
        )

        val entity = mappers.toEntity(template)
        assertThat(entity.instructions).isEqualTo("Write the story.")
        assertThat(entity.instructionsMode).isEqualTo(InstructionsMode.REPLACE)
        assertThat(mappers.toDomain(entity)).isEqualTo(template)
    }

    @Test
    fun `ADR 0062 - an agent's session and directory survive the entity, old rows read PER_STEP and RUN, unknown ones safely`() {
        val agent = Agent.create(
            Agent.Content(
                key = "loner", name = "Loner", harness = AgentHarness.OPENCODE,
                access = AgentAccess(AgentAccessMode.READ_ONLY, directory = AgentDirectory.SEPARATE), session = AgentSession.FRESH,
            ),
            Instant.parse("2026-10-24T10:00:00Z"),
        )
        val entity = mappers.toEntity(agent)
        assertThat(entity.session).isEqualTo("FRESH")
        assertThat(mappers.toDomain(entity)).isEqualTo(agent)

        fun read(access: String, session: String) = mappers.toDomain(
            AgentEntity(id = entity.id, key = entity.key, name = entity.name, createdAt = entity.createdAt, updatedAt = entity.updatedAt, access = access, session = session),
        )
        val old = read("""{"mode": "FULL"}""", "PER_STEP")
        assertThat(old.access).isEqualTo(AgentAccess.FULL)
        assertThat(old.session).isEqualTo(AgentSession.PER_STEP)
        val odd = read("""{"mode": "FULL", "directory": "ELSEWHERE"}""", "SOMETIMES")
        assertThat(odd.access.directory).describedAs("an unknown directory sees less").isEqualTo(AgentDirectory.SEPARATE)
        assertThat(odd.session).isEqualTo(AgentSession.PER_STEP)
        assertThat(read("""{"mode": "ODD", "directory": "SEPARATE"}""", "PER_RUN").access).isEqualTo(AgentAccess.READ_ONLY)
    }

    @Test
    fun `template category, parameters and parameter values survive the entity`() {
        val template = TaskTemplate.create(
            TaskTemplate.Content(
                key = "cat", name = "Cat", category = TemplateCategory.PLANNING,
                parameters = listOf(
                    pl.mos.backoffice.shared.tasks.TemplateParameter("depth", "Depth", pl.mos.backoffice.shared.tasks.TemplateParameterType.NUMBER, true, 3),
                    pl.mos.backoffice.shared.tasks.TemplateParameter("tone", "Tone", pl.mos.backoffice.shared.tasks.TemplateParameterType.CHOICE, options = listOf("a", "b"), help = "h"),
                ),
                parameterValues = mapOf("tone" to "b"),
            ),
            Instant.parse("2026-10-24T10:00:00Z"),
        )
        val entity = mappers.toEntity(template)
        assertThat(entity.parameterValues).isEqualTo("{\"tone\":\"b\"}")
        assertThat(mappers.toDomain(entity)).isEqualTo(template)
        val broken = TaskTemplateEntity(key = "cat", name = "Cat", parameters = """[{"name":"1bad","label":"x"},{"label":"no name"},"x",{"name":"ok","type":"WHAT"}]""")
        assertThat(mappers.toDomain(broken).parameters.map { it.name to it.type.name }).describedAs("bad entries are skipped, an unknown type reads as TEXT")
            .containsExactly("ok" to "TEXT")
    }

    @Test
    fun `ADR 0057 - an agent's environment survives the entity and a bad stored MCP server is skipped`() {
        val agent = pl.mos.backoffice.tasks.domain.model.Agent.create(
            pl.mos.backoffice.tasks.domain.model.Agent.Content(
                key = "env", name = "Env", instructions = "Be precise.", rules = listOf("r1"), skills = listOf("s1", "s2"),
                mcpServers = listOf(pl.mos.backoffice.shared.runner.AgentMcpServer("docs", "https://mcp.example.com")),
            ),
            Instant.parse("2026-10-24T10:00:00Z"),
        )
        val entity = mappers.toEntity(agent)
        assertThat(entity.mcpServers).isEqualTo("""[{"name":"docs","url":"https://mcp.example.com"}]""")
        assertThat(mappers.toDomain(entity)).isEqualTo(agent)
        val broken = AgentEntity(key = "env", name = "Env", mcpServers = """[{"name":"ok","url":"https://a.example"},{"name":"bad","url":"file:///x"},{"url":"https://b"}]""")
        assertThat(mappers.toDomain(broken).mcpServers.map { it.name }).containsExactly("ok")
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
    fun `ADR 0061 - required skills survive the entity`() {
        val template = TaskTemplate.create(TaskTemplate.Content(key = "plain", name = "Plain", requiresSkills = listOf("adr-format")), Instant.parse("2026-10-24T10:00:00Z"))
        assertThat(mappers.toDomain(mappers.toEntity(template)).requiresSkills).containsExactly("adr-format")
    }
}
