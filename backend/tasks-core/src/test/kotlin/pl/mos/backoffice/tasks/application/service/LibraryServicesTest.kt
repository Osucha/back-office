package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.tasks.application.TasksPermissions

import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.InstructionsMode
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

class LibraryServicesTest {

    private val lib = Library()
    private val other: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** A library curator who also edits the library of two projects; access rules are covered in LibraryAccessTest. */
    private val gate = FakeAuthorizationGate.permitting(
        TasksPermissions.LIBRARY_VIEW, TasksPermissions.LIBRARY_MANAGE, TasksPermissions.LIBRARY_PROJECT_MANAGE,
        projects = setOf(lib.projectId, other),
    )
    private val audit = RecordingAuditPort()
    private val rules = RuleService(lib.rules, lib.agents, lib.clock, gate, audit)
    private val templates = TaskTemplateService(lib.templates, lib.lookup, lib.clock, gate, audit)
    private val skills = SkillService(lib.skills, lib.agents, lib.clock, gate, audit)
    private val catalog = TaskCatalogService(lib.templates, lib.lookup)

    private fun content(key: String, scope: Scope = Scope.Global, extendsKey: String? = null, abstract: Boolean = false) =
        TaskTemplate.Content(key = key, name = key, scope = scope, extendsKey = extendsKey, abstract = abstract)

