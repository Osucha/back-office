package pl.mos.backoffice.tasks.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.ResolutionCheck
import pl.mos.backoffice.tasks.domain.service.TaskResolution
import java.time.Instant
import java.util.UUID

class RestMappersTest {

    private val now = Instant.parse("2026-10-24T10:00:00Z")
    private val issue = RunInputFieldDto("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1")
    private val input = RunInputFieldDto("input", "Input material", RunInputType.MULTILINE, required = false, help = null)

    @Test
    fun `template run inputs go from the request to the domain and back to the response`() {
        val dto = TaskTemplateInputDto(key = "writer", name = "Writer", agentKey = "claude-code", scope = ScopeDto(ScopeTypeDto.GLOBAL), runInputs = listOf(issue, input))

        val content = RestMappers.templateContent(dto)
        assertThat(content.runInputs).containsExactly(
            RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1"),
            RunInputField("input", "Input material", RunInputType.MULTILINE),
        )

        val response = RestMappers.template(TaskTemplate.create(content, now))
        assertThat(response.runInputs).containsExactly(issue, input)
        assertThat(content.agentKey).isEqualTo("claude-code")
        assertThat(response.agentKey).isEqualTo("claude-code")
        assertThat(RestMappers.templateContent(dto.copy(agentKey = " ")).agentKey).describedAs("blank = inherit").isNull()
    }

    @Test
    fun `a request without run inputs declares none`() {
        val dto = TaskTemplateInputDto(key = "writer", name = "Writer", scope = ScopeDto(ScopeTypeDto.GLOBAL))

        assertThat(RestMappers.templateContent(dto).runInputs).isEmpty()
    }

    @Test
    fun `category, parameters and parameter values round trip and a blank key means derive it`() {
        val dto = TaskTemplateInputDto(
            key = " ", name = "Writer", scope = ScopeDto(ScopeTypeDto.GLOBAL),
            category = TemplateCategory.SPECIFICATION,
            parameters = listOf(TemplateParameterDto("tone", "Tone", TemplateParameterType.CHOICE, options = listOf("dry", "warm"), default = "dry", help = " ")),
            parameterValues = mapOf("tone" to "warm"),
        )
        val content = RestMappers.templateContent(dto)
        assertThat(content.key).isNull()
        assertThat(content.category).isEqualTo(TemplateCategory.SPECIFICATION)

        val response = RestMappers.template(TaskTemplate.create(content.copy(key = "writer"), now))
        assertThat(response.category).isEqualTo(TemplateCategory.SPECIFICATION)
        assertThat(response.parameters.single()).isEqualTo(TemplateParameterDto("tone", "Tone", TemplateParameterType.CHOICE, false, "dry", listOf("dry", "warm"), null))
        assertThat(response.parameterValues).isEqualTo(mapOf("tone" to "warm"))
    }

