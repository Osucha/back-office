package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.tasks.TaskOverrides

/** ADR 0057 section 4: a step continues the session of an earlier step in the same workspace and role. */
class SessionContinuationTest {

    private fun step(id: String, config: Map<String, Any?> = emptyMap()) =
        WorkflowNode.Task(id, id, task = TaskBinding("agent-task", TaskOverrides(config = config)))

    @Test
    fun `a derived role is a valid workspace key`() {
        listOf("implement", "Implement Task-List 2", "2nd_step", "a".repeat(40), "--", "x__y").forEach {
            assertThat(SessionContinuation.derivedRole(it)).describedAs(it).matches(RunnerWorkspace.KEY_PATTERN.pattern)
        }
        assertThat(SessionContinuation.derivedRole("Implement Task-List 2")).isEqualTo("implement-task-list")
        assertThat(SessionContinuation.derivedRole("2nd_step")).isEqualTo("s-2nd-step")
        assertThat(SessionContinuation.derivedRole("--")).isEqualTo("s")
    }

    @Test
    fun `the continuing step and the earlier step share the implicit run workspace and the earlier step's derived role`() {
        val nodes = listOf(step("implement"), step("fix", mapOf(SessionContinuation.KEY to "implement")), step("other"))

        assertThat(SessionContinuation.apply("fix", mapOf(SessionContinuation.KEY to "implement", "push" to true), nodes)).containsAllEntriesOf(
            mapOf(StepWorkspace.WORKSPACE to SessionContinuation.RUN_WORKSPACE, StepWorkspace.SESSION_ROLE to "implement", "push" to true),
        )
        assertThat(SessionContinuation.apply("implement", emptyMap(), nodes))
            .isEqualTo(mapOf(StepWorkspace.WORKSPACE to SessionContinuation.RUN_WORKSPACE, StepWorkspace.SESSION_ROLE to "implement"))
        assertThat(SessionContinuation.apply("other", mapOf("a" to 1), nodes)).describedAs("no continuation, unchanged").isEqualTo(mapOf("a" to 1))
    }

    @Test
    fun `the earlier step's own workspace and role win, and chains follow the first session`() {
        val nodes = listOf(
            step("first", mapOf(StepWorkspace.WORKSPACE to "main", StepWorkspace.SESSION_ROLE to "dev")),
            step("second", mapOf(SessionContinuation.KEY to "first")),
            step("third", mapOf(SessionContinuation.KEY to "second")),
        )
        val expected = mapOf(StepWorkspace.WORKSPACE to "main", StepWorkspace.SESSION_ROLE to "dev")
        assertThat(SessionContinuation.apply("first", nodes[0].task.overrides.config, nodes)).containsAllEntriesOf(expected)
        assertThat(SessionContinuation.apply("second", nodes[1].task.overrides.config, nodes)).containsAllEntriesOf(expected)
        assertThat(SessionContinuation.apply("third", nodes[2].task.overrides.config, nodes)).containsAllEntriesOf(expected)
    }

    @Test
    fun `a missing target or itself leave the config unchanged (the checks report them)`() {
        val nodes = listOf(step("aa", mapOf(SessionContinuation.KEY to "bb")), step("bb", mapOf(SessionContinuation.KEY to "aa")))
        assertThat(SessionContinuation.apply("cc", mapOf(SessionContinuation.KEY to "ghost"), nodes)).doesNotContainKey(StepWorkspace.WORKSPACE)
        assertThat(SessionContinuation.apply("self", mapOf(SessionContinuation.KEY to "self"), listOf(step("self"))))
            .isEqualTo(mapOf(SessionContinuation.KEY to "self"))
        assertThat(SessionContinuation.target(mapOf(SessionContinuation.KEY to "  "))).isNull()
    }

    @Test
    fun `the implicit run workspace needs no definition`() {
        val setting = StepWorkspace.of(mapOf(StepWorkspace.WORKSPACE to "run", StepWorkspace.SESSION_ROLE to "implement"), runnerStep = true)
        assertThat(setting.valid).isTrue()
        assertThat(setting.key).isEqualTo("run")
        assertThat(setting.sessionRole).isEqualTo("implement")
        assertThat(StepWorkspace.of(mapOf(StepWorkspace.WORKSPACE to "other"), runnerStep = true).valid).isFalse()
    }
}
