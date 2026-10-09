package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.service.LibraryLookup
import pl.mos.backoffice.tasks.domain.service.TaskResolver
import pl.mos.backoffice.tasks.domain.service.TemplateValidator

/** `LibraryLookup` over the three repositories; the one instance the resolver and validator share. */
class RepositoryLookup(
    private val templates: TaskTemplateRepository,
    private val interfaces: TaskInterfaceRepository,
    private val skills: SkillRepository,
) : LibraryLookup {
    override fun template(key: String, scope: Scope): TaskTemplate? = templates.findByKeyAndScope(key, scope)
    override fun taskInterface(key: String): TaskInterface? = interfaces.findByKey(key)
    override fun skill(key: String, scope: Scope): Skill? = skills.findByKeyAndScope(key, scope)

    val resolver: TaskResolver by lazy { TaskResolver(this) }
    val validator: TemplateValidator by lazy { TemplateValidator(this) }
}
