package pl.mos.backoffice.tasks.support

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.tasks.application.service.BuiltInLibrary
import pl.mos.backoffice.tasks.application.service.RepositoryLookup
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.tasks.domain.model.InstructionsMode
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.time.Instant
import java.util.UUID

/** An in-memory library with helpers to populate it tersely; the built-in agents (ADR 0056) unless [builtInAgents] is off. */
class Library(builtInAgents: Boolean = true) {
    val now: Instant = Instant.parse("2026-09-26T10:00:00Z")
    val clock: Clock = Clock.fixed(now)
    val templates = InMemoryTaskTemplateRepository()
    val skills = InMemorySkillRepository()
    val rules = InMemoryRuleRepository()
    val agents = InMemoryAgentRepository()
    val lookup = RepositoryLookup(templates, skills, rules, agents)
    val projectId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    init {
        // plain built-in agents: their rules and skills are not in this library unless a test adds them
        if (builtInAgents) BuiltInLibrary.agents(now).forEach { agents.save(it.copy(rules = emptyList(), skills = emptyList())) }
    }

    fun agent(
        key: String,
        harness: AgentHarness? = null,
        scope: Scope = Scope.Global,
        name: String = key,
        rules: List<String> = emptyList(),
        skills: List<String> = emptyList(),
        instructions: String = "",
        access: AgentAccess = AgentAccess.FULL,
        session: AgentSession = AgentSession.PER_STEP,
    ): Agent =
        agents.save(Agent.create(Agent.Content(key = key, name = name, scope = scope, harness = harness, rules = rules, skills = skills, instructions = instructions, access = access, session = session), now))

    fun skill(key: String, scope: Scope = Scope.Global, content: String = "# $key"): Skill =
        skills.save(Skill.create(Skill.Content(key = key, name = key, content = content, scope = scope), now))

    fun rule(key: String, scope: Scope = Scope.Global, automatic: Boolean = false, content: String = "Rule $key"): Rule =
        rules.save(Rule.create(Rule.Content(key = key, name = key, content = content, scope = scope, automatic = automatic), now))

    fun template(
        key: String,
        extendsKey: String? = null,
        abstract: Boolean = false,
        scope: Scope = Scope.Global,
        category: TemplateCategory? = null,
        instructions: String? = null,
        instructionsMode: InstructionsMode = InstructionsMode.APPEND,
        config: Map<String, Any?> = emptyMap(),
        outputSchema: String? = null,
        confidenceField: String? = null,
        produces: List<String> = emptyList(),
        consumes: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        name: String = key,
        parameters: List<TemplateParameter> = emptyList(),
        parameterValues: Map<String, Any?> = emptyMap(),
        requiresSkills: List<String> = emptyList(),
        outputFiles: List<String> = emptyList(),
    ): TaskTemplate = templates.save(
        TaskTemplate.create(
            TaskTemplate.Content(
                key = key, name = name, abstract = abstract, extendsKey = extendsKey, category = category,
                scope = scope, instructions = instructions, instructionsMode = instructionsMode, config = config, outputSchema = outputSchema,
                confidenceField = confidenceField, produces = produces, consumes = consumes, tags = tags,
                parameters = parameters, parameterValues = parameterValues, requiresSkills = requiresSkills, outputFiles = outputFiles,
            ),
            now,
        ),
    )
}
