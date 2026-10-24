package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

/**
 * Contract a template can implement: what it must consume, what it must produce and which output ports
 * its workflow node exposes. Interfaces are global; uniqueness is by `key`.
 */
data class TaskInterface(
    val id: TaskInterfaceId,
    val key: String,
    val name: String,
    val description: String,
    val inputs: List<Slot>,
    val outputs: List<Slot>,
    val ports: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (ADR 0017); null for rows written before actor stamps existed. */
    val updatedBy: Actor? = null,
) {
    init {
        Keys.require(key)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (ports.isEmpty()) throw ValidationException("at least one port is required", "ports")
        ports.forEach { if (!PORT_PATTERN.matches(it)) throw ValidationException("port $it must match ${PORT_PATTERN.pattern}", "ports") }
        if (ports.distinct().size != ports.size) throw ValidationException("port names must be unique", "ports")
        if (inputs.map { it.name to it.kind }.distinct().size != inputs.size) throw ValidationException("input (name, kind) pairs must be unique", "inputs")
        if (outputs.map { it.name to it.kind }.distinct().size != outputs.size) throw ValidationException("output (name, kind) pairs must be unique", "outputs")
        outputs.firstOrNull { it.kind == SlotKind.INPUT }?.let {
            throw ValidationException("output ${it.name}: outputs are STATE or ARTIFACT, not INPUT", "outputs")
        }
    }

    /** Inputs that must be listed in a template's `consumes` (rule 5 of the resolution rules). */
    val requiredConsumes: List<String>
        get() = inputs.filter { it.required && it.kind != SlotKind.INPUT }.map { it.name }

    /** `INPUT` slots that must be declared in a template's `runInputs` (rule 5, ADR 0052). */
    val requiredRunInputs: List<String>
        get() = inputs.filter { it.required && it.kind == SlotKind.INPUT }.map { it.name }

    fun update(
        name: String, description: String, inputs: List<Slot>, outputs: List<Slot>, ports: List<String>, now: Instant, actor: Actor? = null,
    ): TaskInterface = copy(name = name, description = description, inputs = inputs, outputs = outputs, ports = ports, updatedAt = now, updatedBy = actor)

    fun rename(key: String, now: Instant, actor: Actor? = null): TaskInterface = copy(key = key, updatedAt = now, updatedBy = actor)

    companion object {
        val DEFAULT_PORTS: List<String> = listOf("success", "failure")
        val PORT_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{0,39}$")

        fun create(
            key: String,
            name: String,
            description: String = "",
            inputs: List<Slot> = emptyList(),
            outputs: List<Slot> = emptyList(),
            ports: List<String> = DEFAULT_PORTS,
            builtIn: Boolean = false,
            now: Instant,
            actor: Actor? = null,
        ): TaskInterface = TaskInterface(
            id = TaskInterfaceId.new(),
            key = key,
            name = name,
            description = description,
            inputs = inputs,
            outputs = outputs,
            ports = ports.ifEmpty { DEFAULT_PORTS },
            builtIn = builtIn,
            createdAt = now,
            updatedAt = now,
            updatedBy = actor,
        )
    }
}
