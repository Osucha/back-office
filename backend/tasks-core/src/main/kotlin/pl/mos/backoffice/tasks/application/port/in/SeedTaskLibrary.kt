package pl.mos.backoffice.tasks.application.port.`in`

/**
 * Use case: create the built-in templates, skills and rules of docs/architecture/07-task-library.md.
 * Idempotent by key: entries the owner edited are left untouched; a never-edited built-in whose content differs
 * from the shipped definition (content checksum over every field) is upgraded to it (counted in `*Upgraded`).
 */
interface SeedTaskLibrary {
    fun seed(): SeedResult

    data class SeedResult(
        val templatesCreated: Int,
        val skillsCreated: Int,
        val rulesCreated: Int = 0,
        val templatesUpgraded: Int = 0,
        val skillsUpgraded: Int = 0,
        val rulesUpgraded: Int = 0,
    ) {
        val total: Int get() = templatesCreated + skillsCreated + rulesCreated + templatesUpgraded + skillsUpgraded + rulesUpgraded
    }
}
