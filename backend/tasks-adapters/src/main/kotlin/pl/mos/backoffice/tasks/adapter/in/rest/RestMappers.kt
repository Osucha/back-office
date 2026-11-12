package pl.mos.backoffice.tasks.adapter.`in`.rest

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.TaskResolution

/** DTO <-> command / domain mapping for the task-library controllers. */
object RestMappers {

    fun scope(dto: ScopeDto): Scope = when (dto.type) {
        ScopeTypeDto.GLOBAL -> Scope.Global
        ScopeTypeDto.PROJECT -> Scope.Project(dto.projectId ?: throw ValidationException("required for PROJECT scope", "scope.projectId"))
    }

    fun scope(scope: Scope): ScopeDto = when (scope) {
        Scope.Global -> ScopeDto(ScopeTypeDto.GLOBAL)
        is Scope.Project -> ScopeDto(ScopeTypeDto.PROJECT, scope.projectId)
    }

    private fun runInput(dto: RunInputFieldDto) = RunInputField(dto.name, dto.label, dto.type, dto.required, dto.help?.takeIf { it.isNotBlank() })
    private fun runInput(f: RunInputField) = RunInputFieldDto(f.name, f.label, f.type, f.required, f.help)

    fun parameter(dto: TemplateParameterDto) = TemplateParameter(dto.name, dto.label, dto.type, dto.required, dto.default, dto.options, dto.help?.takeIf { it.isNotBlank() })
    fun parameter(p: TemplateParameter) = TemplateParameterDto(p.name, p.label, p.type, p.required, p.default, p.options, p.help)
    fun resolvedParameter(p: ResolvedParameter) = ResolvedParameterDto(
        p.definition.name, p.definition.label, p.definition.type, p.definition.required, p.definition.default, p.definition.options,
        p.definition.help, p.value, p.source,
    )

    fun templateContent(dto: TaskTemplateInputDto) = TaskTemplate.Content(
        key = dto.key?.takeIf { it.isNotBlank() },
        name = dto.name,
        description = dto.description,
        abstract = dto.abstract,
        extendsKey = dto.extendsKey,
        category = dto.category,
        scope = scope(dto.scope),
        prompt = Prompt(dto.prompt.system, dto.prompt.user, dto.prompt.mode),
        config = dto.config,
        outputSchema = dto.outputSchema,
        confidenceField = dto.confidenceField,
        produces = dto.produces,
        consumes = dto.consumes,
        tags = dto.tags,
        runInputs = dto.runInputs.map(::runInput),
        parameters = dto.parameters.map(::parameter),
        parameterValues = dto.parameterValues,
        requiresSkills = dto.requiresSkills,
    )

    /** [needsCodingAgent]: ADR 0061, derived from the merged config by the use case. */
    fun template(t: TaskTemplate, needsCodingAgent: Boolean = false) = TaskTemplateDto(
        id = t.id.value,
        key = t.key,
        name = t.name,
        description = t.description,
        abstract = t.abstract,
        extendsKey = t.extendsKey,
        category = t.category,
        scope = scope(t.scope),
        prompt = TaskPromptDto(t.prompt.system, t.prompt.user, t.prompt.mode),
        config = t.config,
        outputSchema = t.outputSchema,
        confidenceField = t.confidenceField,
        produces = t.produces,
        consumes = t.consumes,
        tags = t.tags,
        runInputs = t.runInputs.map(::runInput),
        parameters = t.parameters.map(::parameter),
        parameterValues = t.parameterValues,
        requiresSkills = t.requiresSkills,
        needsCodingAgent = needsCodingAgent,
        builtIn = t.builtIn,
        version = t.version,
        createdAt = t.createdAt,
        updatedAt = t.updatedAt,
        updatedBy = ActorRefDto.of(t.updatedBy),
    )