    @Test
    fun `rules are unique per scope, sorted by name, and cannot be deleted or renamed while an agent lists them`() {
        val rule = rules.create(Rule.Content(key = "be-brief", name = "Be brief", content = "Short answers.", automatic = true, tags = listOf("style", "style")))
        assertThat(rule.automatic).isTrue()
        assertThat(rule.tags).containsExactly("style")
        assertThat(rule.builtIn).isFalse()
        assertThatThrownBy { rules.create(Rule.Content(key = "be-brief", name = "Again", content = "x")) }.isInstanceOf(InvalidStateException::class.java)
        val mine = rules.create(Rule.Content(key = "be-brief", name = "Also brief", content = "Shorter.", scope = Scope.Project(lib.projectId)))
        val alpha = rules.create(Rule.Content(key = "zzz", name = "alpha", content = "a"))

        assertThat(rules.list().map { it.id }).containsExactly(alpha.id, rule.id)
        assertThat(rules.list(lib.projectId).map { it.id }).describedAs("by name, case-insensitive").containsExactly(alpha.id, mine.id, rule.id)
        assertThat(rules.get(rule.id).content).isEqualTo("Short answers.")

        val user = lib.agent("brief-agent", rules = listOf("be-brief"))
        assertThatThrownBy { rules.delete(rule.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("used by agents")
        assertThatThrownBy { rules.delete(mine.id) }.describedAs("the reference check is by key, whatever the scope").isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { rules.update(rule.id, Rule.Content(key = "renamed", name = "Be brief", content = "x")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { rules.update(alpha.id, Rule.Content(key = "be-brief", name = "alpha", content = "a")) }
            .describedAs("key clash in the same scope").isInstanceOf(InvalidStateException::class.java).hasMessageContaining("already exists")
        val updated = rules.update(rule.id, Rule.Content(name = "Be very brief", content = "Shortest.", automatic = false))
        assertThat(updated.key).describedAs("an update without a key keeps it").isEqualTo("be-brief")
        assertThat(updated.automatic).isFalse()
        assertThat(updated.createdAt).isEqualTo(rule.createdAt)

        lib.agents.delete(user.id)
        rules.delete(mine.id)
        rules.delete(rule.id)
        assertThatThrownBy { rules.get(rule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { rules.delete(rule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThat(audit.operations()).containsExactly(
            "RULE_CREATE", "RULE_CREATE", "RULE_CREATE", "RULE_UPDATE", "RULE_DELETE", "RULE_DELETE",
        )
    }

    @Test
    fun `a template, skill or rule created without a key gets a unique one derived from its name`() {
        val first = templates.create(TaskTemplate.Content(name = "Analiza zgłoszenia"))
        val second = templates.create(TaskTemplate.Content(name = "Analiza zgłoszenia", scope = Scope.Project(lib.projectId)))
        val third = templates.create(TaskTemplate.Content(name = "Analiza  Zgłoszenia!"))
        assertThat(first.key).isEqualTo("analiza-zgloszenia")
        assertThat(second.key).describedAs("unique across scopes, never shadows by accident").isEqualTo("analiza-zgloszenia-2")
        assertThat(third.key).isEqualTo("analiza-zgloszenia-3")
        assertThat(templates.create(TaskTemplate.Content(name = "2FA check")).key).isEqualTo("task-2fa-check")

        assertThat(skills.create(Skill.Content(name = "Kotlin style", content = "# k")).key).isEqualTo("kotlin-style")
        assertThat(skills.create(Skill.Content(name = "Kotlin style", content = "# k")).key).isEqualTo("kotlin-style-2")
        assertThat(skills.create(Skill.Content(name = "???", content = "# k")).key).isEqualTo("skill")
        assertThat(rules.create(Rule.Content(name = "Pisz krótko", content = "x")).key).isEqualTo("pisz-krotko")
        assertThat(rules.create(Rule.Content(name = "Pisz krótko", content = "y")).key).isEqualTo("pisz-krotko-2")
        assertThat(rules.create(Rule.Content(name = "1st rule", content = "y")).key).isEqualTo("rule-1st-rule")
    }

    @Test
    fun `an update without a key keeps it and an explicit key still works`() {
        val created = templates.create(TaskTemplate.Content(key = "my-step", name = "My step"))
        val renamed = templates.update(created.id, created.content.copy(key = null, name = "Completely different"))
        assertThat(renamed.key).isEqualTo("my-step")
        assertThat(renamed.name).isEqualTo("Completely different")

        val skill = skills.create(Skill.Content(key = "explicit-skill", name = "Whatever", content = "# s"))
        assertThat(skill.key).isEqualTo("explicit-skill")
        assertThat(skills.update(skill.id, Skill.Content(name = "Other", content = "# s2")).key).isEqualTo("explicit-skill")
        assertThatThrownBy { skills.create(Skill.Content(key = "Bad Key", name = "x", content = "# x")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a project template with the key of a global one and extending it customises that template for the project`() {
        templates.create(content("review").copy(instructions = "global rules", category = TemplateCategory.REVIEW))
        val shadow = templates.create(
            TaskTemplate.Content(key = "review", name = "Our review", extendsKey = "review", scope = Scope.Project(lib.projectId), instructions = "ours"),
        )
        assertThat(shadow.key).isEqualTo("review")

        val resolved = templates.resolve("review", lib.projectId)
        assertThat(resolved.name).isEqualTo("Our review")
        assertThat(resolved.instructions).isEqualTo("global rules\n\nours")
        assertThat(resolved.category).describedAs("inherited from the global template").isEqualTo(TemplateCategory.REVIEW)
        assertThat(templates.resolve("review").name).isEqualTo("review")
    }

    @Test
    fun `templates audit category and parameters`() {
        val created = templates.create(content("cat").copy(category = TemplateCategory.ANALYSIS))
        assertThat(audit.entries.last().details).containsEntry("category", "ANALYSIS").containsEntry("parameters", emptyList<String>())
        templates.update(created.id, created.content.copy(category = TemplateCategory.PLANNING, parameterValues = emptyMap(), parameters = listOf(
            pl.mos.backoffice.shared.tasks.TemplateParameter("p1", "P1", pl.mos.backoffice.shared.tasks.TemplateParameterType.NUMBER, required = true),
        )))
        val diff = audit.entries.last().details
        assertThat(diff["category"]).isEqualTo(mapOf("old" to "ANALYSIS", "new" to "PLANNING"))
        assertThat(diff["parameters"]).isEqualTo(mapOf("old" to emptyList<String>(), "new" to listOf("p1:NUMBER:true")))
        assertThatThrownBy { templates.create(content("bad").copy(parameterValues = mapOf("ghost" to 1))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("parameter ghost is not declared")
    }

    @Test
    fun `templates validate on save, bump version on update and refuse built-in or extended deletion`() {
        assertThatThrownBy { templates.create(content("child", extendsKey = "ghost")) }.isInstanceOf(ValidationException::class.java)

        val base = templates.create(content("base", abstract = true).copy(instructions = "base"))
        assertThat(base.version).isEqualTo(1)
        assertThatThrownBy { templates.create(content("base")) }.isInstanceOf(InvalidStateException::class.java)

        val child = templates.create(content("child", extendsKey = "base"))
        val updated = templates.update(child.id, child.content.copy(name = "Child v2"))
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.name).isEqualTo("Child v2")

        assertThatThrownBy { templates.update(base.id, base.content.copy(extendsKey = "child")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("cycle")
        assertThatThrownBy { templates.delete(base.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("extended")

        lib.templates.save(child.copy(builtIn = true))
        assertThatThrownBy { templates.delete(child.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("built in")
        assertThatThrownBy { templates.get(pl.mos.backoffice.tasks.domain.model.TaskTemplateId.new()) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `ADR 0062 - instructions and their mode are saved, audited by length and merged in the resolved view`() {
        val base = templates.create(content("base", abstract = true).copy(instructions = "base work"))
        val child = templates.create(content("child", extendsKey = "base").copy(instructions = "child work"))
        assertThat(audit.entries.last().details).containsEntry("instructionsLength", 10).containsEntry("instructionsMode", "APPEND")

        val updated = templates.update(child.id, child.content.copy(instructionsMode = InstructionsMode.REPLACE))

        assertThat(templates.get(child.id).instructionsMode).isEqualTo(InstructionsMode.REPLACE)
        assertThat(updated.version).isEqualTo(2)
        assertThat(audit.entries.last().details).containsEntry("instructionsMode", mapOf("old" to "APPEND", "new" to "REPLACE"))
        assertThat(templates.resolve("child").instructions).isEqualTo("child work")
        assertThat(catalog.resolve("child", null)!!.instructions).isEqualTo("child work")
        assertThat(templates.get(base.id).instructions).isEqualTo("base work")
    }

    @Test
    fun `list returns global plus the project templates and can hide abstract ones`() {
        templates.create(content("base", abstract = true))
        templates.create(content("review"))
        templates.create(content("review", scope = Scope.Project(lib.projectId)))
        templates.create(content("secret", scope = Scope.Project(other)))

        assertThat(templates.list().map { it.key }).containsExactly("base", "review")
        assertThat(templates.list(lib.projectId).map { it.key to it.scope.projectId }).containsExactly("base" to null, "review" to null, "review" to lib.projectId)
        assertThat(templates.list(lib.projectId, includeAbstract = false).map { it.key }).containsExactly("review", "review")
    }

    @Test
    fun `ADR 0061 - a template needs a coding agent when its merged config does`() {
        val base = templates.create(content("spec-base", abstract = true).copy(config = mapOf("codingAgent" to true)))
        val child = templates.create(content("spec-child", extendsKey = "spec-base"))
        val mine = templates.create(content("spec-child", scope = Scope.Project(lib.projectId), extendsKey = "spec-child").copy(config = mapOf("codingAgent" to null)))

        assertThat(templates.needsCodingAgent(base)).isTrue()
        assertThat(templates.needsCodingAgent(child)).isTrue()
        assertThat(templates.needsCodingAgent(mine)).describedAs("the project copy removes codingAgent").isFalse()
    }

    @Test
    fun `resolve previews abstract and broken templates and is 404 for unknown keys`() {
        val gone = lib.rule("gone")
        lib.agent("fading", rules = listOf("gone"))
        templates.create(content("base", abstract = true).copy(instructions = "hello"))
        templates.create(content("half", extendsKey = "base"))
        lib.rules.delete(gone.id)

        assertThat(templates.resolve("base").abstract).isTrue()
        assertThat(templates.resolve("base").instructions).isEqualTo("hello")
        val half = templates.resolveStep("half", null, TaskOverrides(agentKey = "fading"))
        assertThat(half.check.ok).isFalse()
        assertThat(half.check.problems).containsExactly("rule gone of agent fading does not exist")
        assertThat(half.executable).isFalse()
        assertThatThrownBy { templates.resolve("nope") }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `resolveStep shows what a workflow step gets with its overrides applied`() {
        templates.create(content("step").copy(instructions = "do the work", config = mapOf("agent" to "OPENCODE", "codingAgent" to true)))

        val step = templates.resolveStep(
            "step", null,
            TaskOverrides(instructionsAppend = "be brief", instructionsReplace = "only analyse", config = mapOf("agent" to "CLAUDE_CODE")),
        )

        assertThat(step.instructions).isEqualTo("only analyse\n\nbe brief")
        assertThat(step.config).doesNotContainKey("agent").containsEntry("codingAgent", true)
        assertThat(step.agent.key).describedAs("the legacy step key agent").isEqualTo(AgentSnapshot.CLAUDE_CODE)
        assertThat(templates.resolve("step").agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(templates.resolve("step").instructions).isEqualTo("do the work")
        assertThatThrownBy { templates.resolveStep("nope", null, TaskOverrides.NONE) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `skills are unique per scope and cannot be deleted or renamed while used`() {
        val skill = skills.create(Skill.Content(key = "ss", name = "S", content = "# s"))
        assertThatThrownBy { skills.create(Skill.Content(key = "ss", name = "S", content = "# s")) }.isInstanceOf(InvalidStateException::class.java)
        val mine = skills.create(Skill.Content(key = "ss", name = "Mine", content = "# mine", scope = Scope.Project(lib.projectId)))

        assertThat(skills.list().map { it.id }).containsExactly(skill.id)
        assertThat(skills.list(lib.projectId).map { it.id }).describedAs("by name").containsExactly(mine.id, skill.id)

        val user = lib.agent("skilled", skills = listOf("ss"))
        assertThatThrownBy { skills.delete(skill.id) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { skills.update(skill.id, Skill.Content(key = "renamed", name = "S", content = "# s")) }.isInstanceOf(InvalidStateException::class.java)
        assertThat(skills.update(skill.id, Skill.Content(key = "ss", name = "S2", content = "# s2")).name).isEqualTo("S2")

        assertThatThrownBy { skills.delete(mine.id) }.describedAs("the reference check is by key, whatever the scope").isInstanceOf(InvalidStateException::class.java)

        lib.agents.delete(user.id)
        skills.delete(mine.id)
        skills.delete(skill.id)
        assertThatThrownBy { skills.get(mine.id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a deprecated template stays in the palette flagged, for the steps that use it, and still resolves`() {
        templates.create(content("old-way").copy(tags = listOf(BuiltInLibrary.DEPRECATED_TAG)))
        templates.create(content("new-way"))

        assertThat(catalog.palette(null).associate { it.key to it.deprecated }).containsExactlyInAnyOrderEntriesOf(mapOf("old-way" to true, "new-way" to false))
        assertThat(catalog.resolve("old-way", null)).isNotNull()
    }

    @Test
    fun `the catalog palette lists concrete templates once per key with the project entry winning`() {
        templates.create(content("base", abstract = true))
        templates.create(content("review", extendsKey = "base").copy(category = TemplateCategory.REVIEW, name = "Global review"))
        templates.create(content("review", scope = Scope.Project(lib.projectId)).copy(name = "Project review"))
        templates.create(content("broken").copy(extendsKey = null))
        lib.templates.save(lib.templates.findByKeyAndScope("broken", Scope.Global)!!.copy(extendsKey = "ghost"))

        val global = catalog.palette(null)
        assertThat(global.map { it.key }).containsExactly("review")
        assertThat(global.single().name).isEqualTo("Global review")
        assertThat(global.single().ports).containsExactly("success", "failure")
        assertThat(global.single().category).isEqualTo(TemplateCategory.REVIEW)
        assertThat(global.single().needsCodingAgent).describedAs("ADR 0061: the palette says what kind of agent, not which").isFalse()

        val mine = catalog.palette(lib.projectId).single()
        assertThat(mine.name).isEqualTo("Project review")
        assertThat(mine.projectId).isEqualTo(lib.projectId)

        assertThat(catalog.resolve("review", lib.projectId)!!.name).isEqualTo("Project review")
        assertThat(catalog.resolve("nope", null)).isNull()
        assertThatThrownBy { catalog.resolve("base", null) }.isInstanceOf(ValidationException::class.java)
    }
}
