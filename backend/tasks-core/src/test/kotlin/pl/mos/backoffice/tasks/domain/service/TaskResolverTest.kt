package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library
import pl.mos.backoffice.tasks.support.Library.Companion.artifact
import pl.mos.backoffice.tasks.support.Library.Companion.state
import java.util.UUID

class TaskResolverTest {

    private val lib = Library()
    private val resolver = TaskResolver(lib.lookup)

    @Test
    fun `a project template shadows the global one with the same key for that project only`() {
        lib.template("review", executor = TaskExecutor.LLM, prompt = Prompt(system = "global"))
        lib.template("review", executor = TaskExecutor.LLM, prompt = Prompt(system = "mine"), scope = Scope.Project(lib.projectId))

        assertThat(resolver.resolve("review", null)!!.prompt.system).isEqualTo("global")
        assertThat(resolver.resolve("review", lib.projectId)!!.prompt.system).isEqualTo("mine")
        assertThat(resolver.resolve("review", UUID.randomUUID())!!.prompt.system).describedAs("other projects see the global one").isEqualTo("global")
        assertThat(resolver.resolve("unknown", lib.projectId)).isNull()
    }

    @Test
    fun `a project template may extend the global template it shadows by its own key`() {
        lib.template("review", executor = TaskExecutor.LLM, prompt = Prompt(system = "global rules"), produces = listOf("review"))
        lib.template("review", extendsKey = "review", prompt = Prompt(system = "project extras"), scope = Scope.Project(lib.projectId))

        val task = resolver.resolve("review", lib.projectId)!!

        assertThat(task.lineage).containsExactly("review", "review")
        assertThat(task.prompt.system).isEqualTo("global rules\n\nproject extras")
        assertThat(task.produces).containsExactly("review")
        assertThat(resolver.resolve("review", null)!!.prompt.system).isEqualTo("global rules")
    }

