package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library

/** ADR 0059: the agents of the TaskCatalog port, as benchmark candidates use them (global agents, their environment). */
class AgentCatalogTest {

    private val lib = Library()
    private val catalog = TaskCatalogService(lib.templates, lib.lookup)

    @Test
    fun `an agent is looked up by key, a project sees its own first, unknown agents are null`() {
        lib.agent("reviewer-x", name = "Global reviewer")
        lib.agent("reviewer-x", name = "Project reviewer", scope = Scope.Project(lib.projectId))

        assertThat(catalog.agent("reviewer-x", null)!!.name).isEqualTo("Global reviewer")
        assertThat(catalog.agent("reviewer-x", lib.projectId)!!.name).isEqualTo("Project reviewer")
        assertThat(catalog.agent(AgentSnapshot.CLAUDE_CODE, null)!!.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(catalog.agent("nobody", null)).isNull()
    }

    @Test
    fun `the environment of an agent, automatic rules first, then its own, its skills, missing ones left out`() {
        lib.rule("auto", automatic = true, content = "Always.")
        lib.rule("own", content = "Mine.")
        lib.skill("spec-writing", content = "# Specs")
        lib.agent("coder", AgentHarness.OPENCODE, name = "Coder", rules = listOf("own", "gone"), skills = listOf("spec-writing", "missing"), instructions = "Be careful.")

        val env = catalog.agentEnvironment("coder", null)!!
        assertThat(env.agent.name).isEqualTo("Coder")
        assertThat(env.agent.instructions).isEqualTo("Be careful.")
        assertThat(env.rules.map { it.key to it.automatic }).containsExactly("auto" to true, "own" to false)
        assertThat(env.skills.map { it.key to it.content }).containsExactly("spec-writing" to "# Specs")

        assertThat(catalog.agentEnvironment("nobody", null)).isNull()
        assertThat(catalog.agentEnvironment(AgentSnapshot.MODEL_ANSWER, null)!!.agent.modelAnswer).isTrue()
    }

    @Test
    fun `without the built-in model answer agent stored, the default one is its environment`() {
        val bare = Library(builtInAgents = false)
        val env = TaskCatalogService(bare.templates, bare.lookup).agentEnvironment(AgentSnapshot.MODEL_ANSWER, null)!!
        assertThat(env.agent).isEqualTo(AgentSnapshot.DEFAULT)
        assertThat(env.rules).isEmpty()
    }
}
