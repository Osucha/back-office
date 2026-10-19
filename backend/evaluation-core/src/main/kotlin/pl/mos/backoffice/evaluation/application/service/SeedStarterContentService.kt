package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.port.`in`.SeedStarterContent
import pl.mos.backoffice.evaluation.application.port.`in`.SeedStarterContent.SeedResult
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * Creates the dated model seed and the starter suite (docs/architecture/04-model-evaluation.md) when
 * the respective catalogue is empty. Idempotent: a non-empty catalogue is never touched, so the owner's
 * edits and deletions survive restarts.
 */
class SeedStarterContentService(
    private val profiles: ModelProfileRepository,
    private val suites: TaskSuiteRepository,
    private val clock: Clock,
) : SeedStarterContent {

    @SystemOperation("startup seeding of the starter suite; never exposed through a controller")
    override fun seedIfEmpty(): SeedResult {
        val now = clock.now()
        var profilesCreated = 0
        var suitesCreated = 0
        // ADR 0045: no seeded model profiles; they are created from the models the connected providers offer
        if (suites.count() == 0L) {
            suites.save(StarterContent.starterSuite(now))
            suitesCreated++
        }
        return SeedResult(profilesCreated, suitesCreated)
    }
}
