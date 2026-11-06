package pl.mos.backoffice.tasks.application.port.`in`

/**
 * Use case: create the built-in templates, skills, rules and agents (ADR 0056) of docs/architecture/07-task-library.md.
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
        val agentsCreated: Int = 0,
        val agentsUpgraded: Int = 0,
        /** ADR 0056: former built-in action templates removed (their steps became action nodes). */
        val templatesRemoved: Int = 0,
    ) {
        val total: Int get() = templatesCreated + skillsCreated + rulesCreated + templatesUpgraded + skillsUpgraded + rulesUpgraded +
            agentsCreated + agentsUpgraded + templatesRemoved
    }
}