    @Test
    fun `rules map from the request and back`() {
        val projectId = UUID.randomUUID()
        val dto = RuleInputDto(name = "Be brief", content = "Short.", scope = ScopeDto(ScopeTypeDto.PROJECT, projectId), automatic = true, tags = listOf("style"))
        val content = RestMappers.ruleContent(dto)
        assertThat(content).isEqualTo(Rule.Content(null, "Be brief", "", "Short.", Scope.Project(projectId), automatic = true, tags = listOf("style")))
        assertThat(RestMappers.ruleContent(dto.copy(key = "be-brief")).key).isEqualTo("be-brief")

        val rule = Rule.create(content.copy(key = "be-brief"), now)
        val out = RestMappers.rule(rule)
        assertThat(out.id).isEqualTo(rule.id.value)
        assertThat(out.key).isEqualTo("be-brief")
        assertThat(out.scope).isEqualTo(ScopeDto(ScopeTypeDto.PROJECT, projectId))
        assertThat(out.automatic).isTrue()
        assertThat(out.tags).containsExactly("style")
        assertThat(out.updatedBy).isNull()

        assertThatThrownBy { RestMappers.ruleContent(dto.copy(scope = ScopeDto(ScopeTypeDto.PROJECT))) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the resolved view carries the merged run inputs, category, rules and the check`() {
        val resolution = TaskResolution(
            key = "writer", name = "Writer", description = "", agent = AgentSnapshot.DEFAULT, modelProfileId = null,
            prompt = TaskPrompt(null, null), skills = emptyList(), config = emptyMap(), outputSchema = null, confidenceField = null,
            produces = emptyList(), consumes = emptyList(), category = TemplateCategory.REVIEW, lineage = listOf("writer", "base-agent"),
            ports = listOf("success", "failure"), tags = emptyList(), abstract = false, projectId = null, check = ResolutionCheck.of(listOf("rule x does not exist")),
            runInputs = listOf(RunInputField("input", "Input material", RunInputType.MULTILINE)),
            rules = listOf(RuleSnapshot("auto", "Auto", "text", automatic = true), RuleSnapshot("listed", "Listed", "text")),
        )

        val dto = RestMappers.resolved(resolution)
        assertThat(dto.runInputs).containsExactly(input)
        assertThat(dto.category).isEqualTo(TemplateCategory.REVIEW)
        assertThat(dto.rules).containsExactly(ResolvedRuleDto("auto", "Auto", true), ResolvedRuleDto("listed", "Listed", false))
        assertThat(dto.check).isEqualTo(ResolutionCheckDto(false, listOf("rule x does not exist")))
        assertThat(dto.agent).isEqualTo(AgentRefDto("model-answer", "Model answer", null, null))
    }

    @Test
    fun `agents map from the request and back, and a step's agent key and the resolved agent travel`() {
        val model = UUID.randomUUID()
        val dto = AgentInputDto(key = " ", name = "Coder", description = "d", scope = ScopeDto(ScopeTypeDto.GLOBAL), harness = AgentHarness.OPENCODE, modelProfileId = model, tags = listOf("t"))
        val content = RestMappers.agentContent(dto)
        assertThat(content.key).isNull()
        assertThat(content.harness).isEqualTo(AgentHarness.OPENCODE)
        assertThat(content.modelProfileId!!.value).isEqualTo(model)

        val agent = Agent.create(content.copy(key = "coder"), now)
        val out = RestMappers.agent(agent)
        assertThat(out.id).isEqualTo(agent.id.value)
        assertThat(out.key).isEqualTo("coder")
        assertThat(out.harness).isEqualTo(AgentHarness.OPENCODE)
        assertThat(out.modelProfileId).isEqualTo(model)
        assertThat(out.scope).isEqualTo(ScopeDto(ScopeTypeDto.GLOBAL))

        assertThat(RestMappers.agentRef(AgentSnapshot.DEFAULT)).isEqualTo(AgentRefDto("model-answer", "Model answer", null, null))
        assertThat(RestMappers.overrides(TaskOverridesDto(agentKey = "coder")).agentKey).isEqualTo("coder")
        assertThat(RestMappers.overrides(TaskOverridesDto(agentKey = "")).agentKey).isNull()
    }

    @Test
    fun `ADR 0057 - agents map instructions, rules, skills and MCP servers both ways`() {
        val dto = AgentInputDto(
            name = "Env", scope = ScopeDto(ScopeTypeDto.GLOBAL), instructions = "Be precise.", rules = listOf("r1"), skills = listOf("s1"),
            mcpServers = listOf(AgentMcpServerDto("docs", "https://mcp.example.com")),
        )
        val content = RestMappers.agentContent(dto)
        assertThat(content.instructions).isEqualTo("Be precise.")
        assertThat(content.mcpServers).containsExactly(pl.mos.backoffice.shared.runner.AgentMcpServer("docs", "https://mcp.example.com"))
        val agent = RestMappers.agent(pl.mos.backoffice.tasks.domain.model.Agent.create(content.copy(key = "env"), now))
        assertThat(agent.rules).containsExactly("r1")
        assertThat(agent.skills).containsExactly("s1")
        assertThat(agent.mcpServers).containsExactly(AgentMcpServerDto("docs", "https://mcp.example.com"))
        assertThat(RestMappers.agentRef(agent.let { pl.mos.backoffice.shared.tasks.AgentSnapshot("env", "Env", rules = listOf("r1"), skills = listOf("s1")) }).skills).containsExactly("s1")
        org.assertj.core.api.Assertions.assertThatThrownBy {
            RestMappers.agentContent(dto.copy(mcpServers = listOf(AgentMcpServerDto("docs", "ftp://x"))))
        }.isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java)
    }

    @Test
    fun `ADR 0058 - step parameters and resolved parameters map with value and source`() {
        assertThat(RestMappers.overrides(TaskOverridesDto(parameters = mapOf("task" to "x"))).parameters).isEqualTo(mapOf("task" to "x"))
        val resolved = RestMappers.resolvedParameter(
            pl.mos.backoffice.shared.tasks.ResolvedParameter(
                pl.mos.backoffice.shared.tasks.TemplateParameter("task", "Task", TemplateParameterType.MULTILINE, default = "d"), "d",
                pl.mos.backoffice.shared.tasks.ParameterSource.DEFAULT,
            ),
        )
        assertThat(resolved).isEqualTo(ResolvedParameterDto("task", "Task", TemplateParameterType.MULTILINE, false, "d", emptyList(), null, "d", pl.mos.backoffice.shared.tasks.ParameterSource.DEFAULT))
    }

}
