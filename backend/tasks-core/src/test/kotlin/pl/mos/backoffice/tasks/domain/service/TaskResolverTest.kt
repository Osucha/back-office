package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

class TaskResolverTest {

    private val lib = Library()
    private val resolver = TaskResolver(lib.lookup)

    @Test
    fun `a project template shadows the global one with the same key for that project only`() {
        lib.template("review", prompt = Prompt(system = "global"))
        lib.template("review", prompt = Prompt(system = "mine"), scope = Scope.Project(lib.projectId))

        assertThat(resolver.resolve("review", null)!!.prompt.system).isEqualTo("global")
        assertThat(resolver.resolve("review", lib.projectId)!!.prompt.system).isEqualTo("mine")
        assertThat(resolver.resolve("review", UUID.randomUUID())!!.prompt.system).describedAs("other projects see the global one").isEqualTo("global")
        assertThat(resolver.resolve("unknown", lib.projectId)).isNull()
    }

    @Test
    fun `a project template may extend the global template it shadows by its own key`() {
        lib.template("review", prompt = Prompt(system = "global rules"), produces = listOf("review"))
        lib.template("review", extendsKey = "review", prompt = Prompt(system = "project extras"), scope = Scope.Project(lib.projectId))

        val task = resolver.resolve("review", lib.projectId)!!

        assertThat(task.lineage).containsExactly("review", "review")
        assertThat(task.prompt.system).isEqualTo("global rules\n\nproject extras")
        assertThat(task.produces).containsExactly("review")
        assertThat(resolver.resolve("review", null)!!.prompt.system).isEqualTo("global rules")
    }

