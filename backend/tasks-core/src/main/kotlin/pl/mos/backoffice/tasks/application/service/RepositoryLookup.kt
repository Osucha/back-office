package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.LibraryLookup
import pl.mos.backoffice.tasks.domain.service.TaskResolver
import pl.mos.backoffice.tasks.domain.service.TemplateValidator

/** `LibraryLookup` over the four repositories; the one instance the resolver and validator share. */
class RepositoryLookup(
    private val templates: TaskTemplateRepository,
    private val skills: SkillRepository,
    private val rules: RuleRepository,
    private val agents: AgentRepository,
) : LibraryLookup {
    override fun template(key: String, scope: Scope): TaskTemplate? = templates.findByKeyAndScope(key, scope)
    override fun skill(key: String, scope: Scope): Skill? = skills.findByKeyAndScope(key, scope)
    override fun rule(key: String, scope: Scope): Rule? = rules.findByKeyAndScope(key, scope)
    override fun agent(key: String, scope: Scope): Agent? = agents.findByKeyAndScope(key, scope)
    override fun automaticRules(scope: Scope): List<Rule> = rules.findAutomatic(scope).sortedBy { it.key }

    val resolver: TaskResolver by lazy { TaskResolver(this) }
    val validator: TemplateValidator by lazy { TemplateValidator(this) }
}
