package pl.mos.backoffice.evaluation.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelRoute
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget
import java.util.UUID

/** ADR 0050: which models a use may take, by which route, and why not. */
class ModelAvailabilityServiceTest {

    private val profiles = InMemoryModelProfileRepository()
    private val gateway = mockk<LlmGateway>()
    private val runner = mockk<AgentRunner>()
    private val service = ModelAvailabilityService(profiles, gateway, runner)

    private val connected = Fixtures.profile("Sonnet API")
    private val unconnected = Fixtures.profile("GPT", provider = Provider.OPENAI, modelId = "gpt-5")
    private val login = Fixtures.profile("Opus (my login)", modelId = "opus").copy(runtime = ModelRuntime.CLAUDE_CODE_LOGIN)
    private val off = Fixtures.profile("Off").copy(enabled = false)
    private val user = UUID.randomUUID()

    private fun personal(pools: Int = 1, online: Int = 1, localLogin: Boolean = true) {
        every { runner.executionTargets(user) } returns listOf(ExecutionTarget(RunnerTarget.SHARED, 1, 1), ExecutionTarget(RunnerTarget.PERSONAL, pools, online, localLogin))
    }

    init {
        for (p in listOf<pl.mos.backoffice.evaluation.domain.model.ModelProfile>(connected, unconnected, login, off)) profiles.save(p)
        every { gateway.isConfigured(any<pl.mos.backoffice.shared.llm.ModelRef>()) } answers { firstArg<pl.mos.backoffice.shared.llm.ModelRef>().provider == Provider.ANTHROPIC }
        personal()
    }

    private val coding = ModelUse(StepKind.CODING_AGENT, AgentHarness.CLAUDE_CODE, privateProject = true, userId = user)

    @Test
    fun `API models need their provider connection, disabled profiles are not offered, usable ones come first`() {
        val options = service.options(ModelUse(StepKind.LLM_CALL, userId = user))

        assertThat(options.map { it.displayName }).containsExactly("Sonnet API", "GPT", "Opus (my login)")
        assertThat(options.first().let { Triple(it.usable, it.route, it.personal) }).isEqualTo(Triple(true, ModelRoute.GATEWAY, false))
        assertThat(options[1].problem).contains("provider connection")
        assertThat(service.check(off.id, coding)!!.problem).isEqualTo("the model profile is disabled")
    }

    @Test
    fun `a login model needs a Claude Code coding step in a private project and a personal runner with the user's login`() {
        fun problem(use: ModelUse) = service.check(login.id, use)!!.problem

        assertThat(service.check(login.id, coding)!!.let { Triple(it.usable, it.route, it.personal) }).isEqualTo(Triple(true, ModelRoute.OWN_LOGIN, true))
        assertThat(problem(coding.copy(stepKind = StepKind.LLM_CALL))).contains("coding-agent steps only")
        assertThat(problem(coding.copy(privateProject = false))).contains("private projects")
        assertThat(problem(coding.copy(harness = AgentHarness.OPENCODE))).contains("Claude Code as the coding agent")

        personal(pools = 0, online = 0)
        assertThat(problem(coding)).contains("no personal runner")
        personal(localLogin = false)
        assertThat(problem(coding)).contains("does not use your own Claude login")
        personal(online = 0)
        assertThat(service.check(login.id, coding)!!.let { it.usable to it.note }).isEqualTo(true to "your personal runner is offline: its jobs wait until it connects")
        assertThat(ModelAvailabilityService(profiles, gateway).check(login.id, coding)!!.usable).isFalse()
    }
}
