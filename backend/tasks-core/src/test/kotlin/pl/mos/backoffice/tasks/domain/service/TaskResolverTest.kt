package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.RuleSnapshot
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
        lib.template("root", abstract = true, agentKey = "opencode", prompt = Prompt(system = "root system", user = "root user"), tags = listOf("aa"))
        lib.template("middle", extendsKey = "root", prompt = Prompt(system = "middle system"), produces = listOf("diff"), tags = listOf("bb"))
        lib.template("leaf", extendsKey = "middle", prompt = Prompt(user = "leaf user"), produces = listOf("mr"), tags = listOf("aa", "cc"), name = "Leaf")

        val task = resolver.resolve("leaf", null)!!

        assertThat(task.lineage).containsExactly("leaf", "middle", "root")
        assertThat(task.name).isEqualTo("Leaf")
        assertThat(task.agent).isEqualTo(AgentSnapshot("opencode", "OpenCode", AgentHarness.OPENCODE))
        assertThat(task.prompt.system).isEqualTo("root system\n\nmiddle system")
        assertThat(task.prompt.user).isEqualTo("root user\n\nleaf user")
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(resolver.preview("leaf", null)!!.tags).containsExactly("aa", "bb", "cc")
    }

    @Test
    fun `nearest agent and child-wins scalars`() {
        val rootModel = ModelProfileId.new()
        val leafModel = ModelProfileId.new()
        lib.template("root", abstract = true, confidenceField = "cc")
        lib.template("middle", extendsKey = "root", agentKey = "claude-code", abstract = true)
        lib.template("leaf", extendsKey = "middle", agentKey = AgentSnapshot.MODEL_ANSWER, outputSchema = """{"type":"object"}""")
        lib.template("coder", extendsKey = "middle")
        lib.template("other", extendsKey = "root", outputSchema = "{}")
        lib.templates.save(lib.templates.findByKeyAndScope("root", Scope.Global)!!.copy(modelProfileId = rootModel))
        lib.templates.save(lib.templates.findByKeyAndScope("leaf", Scope.Global)!!.copy(modelProfileId = leafModel))

        val leaf = resolver.resolve("leaf", null)!!
        assertThat(leaf.agent.key).isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(leaf.agent.modelAnswer).isTrue()
        assertThat(leaf.modelProfileId).isEqualTo(leafModel)
        assertThat(leaf.outputSchema).isEqualTo("""{"type":"object"}""")
        assertThat(leaf.confidenceField).isEqualTo("cc")

        assertThat(resolver.resolve("coder", null)!!.agent.harness).isEqualTo(AgentHarness.CLAUDE_CODE)

        val other = resolver.resolve("other", null)!!
        assertThat(other.agent.key).describedAs("no agent in the lineage").isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(other.modelProfileId).isEqualTo(rootModel)
        assertThat(other.outputSchema).isEqualTo("{}")
    }

    @Test
    fun `model order is the step's, then the agent's, then the lineage's`() {
        val templateModel = ModelProfileId.new()
        val agentModel = ModelProfileId.new()
        val stepModel = ModelProfileId.new()
        lib.agents.save(lib.agent("cheap", AgentHarness.OPENCODE).copy(modelProfileId = agentModel))
        lib.template("work", agentKey = "opencode")
        lib.templates.save(lib.templates.findByKeyAndScope("work", Scope.Global)!!.copy(modelProfileId = templateModel))

        assertThat(resolver.resolve("work", null)!!.modelProfileId).isEqualTo(templateModel)
        val withAgent = resolver.resolve("work", null, TaskOverrides(agentKey = "cheap"))!!
        assertThat(withAgent.modelProfileId).isEqualTo(agentModel)
        assertThat(withAgent.agent.modelProfileId).isEqualTo(agentModel)
        assertThat(resolver.resolve("work", null, TaskOverrides(agentKey = "cheap", modelProfileId = stepModel))!!.modelProfileId).isEqualTo(stepModel)
    }

    @Test
    fun `the step's agent wins and a project agent shadows the global one`() {
        lib.agent("opencode", AgentHarness.CLAUDE_CODE, scope = Scope.Project(lib.projectId), name = "Project coder")
        lib.template("work", agentKey = "claude-code")

        assertThat(resolver.resolve("work", null, TaskOverrides(agentKey = "opencode"))!!.agent.harness).isEqualTo(AgentHarness.OPENCODE)
        val shadowed = resolver.resolve("work", lib.projectId, TaskOverrides(agentKey = "opencode"))!!.agent
        assertThat(shadowed.name).isEqualTo("Project coder")
        assertThat(shadowed.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
    }

    @Test
    fun `an unknown agent is a problem and the step falls back to a model answer under its key`() {
        lib.template("work", agentKey = "ghost")

        val preview = resolver.preview("work", null)!!
        assertThat(preview.check.problems).containsExactly("agent ghost does not exist")
        assertThat(preview.agent).isEqualTo(AgentSnapshot("ghost", "ghost"))
        assertThat(preview.executable).isFalse()
        assertThatThrownBy { resolver.resolve("work", null) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("ghost")
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

        assertThat(resolver.preview("spec", null)!!.check.problems.single()).contains("Spec Kit phase needs a coding agent")
        assertThat(resolver.preview("pusher", null)!!.check.problems.single()).contains("push needs a coding agent")
        assertThat(resolver.preview("prepare", null)!!.check.problems.single()).contains("prepareOnly needs a coding agent")
        assertThat(resolver.preview("blank-phase", null)!!.check.ok).isTrue()
        assertThat(resolver.preview("spec", null, TaskOverrides(agentKey = "opencode"))!!.check.ok).isTrue()
    }

    @Test
    fun `a structured answer is a problem for a coding agent`() {
        lib.template("review", outputSchema = "{}")

        assertThat(resolver.preview("review", null)!!.check.ok).isTrue()
        val coded = resolver.preview("review", null, TaskOverrides(agentKey = "claude-code"))!!
        assertThat(coded.check.problems.single()).contains("output schema").contains("needs a model answer agent")
        assertThat(coded.executable).isFalse()
    }

    @Test
    fun `the legacy config key agent reads as the agent key, on templates and steps`() {
        lib.template("old", config = mapOf("agent" to "CLAUDE_CODE", "push" to true))
        lib.template("odd", config = mapOf("agent" to "SOMETHING"))

        val old = resolver.resolve("old", null)!!
        assertThat(old.agent.key).isEqualTo(AgentSnapshot.CLAUDE_CODE)
        assertThat(old.config).doesNotContainKey("agent").containsEntry("push", true)

        val step = resolver.resolve("old", null, TaskOverrides(config = mapOf("agent" to "OPENCODE")))!!
        assertThat(step.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(step.config).doesNotContainKey("agent")
        assertThat(resolver.resolve("old", null, TaskOverrides(agentKey = "claude-code", config = mapOf("agent" to "OPENCODE")))!!.agent.key)
            .describedAs("an explicit agent key wins over the legacy key").isEqualTo(AgentSnapshot.CLAUDE_CODE)

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
            "root", abstract = true, agentKey = "opencode",
            config = mapOf("agent" to "OPENCODE", "verifyCommand" to null, "push" to true, "mergeRequest" to mapOf("create" to true, "draft" to false, "labels" to listOf("ai"))),
        )
        lib.template(
            "leaf", extendsKey = "root",
            config = mapOf("agent" to "CLAUDE_CODE", "push" to null, "mergeRequest" to mapOf("draft" to true, "labels" to null), "extra" to mapOf("aa" to 1)),
        )

        val config = resolver.resolve("leaf", null)!!.config

        assertThat(config).doesNotContainKeys("agent", "push", "verifyCommand")
        assertThat(resolver.resolve("leaf", null)!!.agent.key).describedAs("the legacy key of the leaf wins").isEqualTo(AgentSnapshot.CLAUDE_CODE)
        assertThat(config["mergeRequest"]).isEqualTo(mapOf("create" to true, "draft" to true))
        assertThat(config["extra"]).isEqualTo(mapOf("aa" to 1))
    }

    @Test
    fun `skills are unioned in order minus the child removeSkills and resolved with project shadowing`() {
        listOf("aa", "bb", "cc").forEach { lib.skill(it) }
        lib.skill("bb", scope = Scope.Project(lib.projectId), content = "# project bb")
        lib.template("root", abstract = true, skills = listOf("aa", "bb"))
        lib.template("leaf", extendsKey = "root", skills = listOf("cc", "aa"), removeSkills = listOf("aa"))

        val skills = resolver.resolve("leaf", lib.projectId)!!.skills

        assertThat(skills.map { it.key }).containsExactly("bb", "cc")
        assertThat(skills.first().content).isEqualTo("# project bb")
        assertThat(resolver.resolve("leaf", null)!!.skills.first().content).isEqualTo("# bb")
    }

    @Test
    fun `node overrides are applied after the chain`() {
        listOf("aa", "bb", "xx").forEach { lib.skill(it) }
        val model = ModelProfileId.new()
        lib.template("task", prompt = Prompt(system = "sys", user = "usr"), skills = listOf("aa", "bb"), config = mapOf("k" to 1, "n" to mapOf("xx" to 1)))

        val overrides = TaskOverrides(
            modelProfileId = model, systemPromptAppend = "more sys", promptReplace = "new usr", promptAppend = "and more",
            addSkills = listOf("xx"), removeSkills = listOf("aa"), config = mapOf("k" to 2, "n" to mapOf("y" to 2)),
        )
        val task = resolver.resolve("task", null, overrides)!!

        assertThat(task.modelProfileId).isEqualTo(model)
        assertThat(task.prompt.system).isEqualTo("sys\n\nmore sys")
        assertThat(task.prompt.user).isEqualTo("new usr\n\nand more")
        assertThat(task.skills.map { it.key }).containsExactly("bb", "xx")
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
    fun `automatic rules come first, global then project, followed by the rules the lineage lists`() {
        lib.rule("g-auto", automatic = true, content = "global automatic")
        lib.rule("p-auto", scope = Scope.Project(lib.projectId), automatic = true, content = "project automatic")
        lib.rule("listed-root", content = "from root")
        lib.rule("listed-leaf", content = "from leaf")
        lib.rule("unused")
        lib.template("root", abstract = true, rules = listOf("listed-root"))
        lib.template("leaf", extendsKey = "root", rules = listOf("listed-leaf", "g-auto"))

        val inProject = resolver.resolve("leaf", lib.projectId)!!
        assertThat(inProject.rules).containsExactly(
            RuleSnapshot("g-auto", "g-auto", "global automatic", automatic = true),
            RuleSnapshot("p-auto", "p-auto", "project automatic", automatic = true),
            RuleSnapshot("listed-root", "listed-root", "from root", automatic = false),
            RuleSnapshot("listed-leaf", "listed-leaf", "from leaf", automatic = false),
        )
        assertThat(resolver.resolve("leaf", null)!!.rules.map { it.key })
            .describedAs("no project: global automatic rules only, a listed automatic rule is not repeated")
            .containsExactly("g-auto", "listed-root", "listed-leaf")
        assertThat(resolver.resolve("leaf", UUID.randomUUID())!!.rules.map { it.key }).containsExactly("g-auto", "listed-root", "listed-leaf")
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
        lib.template("tt", rules = listOf("style"))

        assertThat(resolver.resolve("tt", lib.projectId)!!.rules.single().content).isEqualTo("project style")
        assertThat(resolver.resolve("tt", null)!!.rules.single().content).isEqualTo("global style")
    }

    @Test
    fun `removeRules drops inherited and automatic rules and a child may list a removed rule again`() {
        lib.rule("auto", automatic = true)
        lib.rule("inherited")
        lib.rule("other")
        lib.template("root", abstract = true, rules = listOf("inherited", "other"))
        lib.template("middle", abstract = true, extendsKey = "root", removeRules = listOf("inherited", "auto"))
        lib.template("leaf", extendsKey = "middle")
        lib.template("back", extendsKey = "middle", rules = listOf("auto"))

        assertThat(resolver.resolve("leaf", null)!!.rules.map { it.key }).containsExactly("other")
        assertThat(resolver.resolve("back", null)!!.rules.map { it.key to it.automatic })
            .describedAs("listing it again brings the automatic rule back")
            .containsExactly("auto" to true, "other" to false)
    }

    @Test
    fun `a missing rule is a check problem and the task is not executable`() {
        lib.template("tt", rules = listOf("ghost"))

        val preview = resolver.preview("tt", null)!!
        assertThat(preview.rules).isEmpty()
        assertThat(preview.check.ok).isFalse()
        assertThat(preview.check.problems).containsExactly("rule ghost does not exist")
        assertThat(preview.executable).isFalse()
        assertThatThrownBy { resolver.resolve("tt", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("rule ghost does not exist")
    }

    @Test
    fun `a skill key that names a rule counts as that rule`() {
        lib.skill("real-skill")
        lib.rule("was-a-skill", content = "now a rule")
        lib.rule("both")
        lib.template("tt", skills = listOf("real-skill", "was-a-skill", "both"), rules = listOf("both"))

        val task = resolver.resolve("tt", null)!!
        assertThat(task.skills.map { it.key }).containsExactly("real-skill")
        assertThat(task.rules.map { it.key }).describedAs("listed once even when named in both lists").containsExactly("both", "was-a-skill")
        assertThat(task.rules.last().content).isEqualTo("now a rule")
    }

    @Test
    fun `an unknown skill is reported as a problem instead of being silently dropped`() {
        lib.template("tt", skills = listOf("nope"))
        val preview = resolver.preview("tt", null)!!
        assertThat(preview.skills).isEmpty()
        assertThat(preview.check.problems).containsExactly("skill nope does not exist")
        assertThat(preview.executable).isFalse()
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
}
