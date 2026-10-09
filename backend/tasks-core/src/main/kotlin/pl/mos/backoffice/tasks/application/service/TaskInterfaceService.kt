package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.tasks.application.TasksPermissions
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskInterfaces
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskInterfaces.InterfaceCommand
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskInterfaces.TaskInterfaceDetails
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.TaskInterface

/**
 * Interface CRUD; `implementedBy` is computed from the templates on every read. Interfaces are global:
 * reading needs `library.view`, changing needs `library.manage`; changes are audited as
 * `INTERFACE_CREATE`, `INTERFACE_UPDATE`, `INTERFACE_DELETE`.
 */
class TaskInterfaceService(
    private val interfaces: TaskInterfaceRepository,
    private val templates: TaskTemplateRepository,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageTaskInterfaces {

    override fun create(command: InterfaceCommand): TaskInterfaceDetails {
        val user = gate.require(TasksPermissions.LIBRARY_MANAGE)
        if (interfaces.findByKey(command.key) != null) throw InvalidStateException("task interface ${command.key} already exists")
        val created = TaskInterface.create(
            key = command.key, name = command.name, description = command.description,
            inputs = command.inputs, outputs = command.outputs, ports = command.ports, now = clock.now(), actor = user.actor,
        )
        val saved = interfaces.save(created)
        audit.record(entry("INTERFACE_CREATE", saved, mapOf("key" to saved.key, "inputs" to slots(saved.inputs), "outputs" to slots(saved.outputs), "ports" to saved.ports)))
        return details(saved)
    }

    override fun update(key: String, command: InterfaceCommand): TaskInterfaceDetails {
        val user = gate.require(TasksPermissions.LIBRARY_MANAGE)
        val existing = find(key)
        val now = clock.now()
        var updated = existing.update(command.name, command.description, command.inputs, command.outputs, command.ports, now, user.actor)
        if (command.key != key) {
            if (templates.existsImplementing(key)) throw InvalidStateException("task interface $key is implemented by templates and cannot be renamed")
            if (interfaces.findByKey(command.key) != null) throw InvalidStateException("task interface ${command.key} already exists")
            updated = updated.rename(command.key, now, user.actor)
        }
        val saved = interfaces.save(updated)
        val changes = AuditableChanges.changed(
            "key" to (existing.key to saved.key),
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "inputs" to (slots(existing.inputs) to slots(saved.inputs)),
            "outputs" to (slots(existing.outputs) to slots(saved.outputs)),
            "ports" to (existing.ports to saved.ports),
        )
        if (changes.isNotEmpty()) audit.record(entry("INTERFACE_UPDATE", saved, changes))
        return details(saved)
    }

    override fun get(key: String): TaskInterfaceDetails {
        gate.require(TasksPermissions.LIBRARY_VIEW)
        return details(find(key))
    }

    override fun list(): List<TaskInterfaceDetails> {
        gate.require(TasksPermissions.LIBRARY_VIEW)
        return interfaces.findAll().sortedBy { it.key }.map(::details)
    }

    override fun delete(key: String) {
        gate.require(TasksPermissions.LIBRARY_MANAGE)
        val existing = find(key)
        val implementers = templates.findKeysImplementing(key)
        if (implementers.isNotEmpty()) {
            throw InvalidStateException("task interface $key is implemented by ${implementers.joinToString()} and cannot be deleted")
        }
        interfaces.delete(existing.id)
        audit.record(entry("INTERFACE_DELETE", existing, mapOf("key" to existing.key)))
    }

    private fun find(key: String) = interfaces.findByKey(key) ?: throw NotFoundException("TaskInterface", key)

    private fun details(definition: TaskInterface) = TaskInterfaceDetails(definition, templates.findKeysImplementing(definition.key))

    private fun slots(slots: List<Slot>): List<String> = slots.map { "${it.kind}:${it.name}" + if (it.required) "" else "?" }

    private fun entry(operation: String, i: TaskInterface, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "TaskInterface", entityId = i.id.toString(), details = mapOf("key" to i.key) + details)
}
