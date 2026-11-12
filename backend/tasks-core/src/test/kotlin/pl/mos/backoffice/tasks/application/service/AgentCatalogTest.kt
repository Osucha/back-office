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
    fun `ADR 0061 - the agent profile has the library identity, the resolved environment and the lost keys`() {
        lib.rule("auto", automatic = true)
        lib.skill("spec-writing")
        lib.skills.save(lib.skills.findByKeyAndScope("spec-writing", Scope.Global)!!.copy(description = "How to write specs"))
        val stored = lib.agent("coder", AgentHarness.OPENCODE, rules = listOf("gone"), skills = listOf("spec-writing", "missing"))
        val mine = lib.agent("coder", AgentHarness.CLAUDE_CODE, scope = Scope.Project(lib.projectId))
        val byId = TaskCatalogService(lib.templates, lib.lookup, lib.agents)

        val profile = byId.agentProfile("coder", null)!!
        assertThat(profile.id).isEqualTo(stored.id.value)
        assertThat(profile.projectId).isNull()
        assertThat(profile.builtIn).isFalse()
        assertThat(profile.rules.map { it.key }).containsExactly("auto")
        assertThat(profile.skills.map { Triple(it.key, it.name, it.description) }).containsExactly(Triple("spec-writing", "spec-writing", "How to write specs"))
        assertThat(profile.unknownRules).containsExactly("gone")
        assertThat(profile.unknownSkills).containsExactly("missing")

        assertThat(byId.agentProfile("coder", lib.projectId)!!.projectId).describedAs("the project's agent shadows").isEqualTo(lib.projectId)
        assertThat(byId.agentProfile(AgentSnapshot.MODEL_ANSWER, null)!!.agent.modelAnswer).isTrue()
        val bare = Library(builtInAgents = false)
        assertThat(TaskCatalogService(bare.templates, bare.lookup).agentProfile(AgentSnapshot.MODEL_ANSWER, null)!!.id)
            .describedAs("the implicit model answer has no library id").isNull()
        assertThat(byId.agentProfile("nobody", null)).isNull()

        assertThat(byId.agentProfileById(mine.id.value, null)!!.agent.harness).describedAs("seen from its own project").isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(byId.agentProfileById(stored.id.value, lib.projectId)!!.agent.key).isEqualTo("coder")
        assertThat(byId.agentProfileById(java.util.UUID.randomUUID(), null)).isNull()
        assertThat(catalog.agentProfileById(stored.id.value, null)).describedAs("no agent repository: none by id").isNull()
    }

    @Test
    fun `ADR 0061 - step needs name the step's agent or the fallback, the required skills and the kind of work`() {
        lib.skill("adr-format")
        lib.template("adr", requiresSkills = listOf("adr-format", "ghost"))
        lib.template("pusher", config = mapOf("codingAgent" to true))
        lib.template("cycle", extendsKey = "cycle")

        val adr = catalog.stepNeeds("adr", null, pl.mos.backoffice.shared.tasks.TaskOverrides(agentKey = "claude-code"))!!
        assertThat(adr.agentKey).isEqualTo("claude-code")
        assertThat(adr.requiredSkills.map { it.key to it.name }).containsExactly("adr-format" to "adr-format", "ghost" to null)
        assertThat(adr.needsCodingAgent).isFalse()
        val pusher = catalog.stepNeeds("pusher", null)!!
        assertThat(pusher.agentKey).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(pusher.needsCodingAgent).isTrue()
        assertThat(catalog.stepNeeds("cycle", null)).isNull()
        assertThat(catalog.stepNeeds("unknown", null)).isNull()

        val palette = catalog.palette(null).associateBy { it.key }
        assertThat(palette.getValue("pusher").needsCodingAgent).isTrue()
        assertThat(palette.getValue("adr").requiresSkills).containsExactly("adr-format", "ghost")
    }

    @Test
    fun `without the built-in model answer agent stored, the default one is its environment`() {
        val bare = Library(builtInAgents = false)
        val env = TaskCatalogService(bare.templates, bare.lookup).agentEnvironment(AgentSnapshot.MODEL_ANSWER, null)!!
        assertThat(env.agent).isEqualTo(AgentSnapshot.DEFAULT)
        assertThat(env.rules).isEmpty()
    }
}
