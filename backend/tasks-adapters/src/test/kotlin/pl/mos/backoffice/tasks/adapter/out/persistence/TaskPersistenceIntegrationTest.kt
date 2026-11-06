package pl.mos.backoffice.tasks.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.tasks.PostgresTestSupport
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(properties = ["backoffice.tasks.seed-builtin=false"])
@Import(PostgresTestSupport::class)
class TaskPersistenceIntegrationTest {

    @Autowired lateinit var rules: RuleRepository
    @Autowired lateinit var agents: AgentRepository
    @Autowired lateinit var templates: TaskTemplateRepository
    @Autowired lateinit var skills: SkillRepository
    @Autowired lateinit var catalog: TaskCatalog

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val projectId: UUID = UUID.randomUUID()
    private val suffix = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `rule round trips, is looked up per scope and answers the automatic and key queries`() {
        val global = rules.save(Rule.create(Rule.Content(key = "ru-$suffix", name = "G", description = "d", content = "Be brief.", automatic = true, tags = listOf("a", "b")), now, builtIn = true, actor = Actor.SYSTEM))
        val mine = rules.save(Rule.create(Rule.Content(key = "ru-$suffix", name = "P", content = "Be briefer.", scope = Scope.Project(projectId)), now))
        val auto = rules.save(Rule.create(Rule.Content(key = "ra-$suffix", name = "A", content = "x", scope = Scope.Project(projectId), automatic = true), now))

        assertThat(rules.findById(global.id)).isEqualTo(global)
        assertThat(rules.findById(mine.id)).isEqualTo(mine)
        assertThat(rules.findByKeyAndScope("ru-$suffix", Scope.Global)).isEqualTo(global)
        assertThat(rules.findByKeyAndScope("ru-$suffix", Scope.Project(projectId))).isEqualTo(mine)
        assertThat(rules.findByKeyAndScope("ru-$suffix", Scope.Project(UUID.randomUUID()))).isNull()
        assertThat(rules.findVisible(null)).contains(global).doesNotContain(mine, auto)
        assertThat(rules.findVisible(projectId)).contains(global, mine, auto)
        assertThat(rules.findAutomatic(Scope.Global)).contains(global).doesNotContain(mine, auto)
        assertThat(rules.findAutomatic(Scope.Project(projectId))).containsExactly(auto)
        assertThat(rules.existsByKey("ru-$suffix")).isTrue()
        assertThat(rules.existsByKey("none-$suffix")).isFalse()

        val updated = rules.save(global.update(Rule.Content(name = "G2", content = "Shorter.", automatic = false), now.plusSeconds(1), Actor.SYSTEM))
        assertThat(rules.findById(global.id)).isEqualTo(updated)
        assertThat(rules.findAutomatic(Scope.Global)).doesNotContain(updated)

        listOf(global, mine, auto).forEach { rules.delete(it.id) }
        assertThat(rules.findById(global.id)).isNull()
        assertThat(rules.existsByKey("ru-$suffix")).isFalse()
    }

