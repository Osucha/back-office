package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.testing.TestAgents

/** ADR 0060/0062: the directory and the session of a step come from its agent; prepare-only and questions from the step. */
class RunDirectoryTest {

    private val coder = TestAgents.OPENCODE
    private fun agent(session: AgentSession = AgentSession.PER_STEP, directory: AgentDirectory = AgentDirectory.RUN) =
        coder.copy(session = session, access = AgentAccess(directory = directory))

    @Test
    fun `defaults - the run directory and the step's own session`() {
        val setting = RunDirectory.of(emptyMap(), coder, runnerStep = true)
        assertThat(setting.directory).isEqualTo(AgentDirectory.RUN)
        assertThat(setting.session).isEqualTo(AgentSession.PER_STEP)
        assertThat(setting.valid).isTrue()
        assertThat(setting.warnings).isEmpty()
        assertThat(RunDirectory.sessionRole("implement", setting, coder.key)).isEqualTo("s-implement")
    }

    @Test
    fun `own roles are valid session roles, deterministic and distinct for long node ids`() {
        val long = "review-the-implementation-against-the-spec"
        val longer = "review-the-implementation-against-the-plan"
        val role = RunDirectory.ownRole(long)
        assertThat(role).matches(RunnerWorkspace.KEY_PATTERN.pattern).hasSizeLessThanOrEqualTo(20).startsWith("s-review")
        assertThat(RunDirectory.ownRole(long)).isEqualTo(role)
        assertThat(RunDirectory.ownRole(longer)).isNotEqualTo(role)
        assertThat(RunDirectory.ownRole("Fix_It")).isEqualTo("s-fix-it")
        assertThat(RunDirectory.ownRole("---")).matches(RunnerWorkspace.KEY_PATTERN.pattern)
        assertThat(RunDirectory.ownRole("abcdefghijklmnopqr")).isEqualTo("s-abcdefghijklmnopqr")
        assertThat(RunDirectory.ownRole("abcdefghijklmnopqrs")).hasSize(20).isNotEqualTo("s-abcdefghijklmnopqr")
    }

    @Test
    fun `PER_RUN shares the agent's role across steps, FRESH and SEPARATE have none`() {
        val perRun = RunDirectory.of(emptyMap(), agent(AgentSession.PER_RUN), runnerStep = true)
        assertThat(RunDirectory.sessionRole("implement", perRun, "claude-code")).isEqualTo("a-claude-code")
        assertThat(RunDirectory.sessionRole("fix", perRun, "claude-code")).describedAs("another step, the same conversation").isEqualTo("a-claude-code")
        assertThat(RunDirectory.agentRole("a-very-long-agent-key-of-the-project"))
            .matches(RunnerWorkspace.KEY_PATTERN.pattern).hasSizeLessThanOrEqualTo(20).startsWith("a-")
            .isEqualTo(RunDirectory.agentRole("a-very-long-agent-key-of-the-project"))

        assertThat(RunDirectory.sessionRole("x", RunDirectory.of(emptyMap(), agent(AgentSession.FRESH), runnerStep = true), coder.key)).isNull()
        val separate = RunDirectory.of(emptyMap(), agent(AgentSession.PER_RUN, AgentDirectory.SEPARATE), runnerStep = true)
        assertThat(separate.runDirectory).isFalse()
        assertThat(RunDirectory.sessionRole("x", separate, coder.key)).isNull()
        val prepare = RunDirectory.of(mapOf(RunDirectory.PREPARE_ONLY to true), coder, runnerStep = true)
        assertThat(prepare.prepareOnly).isTrue()
        assertThat(RunDirectory.sessionRole("x", prepare, coder.key)).isNull()
    }

    @Test
    fun `a legacy sessionRole is the step's role while the agent keeps PER_STEP`() {
        val legacy = mapOf(RunDirectory.LEGACY_SESSION_ROLE to "advisor")
        assertThat(RunDirectory.sessionRole("review", RunDirectory.of(legacy, coder, runnerStep = true), coder.key)).isEqualTo("advisor")
        assertThat(RunDirectory.sessionRole("review", RunDirectory.of(legacy, agent(AgentSession.PER_RUN), runnerStep = true), coder.key)).isEqualTo("a-opencode")
        assertThat(RunDirectory.sessionRole("review", RunDirectory.of(legacy, agent(AgentSession.FRESH), runnerStep = true), coder.key)).isNull()
        val invalid = RunDirectory.of(mapOf(RunDirectory.LEGACY_SESSION_ROLE to "Not A Role"), coder, runnerStep = true)
        assertThat(invalid.legacyRole).isNull()
        assertThat(invalid.warnings.single()).contains("ignored")
    }

    @Test
    fun `a prepare step needs an agent with the run directory, model answers have no directory`() {
        val separate = agent(directory = AgentDirectory.SEPARATE).copy(access = AgentAccess(AgentAccessMode.READ_ONLY, directory = AgentDirectory.SEPARATE))
        assertThat(RunDirectory.of(mapOf(RunDirectory.PREPARE_ONLY to true), separate, runnerStep = true).errors.single())
            .contains("prepare step").contains("SEPARATE")
        assertThat(RunDirectory.of(emptyMap(), separate, runnerStep = true).valid).isTrue()
        assertThat(RunDirectory.of(mapOf(RunDirectory.PREPARE_ONLY to "true"), TestAgents.MODEL_ANSWER, runnerStep = false).warnings.single())
            .contains("only coding agent steps")
        assertThat(RunDirectory.of(mapOf("session" to "NEW", "directory" to "SEPARATE"), TestAgents.MODEL_ANSWER, runnerStep = false).warnings)
            .describedAs("the former step keys are no settings any more").isEmpty()
    }

    @Test
    fun `questions are allowed with two rounds by default`() {
        assertThat(RunDirectory.questions(emptyMap())).isEqualTo(RunDirectory.Questions(true, 2))
        assertThat(RunDirectory.questions(mapOf(RunDirectory.ALLOW_QUESTIONS to false, RunDirectory.MAX_QUESTION_ROUNDS to "3"))).isEqualTo(RunDirectory.Questions(false, 3))
        assertThat(RunDirectory.questions(mapOf(RunDirectory.MAX_QUESTION_ROUNDS to 0)).maxRounds).isZero()
        assertThat(RunDirectory.questions(mapOf(RunDirectory.MAX_QUESTION_ROUNDS to "many")).errors.single()).contains("0 to 10")
        assertThat(RunDirectory.questions(mapOf(RunDirectory.MAX_QUESTION_ROUNDS to 11)).errors).isNotEmpty()
    }
}
