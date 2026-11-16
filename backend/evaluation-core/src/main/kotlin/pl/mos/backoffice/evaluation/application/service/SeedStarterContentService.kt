package pl.mos.backoffice.evaluation.application.service

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.SystemOperation
import pl.mos.backoffice.evaluation.application.port.`in`.system.SeedStarterContent
import pl.mos.backoffice.evaluation.application.port.`in`.system.SeedStarterContent.SeedResult
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.shared.Clock

/**
 * Creates the starter suite (docs/architecture/04-model-evaluation.md) when there is none. Idempotent: the owner's edits
 * and deletions survive restarts. The one exception (ADR 0046): coding tasks that still point at the placeholder
 * repository of the first seed are replaced by the Spring PetClinic tasks, and (ADR 0047) starter tasks still in an
 * earlier version get the current content.
 */
class SeedStarterContentService(
    private val profiles: ModelProfileRepository,
    private val suites: TaskSuiteRepository,
    private val clock: Clock,
    private val guard: AccessGuard,
) : SeedStarterContent {

    @SystemOperation("startup seeding of the starter suite; never exposed through a controller")
    override fun seedIfEmpty(): SeedResult {
        guard.requireSystem()
        val now = clock.now()
        var profilesCreated = 0
        var suitesCreated = 0
        // ADR 0045: no seeded model profiles; they are created from the models the connected providers offer
        if (suites.count() == 0L) {
            suites.save(StarterContent.starterSuite(now))
            suitesCreated++
        }
        var suitesUpgraded = 0
        suites.findAll().forEach { suite ->
            val placeholderFixed = StarterContent.upgradeCodingTasks(suite, now)
            val upgraded = StarterContent.upgradeUntouchedTasks(placeholderFixed ?: suite, now) ?: placeholderFixed
            upgraded?.let { suites.save(it); suitesUpgraded++ }
        }
        return SeedResult(profilesCreated, suitesCreated, suitesUpgraded)
    }
}
