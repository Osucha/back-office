package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary.SeedResult
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate

/**
 * Creates the built-in library ([BuiltInLibrary]) entry by entry in the `GLOBAL` scope and keeps the entries nobody
 * edited in line with the shipped definition. Seeded entries are stamped `updatedBy = system`.
 *
 * - A missing key is created (deleting a non-built-in copy and restarting restores the shipped version).
 * - A stored built-in the owner never edited is upgraded when its content checksum ([BuiltInChecksum], over every
 *   editable field, with null config values normalised) differs from the shipped one: templates, skills and
 *   interfaces alike. Identity, version and creation time stay.
 * - An entry a person edited, or one that is not built-in, is never touched.
 *
 * "Never edited" follows the audit stamps: built-in, last saved by the system (or by nobody, rows older than the
 * actor stamps) and, for templates, still version 1 (every save through the API bumps the version).
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
        var interfacesUpgraded = 0
        BuiltInLibrary.interfaces(now).forEach { shipped ->
            val existing = interfaces.findByKey(shipped.key)
            when {
                existing == null -> { interfaces.save(shipped); interfacesCreated++ }
                untouched(existing) && BuiltInChecksum.of(existing) != BuiltInChecksum.of(shipped) -> {
                    interfaces.save(existing.copy(name = shipped.name, description = shipped.description, inputs = shipped.inputs, outputs = shipped.outputs, ports = shipped.ports, updatedAt = now, updatedBy = Actor.SYSTEM))
                    interfacesUpgraded++
                }
            }
        }
        var skillsCreated = 0
        var skillsUpgraded = 0
        BuiltInLibrary.skills(now).forEach { shipped ->
            val existing = skills.findByKeyAndScope(shipped.key, Scope.Global)
            when {
                existing == null -> { skills.save(shipped); skillsCreated++ }
                untouched(existing) && BuiltInChecksum.of(existing) != BuiltInChecksum.of(shipped) -> {
                    skills.save(existing.copy(name = shipped.name, description = shipped.description, content = shipped.content, tags = shipped.tags, updatedAt = now, updatedBy = Actor.SYSTEM))
                    skillsUpgraded++
                }
            }
        }
        var templatesCreated = 0
        var templatesUpgraded = 0
        BuiltInLibrary.templates(now).forEach { shipped ->
            val existing = templates.findByKeyAndScope(shipped.key, Scope.Global)
            when {
                existing == null -> { templates.save(shipped); templatesCreated++ }
                untouched(existing) && BuiltInChecksum.of(existing) != BuiltInChecksum.of(shipped) -> {
                    // the whole shipped content (prompts, skills, config, schema, produces, parent...) replaces the old one
                    templates.save(shipped.copy(id = existing.id, version = existing.version, createdAt = existing.createdAt, updatedAt = now, updatedBy = Actor.SYSTEM))
                    templatesUpgraded++
                }
            }
        }
        return SeedResult(interfacesCreated, templatesCreated, skillsCreated, templatesUpgraded, skillsUpgraded, interfacesUpgraded)
    }

    private fun untouched(t: TaskTemplate) = untouched(t.builtIn, t.updatedBy) && t.version == 1

    private fun untouched(s: Skill) = untouched(s.builtIn, s.updatedBy)

    private fun untouched(i: TaskInterface) = untouched(i.builtIn, i.updatedBy)

    private fun untouched(builtIn: Boolean, updatedBy: Actor?) = builtIn && (updatedBy == null || updatedBy.kind == PrincipalKind.SYSTEM)
}
