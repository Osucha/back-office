package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary.SeedResult
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.TaskTemplate

/**
 * Creates the built-in library ([BuiltInLibrary]) entry by entry, skipping every key that already exists
 * in the `GLOBAL` scope. Existing entries are never overwritten, so the owner's edits survive restarts
 * and upgrades; deleting a non-built-in copy and restarting restores the shipped version. Seeded entries
 * are stamped `updatedBy = system`.
 */
class SeedTaskLibraryService(
    private val interfaces: TaskInterfaceRepository,
    private val templates: TaskTemplateRepository,
    private val skills: SkillRepository,
    private val clock: Clock,
) : SeedTaskLibrary {

    @SystemOperation("startup seeding of the built-in task library; never exposed through a controller")
    override fun seed(): SeedResult {
        val now = clock.now()
        var interfacesCreated = 0
        var skillsCreated = 0
        var templatesCreated = 0
        BuiltInLibrary.interfaces(now).forEach {
            if (interfaces.findByKey(it.key) == null) { interfaces.save(it); interfacesCreated++ }
        }
        BuiltInLibrary.skills(now).forEach {
            if (skills.findByKeyAndScope(it.key, Scope.Global) == null) { skills.save(it); skillsCreated++ }
        }
        var templatesUpgraded = 0
        BuiltInLibrary.templates(now).forEach { shipped ->
            val existing = templates.findByKeyAndScope(shipped.key, Scope.Global)
            when {
                existing == null -> { templates.save(shipped); templatesCreated++ }
                isUntouchedBuiltIn(existing) &&
                    ((shipped.skills - existing.skills.toSet()).isNotEmpty() || shipped.config != existing.config ||
                        shipped.prompt != existing.prompt || shipped.description != existing.description || shipped.produces != existing.produces) -> {
                    // A never-edited built-in (version 1) follows the shipped definition: skills added later (e.g. the
                    // SDLC ground rules on base-agent, ADR 0019), corrected config (e.g. the Notify Jira message
                    // template), prompts, description and outputs. Edited copies stay as the owner left them.
                    templates.save(
                        existing.copy(
                            skills = (existing.skills + shipped.skills).distinct(), config = shipped.config, prompt = shipped.prompt,
                            description = shipped.description, produces = shipped.produces, updatedAt = now,
                        ),
                    )
                    templatesUpgraded++
                }
            }
        }
        return SeedResult(interfacesCreated, templatesCreated, skillsCreated, templatesUpgraded)
    }

    private fun isUntouchedBuiltIn(t: TaskTemplate) =
        t.builtIn && t.version == 1 && (t.updatedBy == null || t.updatedBy.kind == PrincipalKind.SYSTEM)
}
