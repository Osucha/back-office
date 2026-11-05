package pl.mos.backoffice.tasks.adapter.`in`.rest

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.TaskOverrides
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

    fun templateContent(dto: TaskTemplateInputDto) = TaskTemplate.Content(
        key = dto.key?.takeIf { it.isNotBlank() },
        name = dto.name,
        description = dto.description,
        abstract = dto.abstract,
        extendsKey = dto.extendsKey,
        category = dto.category,
        executor = dto.executor,
        scope = scope(dto.scope),
        modelProfileId = dto.modelProfileId?.let(::ModelProfileId),
        prompt = Prompt(dto.prompt.system, dto.prompt.user, dto.prompt.mode),
        skills = dto.skills,
        removeSkills = dto.removeSkills,
        rules = dto.rules,
        removeRules = dto.removeRules,
        config = dto.config,
        outputSchema = dto.outputSchema,
        confidenceField = dto.confidenceField,
        produces = dto.produces,
        consumes = dto.consumes,
        tags = dto.tags,
        runInputs = dto.runInputs.map(::runInput),
    )

    fun template(t: TaskTemplate) = TaskTemplateDto(
        id = t.id.value,
        key = t.key,
        name = t.name,
        description = t.description,
        abstract = t.abstract,
        extendsKey = t.extendsKey,
        category = t.category,
        executor = t.executor,
        scope = scope(t.scope),
        modelProfileId = t.modelProfileId?.value,
        prompt = TaskPromptDto(t.prompt.system, t.prompt.user, t.prompt.mode),
        skills = t.skills,
        removeSkills = t.removeSkills,
        rules = t.rules,
        removeRules = t.removeRules,
        config = t.config,
        outputSchema = t.outputSchema,
        confidenceField = t.confidenceField,
        produces = t.produces,
        consumes = t.consumes,
        tags = t.tags,
        runInputs = t.runInputs.map(::runInput),
        builtIn = t.builtIn,
        version = t.version,
        createdAt = t.createdAt,
        updatedAt = t.updatedAt,
        updatedBy = ActorRefDto.of(t.updatedBy),
    )

    fun overrides(o: TaskOverridesDto) = TaskOverrides(
        modelProfileId = o.modelProfileId?.let(::ModelProfileId),
        systemPromptAppend = o.systemPromptAppend,
        promptAppend = o.promptAppend,
        promptReplace = o.promptReplace,
        addSkills = o.addSkills,
        removeSkills = o.removeSkills,
        config = o.config,
    )

    fun resolved(r: TaskResolution) = ResolvedTaskDto(
        key = r.key,
        name = r.name,
        executor = r.executor,
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
        abstract = r.abstract,
        check = ResolutionCheckDto(r.check.ok, r.check.problems),
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