    fun overrides(o: TaskOverridesDto) = TaskOverrides(
        agentKey = o.agentKey?.takeIf { it.isNotBlank() },
        modelProfileId = o.modelProfileId?.let(::ModelProfileId),
        systemPromptAppend = o.systemPromptAppend,
        promptAppend = o.promptAppend,
        promptReplace = o.promptReplace,
        config = o.config,
        parameters = o.parameters,
    )

    fun resolved(r: TaskResolution) = ResolvedTaskDto(
        key = r.key,
        name = r.name,
        agent = agentRef(r.agent),
        modelProfileId = r.modelProfileId?.value,
        prompt = ResolvedPromptDto(r.prompt.system, r.prompt.user),
        skills = r.skills.map { ResolvedSkillDto(it.key, it.name) },
        config = r.config,
        outputSchema = r.outputSchema,
        confidenceField = r.confidenceField,
        produces = r.produces,
        consumes = r.consumes,
        category = r.category,
        rules = r.rules.map { ResolvedRuleDto(it.key, it.name, it.automatic) },
        lineage = r.lineage,
        ports = r.ports,
        runInputs = r.runInputs.map(::runInput),
        parameters = r.parameters.map(::resolvedParameter),
        abstract = r.abstract,
        check = ResolutionCheckDto(r.check.ok, r.check.problems, r.check.warnings),
    )

    fun agentRef(a: AgentSnapshot) = AgentRefDto(a.key, a.name, a.harness, a.modelProfileId?.value, a.rules, a.skills)

    fun agentContent(dto: AgentInputDto) = Agent.Content(
        key = dto.key?.takeIf { it.isNotBlank() }, name = dto.name, description = dto.description, scope = scope(dto.scope),
        harness = dto.harness, modelProfileId = dto.modelProfileId?.let(::ModelProfileId), tags = dto.tags,
        instructions = dto.instructions, rules = dto.rules, skills = dto.skills, mcpServers = dto.mcpServers.map { AgentMcpServer(it.name, it.url) },
        access = dto.access?.let { AgentAccess(it.mode, if (it.mode == AgentAccessMode.PATHS) it.paths.map(String::trim) else emptyList()) } ?: AgentAccess.FULL,
    )

    fun agent(a: Agent) = AgentDto(
        id = a.id.value, key = a.key, name = a.name, description = a.description, scope = scope(a.scope), harness = a.harness,
        modelProfileId = a.modelProfileId?.value, instructions = a.instructions, rules = a.rules, skills = a.skills,
        mcpServers = a.mcpServers.map { AgentMcpServerDto(it.name, it.url) },
        access = AgentAccessDto(a.access.mode, a.access.paths),
        tags = a.tags, builtIn = a.builtIn, createdAt = a.createdAt, updatedAt = a.updatedAt,
        updatedBy = ActorRefDto.of(a.updatedBy),
    )

    fun skillContent(dto: SkillInputDto) = Skill.Content(
        key = dto.key?.takeIf { it.isNotBlank() }, name = dto.name, description = dto.description, content = dto.content, scope = scope(dto.scope), tags = dto.tags,
    )

    fun skill(s: Skill) = SkillDto(
        id = s.id.value, key = s.key, name = s.name, description = s.description, content = s.content,
        scope = scope(s.scope), tags = s.tags, builtIn = s.builtIn, createdAt = s.createdAt, updatedAt = s.updatedAt,
        updatedBy = ActorRefDto.of(s.updatedBy),
    )

    fun ruleContent(dto: RuleInputDto) = Rule.Content(
        key = dto.key?.takeIf { it.isNotBlank() }, name = dto.name, description = dto.description, content = dto.content,
        scope = scope(dto.scope), automatic = dto.automatic, tags = dto.tags,
    )

    fun rule(r: Rule) = RuleDto(
        id = r.id.value, key = r.key, name = r.name, description = r.description, content = r.content, scope = scope(r.scope),
        automatic = r.automatic, tags = r.tags, builtIn = r.builtIn, createdAt = r.createdAt, updatedAt = r.updatedAt,
        updatedBy = ActorRefDto.of(r.updatedBy),
    )
}
