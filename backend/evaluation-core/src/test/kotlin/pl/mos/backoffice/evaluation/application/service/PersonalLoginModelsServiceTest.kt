package pl.mos.backoffice.evaluation.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/** ADR 0050 step 3: Claude Code login profiles appear when a user's personal runner reports their login. */
class PersonalLoginModelsServiceTest {

    private val profiles = InMemoryModelProfileRepository()
    private val audit = RecordingAuditPort()
    private val service = PersonalLoginModelsService(profiles, Clock.fixed(Fixtures.now), audit)
    private val user = UUID.randomUUID()

    @Test
    fun `one personal login profile per alias, aliases the user already has are kept, nothing for others`() {
        profiles.save(Fixtures.profile("My Opus", modelId = "opus").copy(runtime = ModelRuntime.CLAUDE_CODE_LOGIN, ownerUserId = user))
        profiles.save(Fixtures.profile("Someone's Sonnet", modelId = "sonnet").copy(runtime = ModelRuntime.CLAUDE_CODE_LOGIN, ownerUserId = UUID.randomUUID()))

        assertThat(service.ensure(user)).isEqualTo(2)
        val mine = profiles.findAll().filter { it.ownerUserId == user }
        assertThat(mine.map { it.ref.modelId }).containsExactlyInAnyOrder("opus", "sonnet", "haiku")
        assertThat(mine.single { it.ref.modelId == "sonnet" }.let { Triple(it.displayName, it.runtime, it.pricing.inputPerMTok.signum()) })
            .isEqualTo(Triple("Claude Sonnet (my login)", ModelRuntime.CLAUDE_CODE_LOGIN, 0))
        assertThat(audit.entries.map { it.operation }).containsOnly("MODEL_PROFILE_CREATE").hasSize(2)

        assertThat(service.ensure(user)).describedAs("idempotent").isZero()
    }
}
