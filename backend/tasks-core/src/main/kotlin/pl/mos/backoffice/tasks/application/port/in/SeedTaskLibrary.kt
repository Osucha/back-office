package pl.mos.backoffice.tasks.application.port.`in`

/**
 * Use case: create the built-in interfaces, templates and skills of docs/architecture/07-task-library.md.
 * Idempotent by key: entries the owner edited are left untouched; a never-edited built-in whose content differs
 * from the shipped definition (content checksum over every field) is upgraded to it (counted in `*Upgraded`).
 */
interface SeedTaskLibrary {
    fun seed(): SeedResult

    data class SeedResult(
        val interfacesCreated: Int,
        val templatesCreated: Int,
        val skillsCreated: Int,
        val templatesUpgraded: Int = 0,
        val skillsUpgraded: Int = 0,
        val interfacesUpgraded: Int = 0,
    ) {
        val total: Int get() = interfacesCreated + templatesCreated + skillsCreated + templatesUpgraded + skillsUpgraded + interfacesUpgraded
    }
}
