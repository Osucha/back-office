package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.tasks.TaskOverrides

/** ADR 0060: the directory, the session and the questions settings of a step. */
class RunDirectoryTest {

    private fun step(id: String, config: Map<String, Any?>) = WorkflowNode.Task(id, id, task = TaskBinding("t", TaskOverrides(config = config)))

    @Test
    fun `defaults - the run directory and the step's own session`() {
        val setting = RunDirectory.of(emptyMap(), runnerStep = true)
        assertThat(setting.directory).isEqualTo(RunDirectory.Directory.RUN)
        assertThat(setting.session).isEqualTo(RunDirectory.Session.OWN)
        assertThat(setting.valid).isTrue()
        assertThat(setting.warnings).isEmpty()
        assertThat(RunDirectory.sessionRole("implement", emptyMap(), emptyList())).isEqualTo("s-implement")
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
    fun `CONTINUE takes the role of the step it continues, through a chain, NEW and SEPARATE have none`() {
        val nodes = listOf(
            step("implement", emptyMap()),
            step("fix", mapOf(RunDirectory.CONTINUE_SESSION to "implement")),
            step("polish", mapOf(RunDirectory.SESSION to "continue", RunDirectory.CONTINUE_SESSION to "fix")),
            step("fresh", mapOf(RunDirectory.SESSION to "NEW")),
            step("after-fresh", mapOf(RunDirectory.CONTINUE_SESSION to "fresh")),
        )
        assertThat(RunDirectory.sessionRole("polish", nodes[2].task.overrides.config, nodes)).isEqualTo("s-implement")
        assertThat(RunDirectory.sessionRole("fresh", nodes[3].task.overrides.config, nodes)).isNull()
        assertThat(RunDirectory.sessionRole("after-fresh", nodes[4].task.overrides.config, nodes)).isNull()
        assertThat(RunDirectory.sessionRole("x", mapOf(RunDirectory.DIRECTORY to "SEPARATE"), nodes)).isNull()
        assertThat(RunDirectory.sessionRole("x", mapOf(RunDirectory.PREPARE_ONLY to true), nodes)).isNull()
        assertThat(RunDirectory.sessionRole("x", mapOf(RunDirectory.CONTINUE_SESSION to "missing"), nodes)).isNull()
        assertThat(RunDirectory.sessionRole("x", mapOf(RunDirectory.CONTINUE_SESSION to "x"), nodes)).isNull()
    }

    @Test
    fun `a legacy sessionRole is the step's own role unless session is set`() {
        assertThat(RunDirectory.sessionRole("review", mapOf(RunDirectory.LEGACY_SESSION_ROLE to "advisor"), emptyList())).isEqualTo("advisor")
        assertThat(RunDirectory.sessionRole("review", mapOf(RunDirectory.LEGACY_SESSION_ROLE to "advisor", RunDirectory.SESSION to "NEW"), emptyList())).isNull()
        val invalid = RunDirectory.of(mapOf(RunDirectory.LEGACY_SESSION_ROLE to "Not A Role"), runnerStep = true)
        assertThat(invalid.legacyRole).isNull()
        assertThat(invalid.warnings.single()).contains("ignored")
    }

    @Test
    fun `SEPARATE forces a new session with a warning and refuses a prepare step`() {
        val separate = RunDirectory.of(mapOf(RunDirectory.DIRECTORY to "separate", RunDirectory.SESSION to "OWN"), runnerStep = true)
        assertThat(separate.directory).isEqualTo(RunDirectory.Directory.SEPARATE)
        assertThat(separate.session).isEqualTo(RunDirectory.Session.NEW)
        assertThat(separate.warnings.single()).contains("always starts a new session")
        assertThat(RunDirectory.of(mapOf(RunDirectory.DIRECTORY to "SEPARATE"), runnerStep = true).warnings).isEmpty()
        assertThat(RunDirectory.of(mapOf(RunDirectory.DIRECTORY to "SEPARATE", RunDirectory.PREPARE_ONLY to true), runnerStep = true).errors.single())
            .contains("prepare step")
    }

    @Test
    fun `invalid values and contradicting keys`() {
        assertThat(RunDirectory.of(mapOf(RunDirectory.DIRECTORY to "HOME"), runnerStep = true).errors.single()).contains("RUN or SEPARATE")
        assertThat(RunDirectory.of(mapOf(RunDirectory.SESSION to "SHARED"), runnerStep = true).errors.single()).contains("OWN, NEW or CONTINUE")
        assertThat(RunDirectory.of(mapOf(RunDirectory.SESSION to "CONTINUE"), runnerStep = true).errors.single()).contains("needs continueSession")
        val ignored = RunDirectory.of(mapOf(RunDirectory.SESSION to "NEW", RunDirectory.CONTINUE_SESSION to "a"), runnerStep = true)
        assertThat(ignored.continueFrom).isNull()
        assertThat(ignored.warnings.single()).contains("continueSession is ignored")
        val prepare = RunDirectory.of(mapOf(RunDirectory.PREPARE_ONLY to "true", RunDirectory.SESSION to "OWN"), runnerStep = true)
        assertThat(prepare.prepareOnly).isTrue()
        assertThat(prepare.warnings.single()).contains("prepare step starts no agent")
        assertThat(RunDirectory.of(mapOf(RunDirectory.SESSION to "OWN"), runnerStep = false).warnings.single()).contains("only coding agent steps")
        assertThat(RunDirectory.of(emptyMap(), runnerStep = false).warnings).isEmpty()
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