    @Test
    fun `template round trips every column including json config, scope and prompt mode, and answers the reference queries`() {
        val skill = skills.save(Skill.create(Skill.Content(key = "sk-$suffix", name = "S", content = "# s", tags = listOf("t")), now))
        val model = ModelProfileId.new()
        val content = TaskTemplate.Content(
            key = "tpl-$suffix", name = "Template", description = "desc", abstract = false, extendsKey = "base-$suffix",
            category = TemplateCategory.IMPLEMENTATION, agentKey = "ag-$suffix", scope = Scope.Project(projectId), modelProfileId = model,
            prompt = Prompt("sys", "usr", PromptMode.REPLACE),
            parameters = listOf(pl.mos.backoffice.shared.tasks.TemplateParameter("task", "Task", default = "x")), parameterValues = mapOf("task" to "y"),
            config = mapOf("verifyCommand" to null, "push" to true, "mergeRequest" to mapOf("create" to true, "labels" to listOf("ai", 1))),
            outputSchema = """{"type":"object"}""", confidenceField = "confidence", produces = listOf("diff"), consumes = listOf("tasks"), tags = listOf("x", "y"),
        )
        val actor = Actor(PrincipalKind.USER, UUID.randomUUID(), "Bob Builder")
        val saved = templates.save(TaskTemplate.create(content, now, builtIn = true, actor = actor))

        val loaded = templates.findById(saved.id)!!
        assertThat(loaded).isEqualTo(saved)
        assertThat(loaded.config).containsEntry("verifyCommand", null)
        assertThat(loaded.config["mergeRequest"]).isEqualTo(mapOf("create" to true, "labels" to listOf("ai", 1)))
        assertThat(loaded.scope).isEqualTo(Scope.Project(projectId))
        assertThat(loaded.prompt.mode).isEqualTo(PromptMode.REPLACE)
        assertThat(loaded.updatedBy).isEqualTo(actor)
        assertThat(loaded.category).isEqualTo(TemplateCategory.IMPLEMENTATION)
        assertThat(loaded.parameters.single().default).isEqualTo("x")
        assertThat(loaded.parameterValues).isEqualTo(mapOf("task" to "y"))

        assertThat(templates.findByKeyAndScope(saved.key, Scope.Project(projectId))).isEqualTo(saved)
        assertThat(templates.findByKeyAndScope(saved.key, Scope.Global)).isNull()
        assertThat(templates.findVisible(projectId)).contains(saved)
        assertThat(templates.findVisible(null)).doesNotContain(saved)
        assertThat(templates.findVisible(UUID.randomUUID())).doesNotContain(saved)
        assertThat(templates.existsExtending("base-$suffix")).isTrue()
        assertThat(templates.existsExtending("nobody-$suffix")).isFalse()
        val user = agents.save(
            pl.mos.backoffice.tasks.domain.model.Agent.create(
                pl.mos.backoffice.tasks.domain.model.Agent.Content(key = "ag-env-$suffix", name = "Env", rules = listOf("rl-$suffix", "rl2-$suffix"), skills = listOf(skill.key)),
                now,
            ),
        )
        assertThat(agents.findById(user.id)).isEqualTo(user)
        assertThat(agents.existsUsingRule("rl2-$suffix")).isTrue()
        assertThat(agents.existsUsingRule("ask-dont-guess-$suffix")).isFalse()
        assertThat(agents.existsUsingRule("rl-")).describedAs("whole keys only").isFalse()
        assertThat(templates.existsByKey(saved.key)).isTrue()
        assertThat(templates.existsByKey("none-$suffix")).isFalse()
        assertThat(agents.existsUsingSkill(skill.key)).isTrue()
        assertThat(agents.existsUsingSkill("other")).isFalse()
        assertThat(loaded.agentKey).isEqualTo("ag-$suffix")
        assertThat(templates.existsUsingAgent("ag-$suffix", null)).isTrue()
        assertThat(templates.existsUsingAgent("ag-$suffix", projectId)).isTrue()
        assertThat(templates.existsUsingAgent("ag-$suffix", UUID.randomUUID())).describedAs("another project's template does not see it").isFalse()
        assertThat(templates.existsUsingAgent("none-$suffix", null)).isFalse()

        val bumped = templates.save(loaded.update(content.copy(name = "v2"), now.plusSeconds(1)))
        assertThat(templates.findById(saved.id)!!.version).isEqualTo(2)
        assertThat(bumped.name).isEqualTo("v2")

        templates.delete(saved.id)
        skills.delete(skill.id)
        assertThat(templates.findById(saved.id)).isNull()
    }

