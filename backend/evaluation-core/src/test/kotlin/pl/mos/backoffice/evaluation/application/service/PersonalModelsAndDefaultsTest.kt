package pl.mos.backoffice.evaluation.application.service

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles.ProfileCommand
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryBenchmarkRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelDefaultRepository
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.DefaultModel
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.Pricing
import java.util.UUID

/** ADR 0050 step 2: personal model profiles and default models per kind of step. */
class PersonalModelsAndDefaultsTest {

    private val profiles = InMemoryModelProfileRepository()
    private val defaults = InMemoryModelDefaultRepository()
    private val audit = RecordingAuditPort()
    private val clock = Clock.fixed(Fixtures.now)
    private val viewer = FakeAuthorizationGate.permitting(EvaluationPermissions.MODELS_VIEW)
    private val admin = FakeAuthorizationGate.permitting(EvaluationPermissions.MODELS_VIEW, EvaluationPermissions.MODELS_MANAGE)
    private val me = FakeAuthorizationGate.TEST_USER_ID.value
    private val other = UUID.randomUUID()

    private val gateway = mockk<LlmGateway>().also { g ->
        io.mockk.every { g.connectionOwner(any()) } answers { if (firstArg<ModelRef>().connection == "their-key") other else null }
    }
    private fun models(gate: FakeAuthorizationGate) = ModelProfileService(profiles, InMemoryBenchmarkRepository(), gateway, clock, gate, audit)
    private fun defaultsOf(gate: FakeAuthorizationGate) = ModelDefaultsService(defaults, profiles, gate, audit, clock)

    private val platform = Fixtures.profile("Sonnet API").also { profiles.save(it) }
    private val theirs = Fixtures.profile("Their key").copy(ownerUserId = other).also { profiles.save(it) }

    @Test
    fun `anyone who reads models creates personal profiles, only their owner sees and changes them`() {
        assertThatThrownBy { models(viewer).create(ProfileCommand("Platform", ModelRef(Provider.OPENAI, "gpt-5"), Pricing.FREE)) }
            .isInstanceOf(InsufficientPermissionException::class.java)

        val mine = models(viewer).create(ProfileCommand("My key", ModelRef(Provider.OPENAI, "gpt-5"), Pricing.FREE, personal = true))
        val login = models(viewer).create(ProfileCommand("Opus (my login)", ModelRef(Provider.ANTHROPIC, "opus"), Pricing.FREE, runtime = ModelRuntime.CLAUDE_CODE_LOGIN))
        assertThat(listOf(mine.ownerUserId, login.ownerUserId)).containsOnly(me)

        assertThat(models(viewer).list().map { it.displayName }).containsExactlyInAnyOrder("Sonnet API", "My key", "Opus (my login)")
        assertThatThrownBy { models(viewer).get(theirs.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { models(admin).delete(theirs.id) }.isInstanceOf(NotFoundException::class.java)

        val renamed = models(viewer).update(mine.id, ProfileCommand("My OpenAI key", mine.ref, Pricing.FREE))
        assertThat(renamed.let { it.displayName to it.ownerUserId }).isEqualTo("My OpenAI key" to me)
        assertThatThrownBy { models(admin).update(platform.id, ProfileCommand("x", platform.ref, Pricing.FREE, runtime = ModelRuntime.CLAUDE_CODE_LOGIN)) }
            .isInstanceOf(ValidationException::class.java)
        models(viewer).delete(login.id)
        assertThat(profiles.findById(login.id)).isNull()

        // ADR 0050 step 2b: someone else's personal connection cannot back a profile, not even a platform one
        val theirKey = ModelRef(Provider.OPENAI, "gpt-5", connection = "their-key")
        assertThatThrownBy { models(viewer).create(ProfileCommand("Borrowed", theirKey, Pricing.FREE, personal = true)) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("personal connection")
        assertThatThrownBy { models(admin).create(ProfileCommand("Borrowed", theirKey, Pricing.FREE)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `defaults, the platform's with models manage, the user's own may be personal, also a login model for LLM steps`() {
        val login = profiles.save(Fixtures.profile("Opus (my login)", modelId = "opus").copy(runtime = ModelRuntime.CLAUDE_CODE_LOGIN, ownerUserId = me))
        val myKey = profiles.save(Fixtures.profile("My key").copy(ownerUserId = me))

        assertThatThrownBy { defaultsOf(viewer).setPlatform(StepKind.LLM_CALL, platform.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { defaultsOf(admin).setPlatform(StepKind.LLM_CALL, myKey.id) }.isInstanceOf(ValidationException::class.java)
        defaultsOf(admin).setPlatform(StepKind.LLM_CALL, platform.id)
        defaultsOf(admin).setPlatform(StepKind.CODING_AGENT, platform.id)

        // ADR 0050 step 4: a login model answers LLM steps too (Claude Code prompt mode)
        assertThat(defaultsOf(viewer).setMine(StepKind.LLM_CALL, login.id).mine[StepKind.LLM_CALL]!!.id).isEqualTo(login.id)
        defaultsOf(viewer).setMine(StepKind.LLM_CALL, null)
        assertThatThrownBy { defaultsOf(viewer).setMine(StepKind.CODING_AGENT, theirs.id) }.isInstanceOf(NotFoundException::class.java)
        val set = defaultsOf(viewer).setMine(StepKind.CODING_AGENT, login.id)
        assertThat(set.mine[StepKind.CODING_AGENT]!!.id).isEqualTo(login.id)
        assertThat(set.platform.keys).containsExactlyInAnyOrder(StepKind.LLM_CALL, StepKind.CODING_AGENT)

        val resolver = defaultsOf(viewer)
        assertThat(resolver.resolve(me, StepKind.CODING_AGENT)).isEqualTo(DefaultModel(login.id, personal = true))
        assertThat(resolver.resolve(me, StepKind.LLM_CALL)).isEqualTo(DefaultModel(platform.id, personal = false))
        assertThat(resolver.resolve(other, StepKind.CODING_AGENT)).isEqualTo(DefaultModel(platform.id, personal = false))

        // a disabled default is ignored, and cleared defaults fall back to the platform's
        profiles.save(login.copy(enabled = false))
        assertThat(resolver.resolve(me, StepKind.CODING_AGENT)!!.personal).isFalse()
        defaultsOf(admin).setPlatform(StepKind.CODING_AGENT, null)
        assertThat(resolver.resolve(other, StepKind.CODING_AGENT)).isNull()
        assertThat(audit.entries.filter { it.operation == "MODEL_DEFAULT_SET" }).hasSize(6)
    }
}
