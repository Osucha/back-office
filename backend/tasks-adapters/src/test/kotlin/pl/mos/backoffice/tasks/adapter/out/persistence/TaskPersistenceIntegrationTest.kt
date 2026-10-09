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
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.tasks.PostgresTestSupport
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskInterfaceRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.SlotKind
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(properties = ["backoffice.tasks.seed-builtin=false"])
@Import(PostgresTestSupport::class)
class TaskPersistenceIntegrationTest {

    @Autowired lateinit var interfaces: TaskInterfaceRepository
    @Autowired lateinit var templates: TaskTemplateRepository
    @Autowired lateinit var skills: SkillRepository
    @Autowired lateinit var catalog: TaskCatalog

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val projectId: UUID = UUID.randomUUID()
    private val suffix = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `task interface round trips slots and ports`() {
        val saved = interfaces.save(
            TaskInterface.create(
                key = "rt-$suffix", name = "Round trip", description = "d",
                inputs = listOf(Slot("diff", SlotKind.ARTIFACT, true, "the diff"), Slot("mode", SlotKind.INPUT, false)),
                outputs = listOf(Slot("review", SlotKind.ARTIFACT), Slot("review", SlotKind.STATE)),
                ports = listOf("success", "failure", "changes"), builtIn = true, now = now,
            ),
        )

        val loaded = interfaces.findById(saved.id)!!
        assertThat(loaded).isEqualTo(saved)
        assertThat(interfaces.findByKey(saved.key)).isEqualTo(saved)
        assertThat(interfaces.findAll()).contains(saved)
        assertThat(loaded.requiredConsumes).containsExactly("diff")

        interfaces.delete(saved.id)
        assertThat(interfaces.findByKey(saved.key)).isNull()
    }

    @Test
    fun `template round trips every column including json config, scope and prompt mode, and answers the reference queries`() {
        val skill = skills.save(Skill.create(Skill.Content(key = "sk-$suffix", name = "S", content = "# s", tags = listOf("t")), now))
        val model = ModelProfileId.new()
        val content = TaskTemplate.Content(
            key = "tpl-$suffix", name = "Template", description = "desc", abstract = false, extendsKey = "base-$suffix",
            implements = listOf("if-$suffix"), executor = TaskExecutor.IMPLEMENT, scope = Scope.Project(projectId), modelProfileId = model,
            prompt = Prompt("sys", "usr", PromptMode.REPLACE), skills = listOf(skill.key), removeSkills = listOf("other"),
            config = mapOf("agent" to "OPENCODE", "verifyCommand" to null, "push" to true, "mergeRequest" to mapOf("create" to true, "labels" to listOf("ai", 1))),
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

        assertThat(templates.findByKeyAndScope(saved.key, Scope.Project(projectId))).isEqualTo(saved)
        assertThat(templates.findByKeyAndScope(saved.key, Scope.Global)).isNull()
        assertThat(templates.findVisible(projectId)).contains(saved)
        assertThat(templates.findVisible(null)).doesNotContain(saved)
        assertThat(templates.findVisible(UUID.randomUUID())).doesNotContain(saved)
        assertThat(templates.existsExtending("base-$suffix")).isTrue()
        assertThat(templates.existsExtending("nobody-$suffix")).isFalse()
        assertThat(templates.findKeysImplementing("if-$suffix")).containsExactly(saved.key)
        assertThat(templates.existsImplementing("if-$suffix")).isTrue()
        assertThat(templates.existsImplementing("if-none-$suffix")).isFalse()
        assertThat(templates.existsUsingSkill(skill.key)).isTrue()
        assertThat(templates.existsUsingSkill("other")).isFalse()

        val bumped = templates.save(loaded.update(content.copy(name = "v2"), now.plusSeconds(1)))
        assertThat(templates.findById(saved.id)!!.version).isEqualTo(2)
        assertThat(bumped.name).isEqualTo("v2")

        templates.delete(saved.id)
        skills.delete(skill.id)
        assertThat(templates.findById(saved.id)).isNull()
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
        val iface = interfaces.save(TaskInterface.create(key = "cat-if-$suffix", name = "I", outputs = listOf(Slot("out", SlotKind.ARTIFACT)), ports = listOf("success", "failure", "extra"), now = now))
        val skill = skills.save(Skill.create(Skill.Content(key = "cat-sk-$suffix", name = "S", content = "# s"), now))
        val base = templates.save(TaskTemplate.create(TaskTemplate.Content(key = "cat-base-$suffix", name = "Base", abstract = true, executor = TaskExecutor.LLM, prompt = Prompt(system = "base"), config = mapOf("a" to 1)), now))
        val leaf = templates.save(TaskTemplate.create(TaskTemplate.Content(key = "cat-leaf-$suffix", name = "Leaf", extendsKey = base.key, implements = listOf(iface.key), prompt = Prompt(system = "leaf"), produces = listOf("out"), skills = listOf(skill.key)), now))
        val shadow = templates.save(TaskTemplate.create(TaskTemplate.Content(key = leaf.key, name = "Mine", extendsKey = base.key, scope = Scope.Project(projectId), prompt = Prompt(system = "mine", mode = PromptMode.REPLACE), executor = TaskExecutor.SPEC_KIT), now))

        val global = catalog.resolve(leaf.key, null)!!
        assertThat(global.lineage).containsExactly(leaf.key, base.key)
        assertThat(global.executor).isEqualTo(TaskExecutor.LLM)
        assertThat(global.prompt.system).isEqualTo("base\n\nleaf")
        assertThat(global.skills.map { it.key }).containsExactly(skill.key)
        assertThat(global.ports).containsExactly("success", "failure", "extra")

        val mine = catalog.resolve(leaf.key, projectId, TaskOverrides(config = mapOf("a" to null, "b" to 2), promptAppend = "go"))!!
        assertThat(mine.name).isEqualTo("Mine")
        assertThat(mine.executor).isEqualTo(TaskExecutor.SPEC_KIT)
        assertThat(mine.prompt.system).isEqualTo("mine")
        assertThat(mine.prompt.user).isEqualTo("go")
        assertThat(mine.config).isEqualTo(mapOf("b" to 2))
        assertThat(catalog.resolve("missing-$suffix", null)).isNull()

        assertThat(catalog.palette(null).map { it.key }).contains(leaf.key).doesNotContain(base.key)
        val entry = catalog.palette(projectId).single { it.key == leaf.key }
        assertThat(entry.projectId).isEqualTo(projectId)
        assertThat(entry.executor).isEqualTo(TaskExecutor.SPEC_KIT)

        listOf(shadow, leaf, base).forEach { templates.delete(it.id) }
        skills.delete(skill.id)
        interfaces.delete(iface.id)
    }
}