    @Test
    fun `a global template extending its own key is a cycle`() {
        lib.template("loop", executor = TaskExecutor.LLM, extendsKey = "loop")
        assertThatThrownBy { resolver.resolve("loop", null) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a three level chain merges root to leaf and reports the lineage leaf first`() {
        lib.template("root", abstract = true, executor = TaskExecutor.IMPLEMENT, prompt = Prompt(system = "root system", user = "root user"), tags = listOf("aa"))
        lib.template("middle", extendsKey = "root", prompt = Prompt(system = "middle system"), produces = listOf("diff"), tags = listOf("bb"))
        lib.template("leaf", extendsKey = "middle", prompt = Prompt(user = "leaf user"), produces = listOf("mr"), tags = listOf("aa", "cc"), name = "Leaf")

        val task = resolver.resolve("leaf", null)!!

        assertThat(task.lineage).containsExactly("leaf", "middle", "root")
        assertThat(task.name).isEqualTo("Leaf")
        assertThat(task.executor).isEqualTo(TaskExecutor.IMPLEMENT)
        assertThat(task.prompt.system).isEqualTo("root system\n\nmiddle system")
        assertThat(task.prompt.user).isEqualTo("root user\n\nleaf user")
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(resolver.preview("leaf", null)!!.tags).containsExactly("aa", "bb", "cc")
    }

    @Test
    fun `nearest executor and child-wins scalars`() {
        val rootModel = ModelProfileId.new()
        val leafModel = ModelProfileId.new()
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, outputSchema = "{}", confidenceField = "cc")
        lib.template("middle", extendsKey = "root", executor = TaskExecutor.SPEC_KIT)
        lib.template("leaf", extendsKey = "middle", outputSchema = """{"type":"object"}""")
        lib.template("other", extendsKey = "root")
        lib.templates.save(lib.templates.findByKeyAndScope("root", Scope.Global)!!.copy(modelProfileId = rootModel))
        lib.templates.save(lib.templates.findByKeyAndScope("leaf", Scope.Global)!!.copy(modelProfileId = leafModel))

        val leaf = resolver.resolve("leaf", null)!!
        assertThat(leaf.executor).isEqualTo(TaskExecutor.SPEC_KIT)
        assertThat(leaf.modelProfileId).isEqualTo(leafModel)
        assertThat(leaf.outputSchema).isEqualTo("""{"type":"object"}""")
        assertThat(leaf.confidenceField).isEqualTo("cc")

        val other = resolver.resolve("other", null)!!
        assertThat(other.executor).isEqualTo(TaskExecutor.LLM)
        assertThat(other.modelProfileId).isEqualTo(rootModel)
        assertThat(other.outputSchema).isEqualTo("{}")
    }

    @Test
    fun `REPLACE drops the parent prompt while APPEND joins with a blank line, per field`() {
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, prompt = Prompt(system = "parent system", user = "parent user"))
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
            "root", abstract = true, executor = TaskExecutor.IMPLEMENT,
            config = mapOf("agent" to "OPENCODE", "verifyCommand" to null, "push" to true, "mergeRequest" to mapOf("create" to true, "draft" to false, "labels" to listOf("ai"))),
        )
        lib.template(
            "leaf", extendsKey = "root",
            config = mapOf("agent" to "CLAUDE_CODE", "push" to null, "mergeRequest" to mapOf("draft" to true, "labels" to null), "extra" to mapOf("aa" to 1)),
        )

        val config = resolver.resolve("leaf", null)!!.config

        assertThat(config).containsEntry("agent", "CLAUDE_CODE")
        assertThat(config).doesNotContainKeys("push", "verifyCommand")
        assertThat(config["mergeRequest"]).isEqualTo(mapOf("create" to true, "draft" to true))
        assertThat(config["extra"]).isEqualTo(mapOf("aa" to 1))
    }

    @Test
    fun `skills are unioned in order minus the child removeSkills and resolved with project shadowing`() {
        listOf("aa", "bb", "cc").forEach { lib.skill(it) }
        lib.skill("bb", scope = Scope.Project(lib.projectId), content = "# project bb")
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, skills = listOf("aa", "bb"))
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
        lib.template("task", executor = TaskExecutor.LLM, prompt = Prompt(system = "sys", user = "usr"), skills = listOf("aa", "bb"), config = mapOf("k" to 1, "n" to mapOf("xx" to 1)))

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
        lib.template("aa", extendsKey = "bb", executor = TaskExecutor.LLM)
        lib.template("bb", extendsKey = "cc")
        lib.template("cc", extendsKey = "aa")

        assertThatThrownBy { resolver.preview("aa", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("cycle")
            .hasMessageContaining("aa -> bb -> cc -> aa")
    }

    @Test
    fun `a chain deeper than eight templates is rejected`() {
        lib.template("t0", executor = TaskExecutor.LLM)
        (1..8).forEach { lib.template("t$it", extendsKey = "t${it - 1}") }

        assertThat(resolver.resolve("t7", null)!!.lineage).hasSize(8)
        assertThatThrownBy { resolver.resolve("t8", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("exceeds 8")
    }

    @Test
    fun `an unknown parent is a validation error`() {
        lib.template("orphan", extendsKey = "ghost", executor = TaskExecutor.LLM)
        assertThatThrownBy { resolver.resolve("orphan", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("ghost")
    }

    @Test
    fun `an abstract leaf previews but does not resolve for execution`() {
        lib.template("base", abstract = true, prompt = Prompt(system = "base"))

        val preview = resolver.preview("base", null)!!
        assertThat(preview.abstract).isTrue()
        assertThat(preview.executor).isNull()
        assertThat(preview.prompt.system).isEqualTo("base")
        assertThat(preview.executable).isFalse()

        assertThatThrownBy { resolver.resolve("base", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("abstract")
            .hasMessageContaining("no executor")
    }

    @Test
    fun `interface check reports missing produces and consumes and unions the ports`() {
        lib.iface("reviewer", inputs = listOf(artifact("diff"), artifact("spec", required = false), Library.input("mode")), outputs = listOf(artifact("review"), state("review")), ports = listOf("success", "failure", "changes-requested"))
        lib.iface("notifier", ports = listOf("success", "sent"))
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, implements = listOf("notifier"))
        lib.template("good", extendsKey = "root", implements = listOf("reviewer"), consumes = listOf("diff"), produces = listOf("review"), runInputs = listOf(RunInputField("mode", "Mode")))
        lib.template("bad", extendsKey = "root", implements = listOf("reviewer", "missing"), consumes = listOf("spec"), produces = listOf("summary"))

        val good = resolver.preview("good", null)!!
        assertThat(good.interfaceCheck).isEqualTo(InterfaceCheck.OK)
        assertThat(good.interfaces).containsExactly("notifier", "reviewer")
        assertThat(good.ports).containsExactly("success", "failure", "sent", "changes-requested")
        assertThat(resolver.resolve("good", null)!!.ports).isEqualTo(good.ports)

        val bad = resolver.preview("bad", null)!!
        assertThat(bad.interfaceCheck.ok).isFalse()
        assertThat(bad.interfaceCheck.problems).containsExactly(
            "interface reviewer: output review is not in produces",
            "interface reviewer: required input diff is not in consumes",
            "interface reviewer: required run input mode is not in runInputs",
            "interface missing does not exist",
        )
        assertThatThrownBy { resolver.resolve("bad", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("output review is not in produces")
    }

    @Test
    fun `an unknown skill is reported as a problem instead of being silently dropped`() {
        lib.template("tt", executor = TaskExecutor.LLM, skills = listOf("nope"))
        val preview = resolver.preview("tt", null)!!
        assertThat(preview.skills).isEmpty()
        assertThat(preview.interfaceCheck.problems).containsExactly("skill nope does not exist")
    }

    @Test
    fun `ancestors are shadowed per project as well`() {
        lib.template("base", abstract = true, executor = TaskExecutor.LLM, prompt = Prompt(system = "global base"))
        lib.template("base", abstract = true, executor = TaskExecutor.LLM, prompt = Prompt(system = "project base"), scope = Scope.Project(lib.projectId))
        lib.template("child", extendsKey = "base", prompt = Prompt(system = "child"))

        assertThat(resolver.resolve("child", null)!!.prompt.system).isEqualTo("global base\n\nchild")
        assertThat(resolver.resolve("child", lib.projectId)!!.prompt.system).isEqualTo("project base\n\nchild")
    }

    @Test
    fun `run inputs are unioned root to leaf by name and the child field wins in place`() {
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, runInputs = listOf(RunInputField("input", "Input material", RunInputType.MULTILINE), RunInputField("hints", "Hints")))
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
        lib.template("root", abstract = true, executor = TaskExecutor.LLM, runInputs = listOf(RunInputField("input", "Input material")))
        lib.template("leaf", extendsKey = "root")

        assertThat(resolver.resolve("leaf", null)!!.runInputs).containsExactly(RunInputField("input", "Input material"))
    }

    @Test
    fun `a required interface INPUT slot must be declared as a run input, an optional one need not`() {
        lib.iface("jira-reader", inputs = listOf(Library.input("issueKey"), Library.input("notes", required = false)))
        lib.template("root", abstract = true, executor = TaskExecutor.FETCH_ISSUE, runInputs = listOf(RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY, required = true)))
        lib.template("declared", extendsKey = "root", implements = listOf("jira-reader"))
        lib.template("undeclared", executor = TaskExecutor.FETCH_ISSUE, implements = listOf("jira-reader"))

        assertThat(resolver.preview("declared", null)!!.interfaceCheck).isEqualTo(InterfaceCheck.OK)
        assertThat(resolver.preview("undeclared", null)!!.interfaceCheck.problems)
            .containsExactly("interface jira-reader: required run input issueKey is not in runInputs")
        assertThatThrownBy { resolver.resolve("undeclared", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("required run input issueKey is not in runInputs")
    }
}
