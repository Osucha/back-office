package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

class SeedTaskLibraryServiceTest {

    private val lib = Library()
    private val service = SeedTaskLibraryService(lib.interfaces, lib.templates, lib.skills, lib.clock)
    private val alice = Actor(PrincipalKind.USER, UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "Alice")

    @Test
    fun `seeds the documented library once and never twice`() {
        val first = service.seed()
        assertThat(first.interfacesCreated).isEqualTo(9)
        assertThat(first.templatesCreated).isEqualTo(24)
        assertThat(first.skillsCreated).isEqualTo(12)

        val second = service.seed()
        assertThat(second.total).isZero()
        assertThat(lib.templates.rows).hasSize(24)
        assertThat(lib.interfaces.findAll().map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.interfaceKeys)
        assertThat(lib.templates.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.templateKeys)
        assertThat(lib.skills.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.skillKeys)
        assertThat(lib.templates.rows.values).allSatisfy { assertThat(it.builtIn).isTrue(); assertThat(it.scope).isEqualTo(Scope.Global) }
    }

    @Test
    fun `entries the owner already has are left untouched, missing ones are restored`() {
        lib.template(BuiltInLibrary.CODE_REVIEW, executor = TaskExecutor.LLM, name = "My review")
        lib.skill(BuiltInLibrary.SPEC_WRITING, content = "# mine")
        lib.template(BuiltInLibrary.CODE_REVIEW, executor = TaskExecutor.LLM, scope = Scope.Project(lib.projectId), name = "Project review")

        val result = service.seed()

        assertThat(result.templatesCreated).isEqualTo(23)
        assertThat(result.skillsCreated).isEqualTo(11)
        assertThat(result.templatesUpgraded + result.skillsUpgraded).describedAs("entries that are not built-in are never upgraded").isZero()
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!.name).isEqualTo("My review")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.SPEC_WRITING, Scope.Global)!!.content).isEqualTo("# mine")
    }

    @Test
    fun `untouched built-ins of an older seed are upgraded completely, edited ones are left alone`() {
        service.seed()
        val review = lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!
        val oldReview = review.copy(prompt = Prompt(system = "old system", user = "old user"), config = mapOf("maxChunkLines" to 1500), skills = emptyList())
        lib.templates.save(oldReview)
        val analyse = lib.templates.findByKeyAndScope(BuiltInLibrary.ANALYSE_ISSUE, Scope.Global)!!
        lib.templates.save(analyse.copy(prompt = Prompt(user = "owner's prompt"), version = 2, updatedBy = alice))
        val skill = lib.skills.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!
        lib.skills.save(skill.copy(content = "---\nname: ask-dont-guess\ndescription: old\n---\n# old", description = "old"))
        val editedSkill = lib.skills.findByKeyAndScope(BuiltInLibrary.ADR_FORMAT, Scope.Global)!!
        lib.skills.save(editedSkill.copy(content = "# my ADR rules", updatedBy = alice))
        val iface = lib.interfaces.findByKey(BuiltInLibrary.SPECIFIER)!!
        lib.interfaces.save(iface.copy(description = "old description", updatedBy = null))

        val result = service.seed()

        assertThat(result.templatesUpgraded).isEqualTo(1)
        assertThat(result.skillsUpgraded).isEqualTo(1)
        assertThat(result.interfacesUpgraded).isEqualTo(1)
        val upgraded = lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!
        assertThat(upgraded.id).isEqualTo(review.id)
        assertThat(upgraded.version).isEqualTo(1)
        assertThat(upgraded.prompt).isEqualTo(Prompt(BuiltInLibrary.REVIEW_SYSTEM, BuiltInLibrary.REVIEW_USER))
        assertThat(upgraded.config).describedAs("a removed config key goes away").doesNotContainKey("maxChunkLines")
        assertThat(upgraded.skills).containsExactly(BuiltInLibrary.SECURITY_REVIEW_CHECKLIST)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.ANALYSE_ISSUE, Scope.Global)!!.prompt.user).isEqualTo("owner's prompt")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!.content).isEqualTo(BuiltInLibrary.ASK_DONT_GUESS_MD)
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.ADR_FORMAT, Scope.Global)!!.content).isEqualTo("# my ADR rules")
        assertThat(lib.interfaces.findByKey(BuiltInLibrary.SPECIFIER)!!.description).isEqualTo("Turns an issue into a feature specification.")
        assertThat(lib.interfaces.findByKey(BuiltInLibrary.SPECIFIER)!!.updatedBy).isEqualTo(Actor.SYSTEM)
        assertThat(service.seed().total).describedAs("an upgraded library is stable").isZero()
    }

    @Test
    fun `a stored copy that lost its null config values and has reordered maps does not count as changed`() {
        service.seed()
        lib.templates.rows.values.toList().forEach { t ->
            // what the JSON column gives back: no null values, keys in another order, whole numbers as Long
            val roundTripped = t.config.filterValues { it != null }.entries.reversed().associate { (k, v) -> k to if (v is Int) v.toLong() else v }
            lib.templates.save(t.copy(config = roundTripped, prompt = Prompt(t.prompt.system ?: "", t.prompt.user, t.prompt.mode)))
        }
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.IMPLEMENT_OPENCODE, Scope.Global)!!.config).doesNotContainKey("verifyCommand")

        assertThat(service.seed().total).isZero()
    }

    @Test
    fun `every seeded template validates and every concrete one resolves`() {
        service.seed()

        lib.templates.rows.values.forEach { template ->
            assertThat(lib.lookup.validator.problems(template)).describedAs(template.key).isEmpty()
            val preview = lib.lookup.resolver.preview(template.key, null)!!
            assertThat(preview.interfaceCheck.problems).describedAs(template.key).isEmpty()
            if (!template.abstract) assertThat(preview.executable).describedAs(template.key).isTrue()
        }
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_AGENT, null)!!.abstract).isTrue()
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_LLM, null)!!.abstract).isTrue()
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_CODING_AGENT, null)!!.abstract).isTrue()
    }

    @Test
    fun `every produced name of every built-in template is backed by its schema, its collected files or its executor`() {
        service.seed()

        val problems = lib.templates.rows.values.filterNot { it.abstract }.flatMap { template ->
            val task = lib.lookup.resolver.resolve(template.key, null)!!
            BuiltInLibrary.producedNameProblems(task.key, task.executor, task.produces, task.outputSchema, task.confidenceField, task.config)
        }

        assertThat(problems).isEmpty()
    }

    @Test
    fun `the produces check catches a schema field that does not match the produced name`() {
        val schema = """{ "type": "object", "properties": { "userStory": { "type": "string", "properties": { "x": {} } }, "confidence": { "type": "number" } } }"""
        assertThat(BuiltInLibrary.schemaProperties(schema)).containsExactly("userStory", "confidence")
        val mismatch = BuiltInLibrary.producedNameProblems("write-user-story", TaskExecutor.LLM, listOf("user-story", "confidence"), schema, "confidence", emptyMap())
        assertThat(mismatch).hasSize(1)
        assertThat(mismatch.single()).contains("\"user-story\"")
        assertThat(BuiltInLibrary.producedNameProblems("implement-task-list", TaskExecutor.IMPLEMENT, listOf("diff", "work-log"), null, null, mapOf("collectArtifacts" to listOf("work-log.md")))).isEmpty()
        assertThat(BuiltInLibrary.producedNameProblems("x", TaskExecutor.IMPLEMENT, listOf("work-log"), null, null, emptyMap())).hasSize(1)
        assertThat(BuiltInLibrary.producedNameProblems("x", TaskExecutor.LLM, listOf("a", "b"), null, null, emptyMap())).describedAs("only one name can take the whole answer").hasSize(2)
    }

    @Test
    fun `implement-claude-code inherits the OpenCode implementer and overrides the agent`() {
        service.seed()

        val task = lib.lookup.resolver.resolve(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, null)!!

        assertThat(task.lineage).containsExactly(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_OPENCODE, BuiltInLibrary.BASE_CODING_AGENT, BuiltInLibrary.BASE_AGENT)
        assertThat(task.executor).isEqualTo(TaskExecutor.IMPLEMENT)
        assertThat(task.config["agent"]).isEqualTo("CLAUDE_CODE")
        assertThat(task.config["push"]).isEqualTo(true)
        assertThat(task.config["branchTemplate"]).isEqualTo("feature/{{issue.key | lower}}")
        // the keys RunnerTaskSupport reads (a nested mergeRequest map was never read)
        assertThat(task.config["mergeRequest"]).isEqualTo(true)
        assertThat(task.config["mergeRequestTitleTemplate"]).isEqualTo("{{issue.key}}: {{issue.summary}}")
        assertThat(task.config["mergeRequestDraft"]).isEqualTo(false)
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.IMPLEMENTER)
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(task.skills.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        assertThat(task.prompt.system).contains("{{project.constitution}}").contains("Honesty").contains("Do not commit")
        assertThat(task.prompt.user).contains("{{issue.key}}").contains("{{artifacts.review}}").contains("verifyCommand").contains("Do not commit or push")
            .contains("Apply the skills: ${BuiltInLibrary.AGENT_GROUND_RULES}, ${BuiltInLibrary.ASK_DONT_GUESS}")
            .doesNotContain("conventional commits")
    }

    @Test
    fun `LLM templates carry schemas, confidence and the interface contract`() {
        service.seed()

        val clarify = lib.lookup.resolver.resolve(BuiltInLibrary.CLARIFY_SPEC, null)!!
        assertThat(clarify.executor).isEqualTo(TaskExecutor.LLM)
        assertThat(clarify.confidenceField).isEqualTo("confidence")
        assertThat(BuiltInLibrary.schemaProperties(clarify.outputSchema)).containsExactlyInAnyOrder("confidence", "clarifications", "summary")
        assertThat(clarify.prompt.user).contains("why it matters").contains("suggested default")
        assertThat(clarify.produces).containsExactly("confidence", "clarifications")
        assertThat(clarify.lineage).containsExactly(BuiltInLibrary.CLARIFY_SPEC, BuiltInLibrary.BASE_LLM, BuiltInLibrary.BASE_AGENT)
        assertThat(clarify.prompt.system).contains("no repository").contains("one JSON object").doesNotContain("Quote file paths")

        val review = lib.lookup.resolver.resolve(BuiltInLibrary.CODE_REVIEW, null)!!
        assertThat(review.consumes).containsExactly("diff")
        assertThat(review.produces).containsExactly("review")
        assertThat(review.outputSchema).contains("BLOCKER").contains("\"verdict\"").contains("\"score\"")
        assertThat(review.skills.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.SECURITY_REVIEW_CHECKLIST)
        assertThat(review.prompt.system).contains("MINOR").contains("NIT")
        assertThat(review.config).doesNotContainKey("maxChunkLines")
        val user = review.prompt.user!!
        assertThat(user.indexOf("return only the JSON")).describedAs("instructions come before the data").isLessThan(user.indexOf("{{artifacts.diff}}"))
        assertThat(user).contains("{{input.ci.mergeRequest.title}}").contains("{{#issue.key}}")
        assertThat(user.trimEnd()).endsWith("{{artifacts.diff}}")

        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.NOTIFY_JIRA, null)!!.config).containsKey("messageTemplate")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.TRANSITION_ISSUE, null)!!.config).containsKey("toStatus")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!.config["phase"]).isEqualTo("PLAN")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.FETCH_ISSUE, null)!!.config).describedAs("no config keys nothing reads").isEmpty()
    }

    @Test
    fun `Spec Kit prompts start with the subject and the issue and do not paste artefacts Spec Kit reads from disk`() {
        service.seed()

        val specify = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_SPECIFY, null)!!.prompt.user!!
        assertThat(specify.lines().first()).isEqualTo("{{issue.key}}: {{issue.summary}}")
        assertThat(specify).contains("{{issue.acceptanceCriteria}}").contains("Apply the skills: ${BuiltInLibrary.SPEC_WRITING}")
        assertThat(specify.indexOf("{{issue.description}}")).isLessThan(specify.indexOf("Phase SPECIFY"))
        assertThat(specify).doesNotContain("You are an engineering agent")

        val plan = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!.prompt.user!!
        val tasks = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_TASKS, null)!!.prompt.user!!
        assertThat(plan).doesNotContain("{{artifacts.spec}}").contains("{{feedback}}").contains("{{parameters.techHints}}")
        assertThat(tasks).doesNotContain("{{artifacts.plan}}").contains("{{feedback}}")
    }

    @Test
    fun `runner templates name their skills, skills say when to use them and agent-task guards an empty issue`() {
        service.seed()

        listOf(BuiltInLibrary.SPEC_KIT_SPECIFY, BuiltInLibrary.SPEC_KIT_PLAN, BuiltInLibrary.SPEC_KIT_TASKS, BuiltInLibrary.IMPLEMENT_OPENCODE,
            BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_TASK_LIST, BuiltInLibrary.AGENT_TASK).forEach { key ->
            val task = lib.lookup.resolver.resolve(key, null)!!
            val prompt = (task.prompt.system ?: "") + "\n" + (task.prompt.user ?: "")
            task.skills.forEach { assertThat(prompt).describedAs("$key names ${it.key}").contains(it.key) }
        }
        lib.skills.rows.values.forEach { skill ->
            // agents choose skills by their description; ": " would break the SKILL.md frontmatter
            assertThat(skill.description).describedAs(skill.key).contains("Use when").doesNotContain(": ")
        }
        listOf(BuiltInLibrary.KOTLIN_HEXAGONAL, BuiltInLibrary.ANGULAR_NG_ZORRO).forEach {
            assertThat(lib.skills.findByKeyAndScope(it, Scope.Global)!!.description).describedAs(it).startsWith("Example for the Back Office stack")
        }
        val agent = lib.lookup.resolver.resolve(BuiltInLibrary.AGENT_TASK, null)!!.prompt.system!!
        assertThat(agent).doesNotContain("\n{{issue.key}}: {{issue.summary}}\n\n{{issue.description}}").contains("{{#issue.key}}")
    }

    @Test
    fun `skills follow the SKILL md convention`() {
        service.seed()
        lib.skills.rows.values.forEach { skill ->
            assertThat(skill.content).describedAs(skill.key).startsWith("---\nname: ${skill.key}\ndescription: ${skill.description}\n---")
            assertThat(skill.content.lines().count { it.trim().matches(Regex("^\\d+\\..*")) }).describedAs(skill.key).isBetween(8, 30)
            assertThat(skill.builtIn).isTrue()
        }
    }
}
