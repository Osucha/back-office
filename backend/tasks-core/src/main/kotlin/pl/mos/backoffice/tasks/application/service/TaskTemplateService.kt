package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskTemplates
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import pl.mos.backoffice.tasks.domain.service.TaskResolution
import pl.mos.backoffice.shared.tasks.TaskOverrides
import java.util.UUID

/**
 * Template CRUD and resolution. Saving validates the template against the library (parents, interfaces,
 * skills, schema) and, for concrete templates, dry-runs the resolver so cycles and over-deep chains are
 * rejected at write time instead of at run time.
 *
 * Access (ADR 0017): global templates need `library.view` / `library.manage`; project templates are read by
 * members of the project and changed with `library.project.manage` on it. Every change is audited
 * (`TEMPLATE_CREATE`, `TEMPLATE_UPDATE` with a field diff, `TEMPLATE_DELETE`).
 */
class TaskTemplateService(
    private val templates: TaskTemplateRepository,
    private val lookup: RepositoryLookup,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageTaskTemplates {

    override fun create(command: TaskTemplate.Content): TaskTemplate {
        val user = gate.requireWrite(command.scope)
        if (templates.findByKeyAndScope(command.key, command.scope) != null) {
            throw InvalidStateException("task template ${command.key} already exists in scope ${command.scope.describe()}")
        }
        val template = TaskTemplate.create(command, clock.now(), actor = user.actor)
        validate(template)
        val saved = templates.save(template)
        audit.record(entry("TEMPLATE_CREATE", saved, snapshot(saved)))
        return saved
    }

    override fun update(id: TaskTemplateId, command: TaskTemplate.Content): TaskTemplate {
        val user = gate.requireWrite(command.scope)
        val existing = load(id)
        if (existing.scope != command.scope) gate.requireWrite(existing.scope)
        templates.findByKeyAndScope(command.key, command.scope)?.let { clash ->
            if (clash.id != id) throw InvalidStateException("task template ${command.key} already exists in scope ${command.scope.describe()}")
        }
        if ((command.key != existing.key || command.scope != existing.scope) && templates.existsExtending(existing.key)) {
            throw InvalidStateException("task template ${existing.key} is extended by other templates; its key and scope cannot change")
        }
        val updated = existing.update(command, clock.now(), user.actor)
        validate(updated)
        val saved = templates.save(updated)
        val changes = diff(existing, saved)
        if (changes.isNotEmpty()) audit.record(entry("TEMPLATE_UPDATE", saved, changes + ("version" to saved.version)))
        return saved
    }

    override fun get(id: TaskTemplateId): TaskTemplate {
        val template = load(id)
        gate.requireRead(template.scope)
        return template
    }

    override fun list(projectId: UUID?, includeAbstract: Boolean): List<TaskTemplate> {
        val access = gate.requireListing(projectId)
        return templates.findVisible(access.projectId)
            .filter { access.allows(it.scope) }
            .filter { includeAbstract || !it.abstract }
            .sortedWith(compareBy({ it.key }, { it.scope.projectId != null }))
    }

    override fun delete(id: TaskTemplateId) {
        val existing = load(id)
        gate.requireWrite(existing.scope)
        if (existing.builtIn) throw InvalidStateException("task template ${existing.key} is built in; shadow it per project instead of deleting")
        if (templates.existsExtending(existing.key)) throw InvalidStateException("task template ${existing.key} is extended by other templates and cannot be deleted")
        templates.delete(id)
        audit.record(entry("TEMPLATE_DELETE", existing, mapOf("key" to existing.key, "scope" to existing.scope.describe())))
    }

    override fun resolve(key: String, projectId: UUID?): TaskResolution {
        val access = gate.requireListing(projectId)
        return lookup.resolver.preview(key, access.projectId) ?: throw NotFoundException("TaskTemplate", key)
    }

    override fun resolveStep(key: String, projectId: UUID?, overrides: TaskOverrides): TaskResolution {
        val access = gate.requireListing(projectId)
        return lookup.resolver.preview(key, access.projectId, overrides) ?: throw NotFoundException("TaskTemplate", key)
    }

    private fun load(id: TaskTemplateId): TaskTemplate = templates.findById(id) ?: throw NotFoundException("TaskTemplate", id)

    /** Static checks, then the chain walk (cycles, depth, unknown parents) against the not-yet-saved template. */
    private fun validate(template: TaskTemplate) {
        lookup.validator.validate(template)
        lookup.resolver.chainOf(template, template.scope.projectId)
    }

    private fun entry(operation: String, t: TaskTemplate, details: Map<String, Any?>) = AuditEntry(
        operation = operation,
        entityType = ENTITY_TYPE,
        entityId = t.id.toString(),
        projectId = t.scope.projectId,
        details = details,
    )

    companion object {
        const val ENTITY_TYPE = "TaskTemplate"

        /** What a `TEMPLATE_CREATE` entry carries: identity and structure, prompt sizes instead of prompt text. */
        fun snapshot(t: TaskTemplate): Map<String, Any?> = mapOf(
            "key" to t.key,
            "scope" to t.scope.describe(),
            "abstract" to t.abstract,
            "extendsKey" to t.extendsKey,
            "implements" to t.implements,
            "executor" to t.executor?.name,
            "skills" to t.skills,
            "systemPromptLength" to (t.prompt.system?.length ?: 0),
            "userPromptLength" to (t.prompt.user?.length ?: 0),
            "runInputs" to runInputSummary(t),
        )

        /** Run inputs for the audit log: `name:TYPE:required|optional:label:help=<length>` per field, help by length like prompts. */
        private fun runInputSummary(t: TaskTemplate): List<String> = t.runInputs.map {
            "${it.name}:${it.type.name}:${if (it.required) "required" else "optional"}:${it.label}:help=${it.help?.length ?: 0}"
        }

        /** `{field: {old, new}}` for everything an owner can edit; prompts and schemas by length, not content. */
        fun diff(old: TaskTemplate, new: TaskTemplate): Map<String, Any?> = AuditableChanges.changed(
            "key" to (old.key to new.key),
            "name" to (old.name to new.name),
            "description" to (old.description to new.description),
            "scope" to (old.scope.describe() to new.scope.describe()),
            "abstract" to (old.abstract to new.abstract),
            "extendsKey" to (old.extendsKey to new.extendsKey),
            "implements" to (old.implements to new.implements),
            "executor" to (old.executor?.name to new.executor?.name),
            "modelProfileId" to (old.modelProfileId?.toString() to new.modelProfileId?.toString()),
            "promptMode" to (old.prompt.mode.name to new.prompt.mode.name),
            "systemPromptLength" to ((old.prompt.system?.length ?: 0) to (new.prompt.system?.length ?: 0)),
            "userPromptLength" to ((old.prompt.user?.length ?: 0) to (new.prompt.user?.length ?: 0)),
            "systemPromptChanged" to (false to (old.prompt.system != new.prompt.system)),
            "userPromptChanged" to (false to (old.prompt.user != new.prompt.user)),
            "skills" to (old.skills to new.skills),
            "removeSkills" to (old.removeSkills to new.removeSkills),
            "configKeys" to (old.config.keys.sorted() to new.config.keys.sorted()),
            "configChanged" to (false to (old.config != new.config)),
            "outputSchemaLength" to ((old.outputSchema?.length ?: 0) to (new.outputSchema?.length ?: 0)),
            "confidenceField" to (old.confidenceField to new.confidenceField),
            "produces" to (old.produces to new.produces),
            "consumes" to (old.consumes to new.consumes),
            "tags" to (old.tags to new.tags),
            "runInputs" to (runInputSummary(old) to runInputSummary(new)),
        )
    }
}
