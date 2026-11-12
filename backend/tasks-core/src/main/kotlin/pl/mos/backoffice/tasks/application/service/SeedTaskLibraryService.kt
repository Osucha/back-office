package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary.SeedResult
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate

/**
 * Creates the built-in library ([BuiltInLibrary]) entry by entry in the `GLOBAL` scope and keeps the entries nobody
 * edited in line with the shipped definition. Seeded entries are stamped `updatedBy = system`.
 *
 * - A missing key is created (deleting a non-built-in copy and restarting restores the shipped version).
 * - A stored built-in the owner never edited is upgraded when its content checksum ([BuiltInChecksum], over every
 *   editable field, with null config values normalised) differs from the shipped one: templates, skills and
 *   rules and agents alike. Identity, version and creation time stay.
 * - ADR 0056: the former built-in action templates ([BuiltInLibrary.formerActionTemplateKeys]) are removed while still
 *   built in, global and not extended by another template (their steps read as action nodes).
 * - An entry a person edited, or one that is not built-in, is never touched.
 *
 * "Never edited" follows the audit stamps: built-in, last saved by the system (or by nobody, rows older than the
 * actor stamps) and, for templates, still version 1 (every save through the API bumps the version).
 */
class SeedTaskLibraryService(
    private val templates: TaskTemplateRepository,
    private val skills: SkillRepository,
    private val rules: RuleRepository,
    private val agents: AgentRepository,
    private val clock: Clock,
) : SeedTaskLibrary {

    @SystemOperation("startup seeding of the built-in task library; never exposed through a controller")
    override fun seed(): SeedResult {
        val now = clock.now()
        var rulesCreated = 0
        var rulesUpgraded = 0
        BuiltInLibrary.rules(now).forEach { shipped ->
            val existing = rules.findByKeyAndScope(shipped.key, Scope.Global)
            when {
                existing == null -> { rules.save(shipped); rulesCreated++ }
                untouched(existing) && BuiltInChecksum.of(existing) != BuiltInChecksum.of(shipped) -> {
                    rules.save(existing.copy(name = shipped.name, description = shipped.description, content = shipped.content, automatic = shipped.automatic, tags = shipped.tags, updatedAt = now, updatedBy = Actor.SYSTEM))
                    rulesUpgraded++
                }
            }
        }
        var agentsCreated = 0
        var agentsUpgraded = 0
        BuiltInLibrary.agents(now).forEach { shipped ->
            val existing = agents.findByKeyAndScope(shipped.key, Scope.Global)
            when {
                existing == null -> { agents.save(shipped); agentsCreated++ }
                untouched(existing) && BuiltInChecksum.of(existing) != BuiltInChecksum.of(shipped) -> {
                    agents.save(existing.copy(name = shipped.name, description = shipped.description, harness = shipped.harness, modelProfileId = shipped.modelProfileId, tags = shipped.tags, instructions = shipped.instructions, rules = shipped.rules, skills = shipped.skills, connectors = shipped.connectors, access = shipped.access, session = shipped.session, updatedAt = now, updatedBy = Actor.SYSTEM))
                    agentsUpgraded++
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
                    // the whole shipped content (instructions, parameters, config, schema, produces, parent...) replaces the old one
                    templates.save(shipped.copy(id = existing.id, version = existing.version, createdAt = existing.createdAt, updatedAt = now, updatedBy = Actor.SYSTEM))
                    templatesUpgraded++
                }
            }
        }
        val templatesRemoved = BuiltInLibrary.formerActionTemplateKeys.count { key ->
            val former = templates.findByKeyAndScope(key, Scope.Global)
            val removable = former != null && former.builtIn && !templates.existsExtending(key)
            if (removable) templates.delete(former!!.id)
            removable
        }
        return SeedResult(
            templatesCreated, skillsCreated, rulesCreated, templatesUpgraded, skillsUpgraded, rulesUpgraded,
            agentsCreated, agentsUpgraded, templatesRemoved,
        )
    }

    private fun untouched(t: TaskTemplate) = untouched(t.builtIn, t.updatedBy) && t.version == 1

    private fun untouched(s: Skill) = untouched(s.builtIn, s.updatedBy)

    private fun untouched(r: Rule) = untouched(r.builtIn, r.updatedBy)

    private fun untouched(a: Agent) = untouched(a.builtIn, a.updatedBy)

    private fun untouched(builtIn: Boolean, updatedBy: Actor?) = builtIn && (updatedBy == null || updatedBy.kind == PrincipalKind.SYSTEM)
}