    @Test
    fun `agent round trips, is looked up per scope and answers the key query, and V304 inserted the built-in agents`() {
        val model = pl.mos.backoffice.shared.ModelProfileId.new()
        val global = agents.save(Agent.create(Agent.Content(key = "ag-$suffix", name = "G", description = "d", harness = AgentHarness.CLAUDE_CODE, modelProfileId = model, tags = listOf("a")), now, builtIn = true, actor = Actor.SYSTEM))
        val mine = agents.save(Agent.create(Agent.Content(key = "ag-$suffix", name = "P", scope = Scope.Project(projectId)), now))

        assertThat(agents.findById(global.id)).isEqualTo(global)
        assertThat(agents.findByKeyAndScope("ag-$suffix", Scope.Global)).isEqualTo(global)
        assertThat(agents.findByKeyAndScope("ag-$suffix", Scope.Project(projectId))).isEqualTo(mine)
        assertThat(agents.findVisible(null)).contains(global).doesNotContain(mine)
        assertThat(agents.findVisible(projectId)).contains(global, mine)
        assertThat(agents.existsByKey("ag-$suffix")).isTrue()
        assertThat(agents.existsByKey("none-$suffix")).isFalse()
        assertThat(agents.findByKeyAndScope(AgentSnapshot.MODEL_ANSWER, Scope.Global)!!.harness).isNull()
        assertThat(agents.findByKeyAndScope(AgentSnapshot.CLAUDE_CODE, Scope.Global)!!.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(agents.findByKeyAndScope(AgentSnapshot.OPENCODE, Scope.Global)!!.builtIn).isTrue()

        listOf(global, mine).forEach { agents.delete(it.id) }
        assertThat(agents.findById(global.id)).isNull()
    }

    @Test
    fun `skill round trips and is looked up per scope`() {
        val global = skills.save(Skill.create(Skill.Content(key = "sc-$suffix", name = "G", content = "# g"), now, builtIn = true, actor = Actor.SYSTEM))
        val mine = skills.save(Skill.create(Skill.Content(key = "sc-$suffix", name = "P", content = "# p", scope = Scope.Project(projectId), tags = listOf("mine")), now))

        assertThat(skills.findById(mine.id)).isEqualTo(mine)
        assertThat(skills.findByKeyAndScope("sc-$suffix", Scope.Global)).isEqualTo(global)
        assertThat(skills.findByKeyAndScope("sc-$suffix", Scope.Project(projectId))).isEqualTo(mine)
        assertThat(skills.findVisible(null)).contains(global).doesNotContain(mine)
        assertThat(skills.findVisible(projectId)).contains(global, mine)

        skills.delete(mine.id)
        skills.delete(global.id)
        assertThat(skills.findById(global.id)).isNull()
    }

    @Test
    fun `the TaskCatalog bean resolves through the database with shadowing and overrides`() {
        val rule = rules.save(Rule.create(Rule.Content(key = "cat-ru-$suffix", name = "R", content = "Rule text"), now))
        val skill = skills.save(Skill.create(Skill.Content(key = "cat-sk-$suffix", name = "S", content = "# s"), now))
        val base = templates.save(TaskTemplate.create(TaskTemplate.Content(key = "cat-base-$suffix", name = "Base", abstract = true, prompt = Prompt(system = "base"), config = mapOf("a" to 1)), now))
        val catAgent = agents.save(
            pl.mos.backoffice.tasks.domain.model.Agent.create(
                pl.mos.backoffice.tasks.domain.model.Agent.Content(key = "cat-ag-$suffix", name = "Cat agent", rules = listOf(rule.key), skills = listOf(skill.key)),
                now,
            ),
        )
        val leaf = templates.save(TaskTemplate.create(TaskTemplate.Content(key = "cat-leaf-$suffix", name = "Leaf", extendsKey = base.key, category = TemplateCategory.REVIEW, agentKey = catAgent.key, prompt = Prompt(system = "leaf"), produces = listOf("out")), now))
        val shadow = templates.save(TaskTemplate.create(TaskTemplate.Content(key = leaf.key, name = "Mine", extendsKey = base.key, scope = Scope.Project(projectId), prompt = Prompt(system = "mine", mode = PromptMode.REPLACE), agentKey = AgentSnapshot.OPENCODE), now))

        val global = catalog.resolve(leaf.key, null)!!
        assertThat(global.lineage).containsExactly(leaf.key, base.key)
        assertThat(global.agent.key).isEqualTo(catAgent.key)
        assertThat(global.prompt.system).isEqualTo("base\n\nleaf")
        assertThat(global.skills.map { it.key }).containsExactly(skill.key)
        assertThat(global.ports).containsExactly("success", "failure")
        assertThat(global.category).isEqualTo(TemplateCategory.REVIEW)
        assertThat(global.rules.map { it.key to it.content }).contains(rule.key to "Rule text")

        val mine = catalog.resolve(leaf.key, projectId, TaskOverrides(config = mapOf("a" to null, "b" to 2), promptAppend = "go"))!!
        assertThat(mine.name).isEqualTo("Mine")
        assertThat(mine.agent.harness).describedAs("the built-in agent V304 inserted").isEqualTo(AgentHarness.OPENCODE)
        assertThat(mine.prompt.system).isEqualTo("mine")
        assertThat(mine.prompt.user).isEqualTo("go")
        assertThat(mine.config).isEqualTo(mapOf("b" to 2))
        assertThat(catalog.resolve("missing-$suffix", null)).isNull()

        assertThat(catalog.palette(null).map { it.key }).contains(leaf.key).doesNotContain(base.key)
        val entry = catalog.palette(projectId).single { it.key == leaf.key }
        assertThat(entry.projectId).isEqualTo(projectId)
        assertThat(entry.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(catalog.palette(null).single { it.key == leaf.key }.category).isEqualTo(TemplateCategory.REVIEW)

        listOf(shadow, leaf, base).forEach { templates.delete(it.id) }
        skills.delete(skill.id)
        rules.delete(rule.id)
    }
}
