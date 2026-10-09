package pl.mos.backoffice.tasks.application.port.`in`

import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.TaskInterface

/**
 * Use case: maintain the contracts templates can implement. Interfaces are global and addressed by key.
 * Deleting one that any template implements is refused (`InvalidStateException`).
 */
interface ManageTaskInterfaces {
    fun create(command: InterfaceCommand): TaskInterfaceDetails
    /** Renaming the key is allowed only while no template implements the old key. */
    fun update(key: String, command: InterfaceCommand): TaskInterfaceDetails
    fun get(key: String): TaskInterfaceDetails
    fun list(): List<TaskInterfaceDetails>
    fun delete(key: String)

    data class InterfaceCommand(
        val key: String,
        val name: String,
        val description: String = "",
        val inputs: List<Slot> = emptyList(),
        val outputs: List<Slot> = emptyList(),
        val ports: List<String> = TaskInterface.DEFAULT_PORTS,
    )

    /** An interface plus the keys of the templates that declare it in `implements` (any scope). */
    data class TaskInterfaceDetails(val definition: TaskInterface, val implementedBy: List<String>)
}