    @Test
    fun `a global template extending its own key is a cycle`() {
        lib.template("loop", extendsKey = "loop")
        assertThatThrownBy { resolver.resolve("loop", null) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a three level chain merges root to leaf and reports the lineage leaf first`() {
        lib.template("root", abstract = true, prompt = Prompt(system = "root system", user = "root user"), tags = listOf("aa"))
        lib.template("middle", extendsKey = "root", prompt = Prompt(system = "middle system"), produces = listOf("diff"), tags = listOf("bb"))
        lib.template("leaf", extendsKey = "middle", prompt = Prompt(user = "leaf user"), produces = listOf("mr"), tags = listOf("aa", "cc"), name = "Leaf")

        val task = resolver.resolve("leaf", null, on("opencode"))!!

        assertThat(task.lineage).containsExactly("leaf", "middle", "root")
        assertThat(task.name).isEqualTo("Leaf")
        assertThat(task.agent).isEqualTo(AgentSnapshot("opencode", "OpenCode", AgentHarness.OPENCODE))
        assertThat(task.prompt.system).isEqualTo("root system\n\nmiddle system")
        assertThat(task.prompt.user).isEqualTo("root user\n\nleaf user")
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(resolver.preview("leaf", null)!!.tags).containsExactly("aa", "bb", "cc")
    }

    @Test
    fun `ADR 0061 - the step's agent, else opencode for coding work, else the model answer, and child-wins scalars`() {
        lib.template("root", abstract = true, confidenceField = "cc")
        lib.template("leaf", extendsKey = "root", outputSchema = """{"type":"object"}""")
        lib.template("coder", extendsKey = "root", config = mapOf("push" to true))
        lib.template("other", extendsKey = "root", outputSchema = "{}")

        val leaf = resolver.preview("leaf", null)!!
        assertThat(leaf.agent.key).isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(leaf.agent.modelAnswer).isTrue()
        assertThat(leaf.needsCodingAgent).isFalse()
        assertThat(leaf.modelProfileId).describedAs("templates name no model").isNull()
        assertThat(leaf.outputSchema).isEqualTo("""{"type":"object"}""")
        assertThat(leaf.confidenceField).isEqualTo("cc")

        val coder = resolver.preview("coder", null)!!
        assertThat(coder.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(coder.needsCodingAgent).isTrue()
        assertThat(coder.pushes).isTrue()
        assertThat(resolver.resolve("coder", null, on("claude-code"))!!.agent.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(resolver.resolve("coder", null, TaskOverrides(config = mapOf("push" to false)))!!.agent.key)
            .describedAs("the step's config decides the kind of work").isEqualTo(AgentSnapshot.MODEL_ANSWER)

        assertThat(resolver.resolve("other", null)!!.outputSchema).isEqualTo("{}")
    }

    @Test
    fun `ADR 0061 - needsCodingAgent of a template reads its merged config, false for a broken chain`() {
        lib.template("root", abstract = true, config = mapOf("phase" to "PLAN"))
        val child = lib.template("child", extendsKey = "root")
        val undone = lib.template("undone", extendsKey = "root", config = mapOf("phase" to null))
        val broken = lib.template("broken", extendsKey = "ghost", config = mapOf("push" to true))

        assertThat(resolver.needsCodingAgent(child, null)).isTrue()
        assertThat(resolver.needsCodingAgent(undone, null)).isFalse()
        assertThat(resolver.needsCodingAgent(broken, null)).isFalse()
        assertThat(TaskResolver.defaultAgentKey(mapOf("prepareOnly" to true))).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(TaskResolver.defaultAgentKey(emptyMap())).isEqualTo(AgentSnapshot.MODEL_ANSWER)
    }

    @Test
    fun `model order is the step's, then the agent's`() {
        val agentModel = ModelProfileId.new()
        val stepModel = ModelProfileId.new()
        lib.agents.save(lib.agent("cheap", AgentHarness.OPENCODE).copy(modelProfileId = agentModel))
        lib.template("work")

        assertThat(resolver.resolve("work", null)!!.modelProfileId).describedAs("none: the defaults").isNull()
        val withAgent = resolver.resolve("work", null, TaskOverrides(agentKey = "cheap"))!!
        assertThat(withAgent.modelProfileId).isEqualTo(agentModel)
        assertThat(withAgent.agent.modelProfileId).isEqualTo(agentModel)
        assertThat(resolver.resolve("work", null, TaskOverrides(agentKey = "cheap", modelProfileId = stepModel))!!.modelProfileId).isEqualTo(stepModel)
    }

    @Test
    fun `the step's agent wins and a project agent shadows the global one`() {
        lib.agent("opencode", AgentHarness.CLAUDE_CODE, scope = Scope.Project(lib.projectId), name = "Project coder")
        lib.template("work")

        assertThat(resolver.resolve("work", null, TaskOverrides(agentKey = "opencode"))!!.agent.harness).isEqualTo(AgentHarness.OPENCODE)
        val shadowed = resolver.resolve("work", lib.projectId, TaskOverrides(agentKey = "opencode"))!!.agent
        assertThat(shadowed.name).isEqualTo("Project coder")
        assertThat(shadowed.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
    }

    @Test
    fun `an unknown agent is a problem and the step falls back to a model answer under its key`() {
        lib.template("work")

        val preview = resolver.preview("work", null, on("ghost"))!!
        assertThat(preview.check.problems).containsExactly("agent ghost does not exist")
        assertThat(preview.agent).isEqualTo(AgentSnapshot("ghost", "ghost"))
        assertThat(preview.executable).isFalse()
        assertThatThrownBy { resolver.resolve("work", null, on("ghost")) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("ghost")
    }

    @Test
    fun `without a model-answer agent in the library the default one is used without a problem`() {
        val bare = Library(builtInAgents = false)
        bare.template("work")

        val task = TaskResolver(bare.lookup).resolve("work", null)!!
        assertThat(task.agent).isEqualTo(AgentSnapshot.DEFAULT)
    }

    @Test
    fun `work that needs a coding agent is a problem for a model answer`() {
        lib.template("spec", config = mapOf("phase" to "SPECIFY"))
        lib.template("pusher", config = mapOf("push" to true))
        lib.template("prepare", config = mapOf("prepareOnly" to true))
        lib.template("blank-phase", config = mapOf("phase" to " ", "push" to false))

        val answer = on(AgentSnapshot.MODEL_ANSWER)
        assertThat(resolver.preview("spec", null, answer)!!.check.problems.single()).contains("Spec Kit phase needs a coding agent")
        assertThat(resolver.preview("pusher", null, answer)!!.check.problems.single()).contains("push needs a coding agent")
        assertThat(resolver.preview("prepare", null, answer)!!.check.problems.single()).contains("prepareOnly needs a coding agent")
        assertThat(resolver.preview("blank-phase", null, answer)!!.check.ok).isTrue()
        assertThat(resolver.preview("spec", null)!!.check.ok).describedAs("ADR 0061: no step agent, coding work runs with opencode").isTrue()
    }

    @Test
    fun `ADR 0057 - a coding agent may answer in an output schema`() {
        lib.template("review", outputSchema = "{}")

        assertThat(resolver.preview("review", null)!!.check.ok).isTrue()
        val coded = resolver.preview("review", null, TaskOverrides(agentKey = "claude-code"))!!
        assertThat(coded.check.ok).isTrue()
        assertThat(coded.executable).isTrue()
    }

    @Test
    fun `the legacy config key agent reads as the agent key on steps and is dropped on templates`() {
        lib.template("old", config = mapOf("agent" to "CLAUDE_CODE", "push" to true))
        lib.template("odd", config = mapOf("agent" to "SOMETHING"))

        val old = resolver.resolve("old", null)!!
        assertThat(old.agent.key).describedAs("ADR 0061: a template names no agent; pushing work falls back to opencode").isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(old.config).doesNotContainKey("agent").containsEntry("push", true)

        val step = resolver.resolve("old", null, TaskOverrides(config = mapOf("agent" to "CLAUDE_CODE")))!!
        assertThat(step.agent.key).isEqualTo(AgentSnapshot.CLAUDE_CODE)
        assertThat(step.config).doesNotContainKey("agent")
        assertThat(resolver.resolve("old", null, TaskOverrides(agentKey = "opencode", config = mapOf("agent" to "CLAUDE_CODE")))!!.agent.key)
            .describedAs("an explicit agent key wins over the legacy key").isEqualTo(AgentSnapshot.OPENCODE)

        val odd = resolver.resolve("odd", null)!!
        assertThat(odd.agent.key).isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(odd.config).describedAs("an unknown value is not a harness and stays").containsEntry("agent", "SOMETHING")
    }

    @Test
    fun `REPLACE drops the parent prompt while APPEND joins with a blank line, per field`() {
        lib.template("root", abstract = true, prompt = Prompt(system = "parent system", user = "parent user"))
        lib.template("append", extendsKey = "root", prompt = Prompt(system = "child system", mode = PromptMode.APPEND))
        lib.template("replace", extendsKey = "root", prompt = Prompt(system = "child system", mode = PromptMode.REPLACE))

        val appended = resolver.resolve("append", null)!!.prompt
        assertThat(appended.system).isEqualTo("parent system\n\nchild system")
        assertThat(appended.user).isEqualTo("parent user")

        val replaced = resolver.resolve("replace", null)!!.prompt
        assertThat(replaced.system).isEqualTo("child system")
        assertThat(replaced.user).describedAs("REPLACE only affects fields the child sets").isEqualTo("parent user")
    }

    @Test
    fun `config deep merges with child keys winning and null deleting`() {
        lib.template(
            "root", abstract = true,
            config = mapOf("agent" to "OPENCODE", "verifyCommand" to null, "push" to true, "mergeRequest" to mapOf("create" to true, "draft" to false, "labels" to listOf("ai"))),
        )
        lib.template(
            "leaf", extendsKey = "root",
            config = mapOf("agent" to "CLAUDE_CODE", "push" to null, "mergeRequest" to mapOf("draft" to true, "labels" to null), "extra" to mapOf("aa" to 1)),
        )

        val config = resolver.resolve("leaf", null)!!.config

        assertThat(config).doesNotContainKeys("agent", "push", "verifyCommand")
        assertThat(resolver.resolve("leaf", null)!!.agent.key)
            .describedAs("templates name no agent and the merged config needs none").isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(config["mergeRequest"]).isEqualTo(mapOf("create" to true, "draft" to true))
        assertThat(config["extra"]).isEqualTo(mapOf("aa" to 1))
    }

    @Test
    fun `ADR 0057 - skills are the agent's, resolved with project shadowing`() {
        listOf("aa", "bb", "cc").forEach { lib.skill(it) }
        lib.skill("bb", scope = Scope.Project(lib.projectId), content = "# project bb")
        lib.agent("writer", skills = listOf("aa", "bb"))
        lib.template("root", abstract = true)
        lib.template("leaf", extendsKey = "root")

        val skills = resolver.resolve("leaf", lib.projectId, on("writer"))!!.skills
        assertThat(skills.map { it.key }).containsExactly("aa", "bb")
        assertThat(skills.last().content).isEqualTo("# project bb")
        assertThat(resolver.resolve("leaf", null, on("writer"))!!.skills.last().content).isEqualTo("# bb")
        assertThat(resolver.resolve("leaf", null, on("writer"))!!.agent.skills).describedAs("the agent's own keys, for display").containsExactly("aa", "bb")
    }

    @Test
    fun `ADR 0060 - required skills of the lineage the agent lacks are warnings, not problems`() {
        listOf("aa", "bb", "cc").forEach { lib.skill(it) }
        lib.agent("writer", skills = listOf("aa"))
        lib.template("root", abstract = true, requiresSkills = listOf("aa", "bb"))
        lib.template("leaf", extendsKey = "root", requiresSkills = listOf("cc", "bb"))

        val preview = resolver.preview("leaf", null, on("writer"))!!
        assertThat(preview.requiresSkills).containsExactly("aa", "bb", "cc")
        assertThat(preview.check.ok).isTrue()
        assertThat(preview.executable).isTrue()
        assertThat(preview.check.warnings).hasSize(2)
        assertThat(preview.check.warnings[0]).contains("agent writer lacks skill bb")
        assertThat(preview.check.warnings[1]).contains("lacks skill cc")
    }

    @Test
    fun `ADR 0060 - a read-only coding agent on a step that pushes is a problem`() {
        lib.agent("reader", harness = AgentHarness.CLAUDE_CODE, access = AgentAccess.READ_ONLY)
        lib.template("look")
        lib.template("look-only", config = mapOf("push" to false))

        assertThat(resolver.preview("look", null, on("reader"))!!.check.problems.single()).contains("may only read (access READ_ONLY) but the step pushes")
        assertThat(resolver.preview("look-only", null, on("reader"))!!.check.ok).isTrue()
        assertThat(resolver.preview("look", null, TaskOverrides(agentKey = "reader", config = mapOf("prepareOnly" to true)))!!.check.ok).isTrue()
        assertThat(resolver.resolve("look-only", null, on("reader"))!!.agent.access).isEqualTo(AgentAccess.READ_ONLY)
    }

    @Test
    fun `node overrides are applied after the chain`() {
        listOf("aa", "bb", "xx").forEach { lib.skill(it) }
        val model = ModelProfileId.new()
        lib.agent("skilled", skills = listOf("aa", "bb"))
        lib.template("task", prompt = Prompt(system = "sys", user = "usr"), config = mapOf("k" to 1, "n" to mapOf("xx" to 1)))

        val overrides = TaskOverrides(
            agentKey = "skilled", modelProfileId = model, systemPromptAppend = "more sys", promptReplace = "new usr", promptAppend = "and more",
            config = mapOf("k" to 2, "n" to mapOf("y" to 2)),
        )
        val task = resolver.resolve("task", null, overrides)!!

        assertThat(task.modelProfileId).isEqualTo(model)
        assertThat(task.prompt.system).isEqualTo("sys\n\nmore sys")
        assertThat(task.prompt.user).isEqualTo("new usr\n\nand more")
        assertThat(task.skills.map { it.key }).containsExactly("aa", "bb")
        assertThat(task.config).isEqualTo(mapOf("k" to 2, "n" to mapOf("xx" to 1, "y" to 2)))
    }

    @Test
    fun `a cycle in the extends chain is a validation error`() {
        lib.template("aa", extendsKey = "bb")
        lib.template("bb", extendsKey = "cc")
        lib.template("cc", extendsKey = "aa")

        assertThatThrownBy { resolver.preview("aa", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("cycle")
            .hasMessageContaining("aa -> bb -> cc -> aa")
    }

    @Test
    fun `a chain deeper than eight templates is rejected`() {
        lib.template("t0")
        (1..8).forEach { lib.template("t$it", extendsKey = "t${it - 1}") }

        assertThat(resolver.resolve("t7", null)!!.lineage).hasSize(8)
        assertThatThrownBy { resolver.resolve("t8", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("exceeds 8")
    }

    @Test
    fun `an unknown parent is a validation error`() {
        lib.template("orphan", extendsKey = "ghost")
        assertThatThrownBy { resolver.resolve("orphan", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("ghost")
    }

    @Test
    fun `an abstract leaf previews but does not resolve for execution`() {
        lib.template("base", abstract = true, prompt = Prompt(system = "base"))

        val preview = resolver.preview("base", null)!!
        assertThat(preview.abstract).isTrue()
        assertThat(preview.agent).isEqualTo(AgentSnapshot("model-answer", "Model answer"))
        assertThat(preview.prompt.system).isEqualTo("base")
        assertThat(preview.executable).isFalse()

        assertThatThrownBy { resolver.resolve("base", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("abstract")
    }

    @Test
    fun `every task has the success and failure ports and the category is inherited, child wins`() {
        lib.template("root", abstract = true, category = TemplateCategory.ANALYSIS)
        lib.template("plain", extendsKey = "root")
        lib.template("special", extendsKey = "root", category = TemplateCategory.REVIEW)
        lib.template("bare")

        val plain = resolver.resolve("plain", null)!!
        assertThat(plain.category).isEqualTo(TemplateCategory.ANALYSIS)
        assertThat(plain.ports).containsExactly("success", "failure").isEqualTo(TaskResolver.DEFAULT_PORTS)
        assertThat(resolver.resolve("special", null)!!.category).isEqualTo(TemplateCategory.REVIEW)
        assertThat(resolver.preview("root", null)!!.category).isEqualTo(TemplateCategory.ANALYSIS)
        assertThat(resolver.resolve("bare", null)!!.category).isNull()
        assertThat(resolver.preview("plain", null)!!.check).isEqualTo(ResolutionCheck.OK)
    }

    @Test
    fun `automatic rules come first, global then project, followed by the agent's rules`() {
        lib.rule("g-auto", automatic = true, content = "global automatic")
        lib.rule("p-auto", scope = Scope.Project(lib.projectId), automatic = true, content = "project automatic")
        lib.rule("listed-one", content = "first")
        lib.rule("listed-two", content = "second")
        lib.rule("unused")
        lib.agent("ruled", rules = listOf("listed-one", "listed-two", "g-auto"))
        lib.template("root", abstract = true)
        lib.template("leaf", extendsKey = "root")

        val inProject = resolver.resolve("leaf", lib.projectId, on("ruled"))!!
        assertThat(inProject.rules).containsExactly(
            RuleSnapshot("g-auto", "g-auto", "global automatic", automatic = true),
            RuleSnapshot("p-auto", "p-auto", "project automatic", automatic = true),
            RuleSnapshot("listed-one", "listed-one", "first", automatic = false),
            RuleSnapshot("listed-two", "listed-two", "second", automatic = false),
        )
        assertThat(resolver.resolve("leaf", null, on("ruled"))!!.rules.map { it.key })
            .describedAs("no project: global automatic rules only, an automatic rule the agent lists is not repeated")
            .containsExactly("g-auto", "listed-one", "listed-two")
    }

    @Test
    fun `a project rule shadows a global automatic one in place, a non-automatic project rule switches it off`() {
        lib.rule("aa", automatic = true, content = "global aa")
        lib.rule("bb", automatic = true, content = "global bb")
        lib.rule("cc", automatic = true, content = "global cc")
        lib.rule("aa", scope = Scope.Project(lib.projectId), automatic = true, content = "project aa")
        lib.rule("bb", scope = Scope.Project(lib.projectId), automatic = false, content = "project bb")
        lib.template("tt")

        val rules = resolver.resolve("tt", lib.projectId)!!.rules
        assertThat(rules.map { it.key to it.content }).containsExactly("aa" to "project aa", "cc" to "global cc")
        assertThat(resolver.resolve("tt", null)!!.rules.map { it.content }).containsExactly("global aa", "global bb", "global cc")
    }

    @Test
    fun `a listed rule resolves with project shadowing`() {
        lib.rule("style", content = "global style")
        lib.rule("style", scope = Scope.Project(lib.projectId), content = "project style")
        lib.agent("styled", rules = listOf("style"))
        lib.template("tt")

        assertThat(resolver.resolve("tt", lib.projectId, on("styled"))!!.rules.single().content).isEqualTo("project style")
        assertThat(resolver.resolve("tt", null, on("styled"))!!.rules.single().content).isEqualTo("global style")
    }

    @Test
    fun `a missing rule is a check problem and the task is not executable`() {
        lib.agent("haunted", rules = listOf("ghost"))
        lib.template("tt")

        val preview = resolver.preview("tt", null, on("haunted"))!!
        assertThat(preview.rules).isEmpty()
        assertThat(preview.check.ok).isFalse()
        assertThat(preview.check.problems).containsExactly("rule ghost of agent haunted does not exist")
        assertThat(preview.executable).isFalse()
        assertThat(resolver.environment("haunted", null).unknownRules).containsExactly("ghost")
        assertThatThrownBy { resolver.resolve("tt", null, on("haunted")) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("rule ghost of agent haunted does not exist")
    }

    @Test
    fun `a skill key that names a rule counts as that rule`() {
        lib.skill("real-skill")
        lib.rule("was-a-skill", content = "now a rule")
        lib.rule("both")
        lib.agent("compat", skills = listOf("real-skill", "was-a-skill", "both"), rules = listOf("both"))
        lib.template("tt")

        val task = resolver.resolve("tt", null, on("compat"))!!
        assertThat(task.skills.map { it.key }).containsExactly("real-skill")
        assertThat(task.rules.map { it.key }).describedAs("listed once even when named in both lists").containsExactly("both", "was-a-skill")
        assertThat(task.rules.last().content).isEqualTo("now a rule")
    }

    @Test
    fun `an unknown skill is reported as a problem instead of being silently dropped`() {
        lib.agent("needy", skills = listOf("nope"))
        lib.template("tt")
        val preview = resolver.preview("tt", null, on("needy"))!!
        assertThat(preview.skills).isEmpty()
        assertThat(preview.check.problems).containsExactly("skill nope does not exist")
        assertThat(preview.executable).isFalse()
        assertThat(resolver.environment("needy", null).unknownSkills).containsExactly("nope")
    }

    @Test
    fun `ancestors are shadowed per project as well`() {
        lib.template("base", abstract = true, prompt = Prompt(system = "global base"))
        lib.template("base", abstract = true, prompt = Prompt(system = "project base"), scope = Scope.Project(lib.projectId))
        lib.template("child", extendsKey = "base", prompt = Prompt(system = "child"))

        assertThat(resolver.resolve("child", null)!!.prompt.system).isEqualTo("global base\n\nchild")
        assertThat(resolver.resolve("child", lib.projectId)!!.prompt.system).isEqualTo("project base\n\nchild")
    }

    @Test
    fun `run inputs are unioned root to leaf by name and the child field wins in place`() {
        lib.template("root", abstract = true, runInputs = listOf(RunInputField("input", "Input material", RunInputType.MULTILINE), RunInputField("hints", "Hints")))
        lib.template("middle", abstract = true, extendsKey = "root", runInputs = listOf(RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY, required = true)))
        lib.template("leaf", extendsKey = "middle", runInputs = listOf(RunInputField("input", "Story text", RunInputType.MULTILINE, required = true, help = "paste the story")))

        val leaf = resolver.resolve("leaf", null)!!

        assertThat(leaf.runInputs).containsExactly(
            RunInputField("input", "Story text", RunInputType.MULTILINE, required = true, help = "paste the story"),
            RunInputField("hints", "Hints"),
            RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY, required = true),
        )
        assertThat(resolver.preview("middle", null)!!.runInputs.map { it.name }).containsExactly("input", "hints", "issueKey")
    }

    @Test
    fun `a template without run inputs inherits its parent fields unchanged`() {
        lib.template("root", abstract = true, runInputs = listOf(RunInputField("input", "Input material")))
        lib.template("leaf", extendsKey = "root")

        assertThat(resolver.resolve("leaf", null)!!.runInputs).containsExactly(RunInputField("input", "Input material"))
    }

    // ----- ADR 0058: parameters -----------------------------------------------------------------------

    @Test
    fun `parameters merge root to leaf child wins by name and values come from the step, the nearest template, the default`() {
        lib.template(
            "root", abstract = true,
            parameters = listOf(
                TemplateParameter("task", "Task", TemplateParameterType.MULTILINE, default = "default task"),
                TemplateParameter("tone", "Tone", TemplateParameterType.CHOICE, options = listOf("dry", "warm"), default = "dry"),
                TemplateParameter("depth", "Depth", TemplateParameterType.NUMBER),
            ),
            parameterValues = mapOf("depth" to 1),
        )
        lib.template(
            "leaf", extendsKey = "root",
            parameters = listOf(TemplateParameter("tone", "Voice", TemplateParameterType.CHOICE, options = listOf("dry", "warm", "loud"))),
            parameterValues = mapOf("depth" to "3"),
        )

        val preview = resolver.preview("leaf", null, TaskOverrides(parameters = mapOf("tone" to "loud")))!!
        assertThat(preview.parameters.map { Triple(it.name, it.value, it.source) }).containsExactly(
            Triple("task", "default task", ParameterSource.DEFAULT),
            Triple("tone", "loud", ParameterSource.STEP),
            Triple("depth", "3", ParameterSource.TEMPLATE),
        )
        assertThat(preview.parameters[1].definition.label).describedAs("the child's declaration wins in place").isEqualTo("Voice")
        assertThat(preview.check.ok).isTrue()
        assertThat(preview.templateParameterValues).isEqualTo(mapOf("depth" to "3"))
        assertThat(resolver.resolve("leaf", null)!!.parameterValues).isEqualTo(mapOf("task" to "default task", "tone" to null, "depth" to "3"))
    }

    @Test
    fun `parameter problems - unknown names, wrong types and required without a value - block the step but not the preview`() {
        lib.template(
            "tt",
            parameters = listOf(
                TemplateParameter("count", "Count", TemplateParameterType.NUMBER),
                TemplateParameter("flag", "Flag", TemplateParameterType.BOOLEAN),
                TemplateParameter("pick", "Pick", TemplateParameterType.CHOICE, options = listOf("a", "b")),
                TemplateParameter("goal", "Goal", required = true),
            ),
            parameterValues = mapOf("ghost" to "x"),
        )

        val preview = resolver.preview("tt", null, TaskOverrides(parameters = mapOf("count" to "many", "flag" to "maybe", "pick" to "c", "other" to 1)))!!
        assertThat(preview.check.problems).containsExactlyInAnyOrder(
            "template tt sets parameter ghost, which no template of the lineage declares",
            "the step sets parameter other, which template tt does not declare",
            "parameter count: \"many\" is not a number",
            "parameter flag: \"maybe\" is not true or false",
            "parameter pick: \"c\" is not one of a, b",
            "parameter goal (Goal) needs a value",
        )
        assertThat(preview.needsParameters).isTrue()
        assertThatThrownBy { resolver.resolve("tt", null) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `numeric strings and booleans fit their types and a blank step value falls back to the template`() {
        lib.template(
            "tt",
            parameters = listOf(
                TemplateParameter("count", "Count", TemplateParameterType.NUMBER, required = true),
                TemplateParameter("flag", "Flag", TemplateParameterType.BOOLEAN),
            ),
            parameterValues = mapOf("count" to 2),
        )
        val task = resolver.resolve("tt", null, TaskOverrides(parameters = mapOf("count" to " ", "flag" to "TRUE")))!!
        assertThat(task.parameterValues).isEqualTo(mapOf("count" to 2, "flag" to "TRUE"))
        assertThat(resolver.preview("tt", null)!!.needsParameters).describedAs("the template sets the required one: a ready block").isFalse()
        assertThat(resolver.resolve("tt", null, TaskOverrides(parameters = mapOf("count" to "4.5")))!!.parameterValues["count"]).isEqualTo("4.5")
    }

    @Test
    fun `ADR 0057 - the agent snapshot carries instructions and MCP servers, the model comes from step, agent`() {
        val agentModel = ModelProfileId.new()
        lib.agents.save(
            pl.mos.backoffice.tasks.domain.model.Agent.create(
                pl.mos.backoffice.tasks.domain.model.Agent.Content(
                    key = "mcp", name = "With MCP", harness = AgentHarness.CLAUDE_CODE, modelProfileId = agentModel, instructions = "Be brief.",
                    mcpServers = listOf(AgentMcpServer("docs", "https://mcp.example.com/docs")),
                ),
                lib.now,
            ),
        )
        lib.templates.save(
            pl.mos.backoffice.tasks.domain.model.TaskTemplate.create(
                pl.mos.backoffice.tasks.domain.model.TaskTemplate.Content(key = "tt", name = "tt"),
                lib.now,
            ),
        )
        val task = resolver.resolve("tt", null, on("mcp"))!!
        assertThat(task.agent.instructions).isEqualTo("Be brief.")
        assertThat(task.agent.mcpServers.single().url).isEqualTo("https://mcp.example.com/docs")
        assertThat(task.modelProfileId).isEqualTo(agentModel)
        val stepModel = ModelProfileId.new()
        assertThat(resolver.resolve("tt", null, TaskOverrides(agentKey = "mcp", modelProfileId = stepModel))!!.modelProfileId).isEqualTo(stepModel)
    }

    /** ADR 0061: the step names the agent. */
    private fun on(agentKey: String) = TaskOverrides(agentKey = agentKey)
}
