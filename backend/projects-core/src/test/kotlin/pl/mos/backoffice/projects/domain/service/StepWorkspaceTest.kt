package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.WorkspaceSource

class StepWorkspaceTest {
    private val project = Fixtures.project()
    private val declared = listOf(Fixtures.workspace(project.id, "main"))

    @Test
    fun `no workspace by default, a declared one with its session role`() {
        assertThat(StepWorkspace.of(emptyMap(), runnerStep = true)).isEqualTo(StepWorkspace.Setting(null, null, false))
        val s = StepWorkspace.of(mapOf("workspace" to " main ", "sessionRole" to "implementer"), runnerStep = true, declared)
        assertThat(listOf(s.key, s.sessionRole, s.prepareOnly, s.valid)).containsExactly("main", "implementer", false, true)
        assertThat(s.definition).isEqualTo(declared.single())
        assertThat(StepWorkspace.of(mapOf("workspace" to "main"), true).errors).singleElement().asString().contains("not defined")
    }

    @Test
    fun `the workspace sources become the step repositories, the primary gets the push access`() {
        val ws = Fixtures.workspace(project.id, "main", sources = listOf(WorkspaceSource("standards", library = true), WorkspaceSource("main")))
        @Suppress("UNCHECKED_CAST")
        val entries = StepWorkspace.effectiveConfig(emptyMap(), ws, project, pushes = true)[StepRepositories.CONFIG_KEY] as List<Map<String, Any?>>
        assertThat(entries).containsExactly(
            mapOf("key" to "standards", "access" to "READ", "library" to true),
            mapOf("key" to "main", "access" to "PUSH_MR"),
        )
        assertThat(StepWorkspace.effectiveConfig(mapOf(StepRepositories.CONFIG_KEY to emptyList<Any>()), ws, project, true))
            .isEqualTo(mapOf(StepRepositories.CONFIG_KEY to emptyList<Any>()))
    }

    @Test
    fun `invalid keys and a prepare step without a workspace are errors`() {
        assertThat(StepWorkspace.of(mapOf("workspace" to "Main"), true).errors).singleElement().asString().contains("workspace \"Main\"")
        assertThat(StepWorkspace.of(mapOf("workspace" to "main", "sessionRole" to "x".repeat(21)), true, declared).errors).hasSize(1)
        assertThat(StepWorkspace.of(mapOf("prepareOnly" to true), true).errors).singleElement().asString().contains("needs a workspace")
    }

    @Test
    fun `a role without a workspace or on a prepare step is only a warning and is dropped`() {
        val noWorkspace = StepWorkspace.of(mapOf("sessionRole" to "reviewer"), true)
        assertThat(noWorkspace.valid).isTrue()
        assertThat(noWorkspace.sessionRole).isNull()
        assertThat(noWorkspace.warnings).singleElement().asString().contains("kept only in a workspace")
        val prepare = StepWorkspace.of(mapOf("workspace" to "main", "prepareOnly" to "true", "sessionRole" to "reviewer"), true, declared)
        assertThat(prepare.prepareOnly).isTrue()
        assertThat(prepare.sessionRole).isNull()
        assertThat(prepare.warnings).hasSize(1)
    }

    @Test
    fun `steps that do not run in a sandbox only get a warning`() {
        val s = StepWorkspace.of(mapOf("workspace" to "main"), runnerStep = false)
        assertThat(s.key).isNull()
        assertThat(s.warnings).hasSize(1)
    }
}
