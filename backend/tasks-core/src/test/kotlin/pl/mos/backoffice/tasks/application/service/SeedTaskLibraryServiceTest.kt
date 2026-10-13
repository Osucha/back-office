package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library

class SeedTaskLibraryServiceTest {

    private val lib = Library()
    private val service = SeedTaskLibraryService(lib.interfaces, lib.templates, lib.skills, lib.clock)

    @Test
    fun `seeds the documented library once and never twice`() {
        val first = service.seed()
        assertThat(first.interfacesCreated).isEqualTo(9)
        assertThat(first.templatesCreated).isEqualTo(21)
        assertThat(first.skillsCreated).isEqualTo(12)

        val second = service.seed()
        assertThat(second.total).isZero()
        assertThat(lib.templates.rows).hasSize(21)
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

        assertThat(result.templatesCreated).isEqualTo(20)
        assertThat(result.skillsCreated).isEqualTo(11)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!.name).isEqualTo("My review")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.SPEC_WRITING, Scope.Global)!!.content).isEqualTo("# mine")
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
    }

    @Test
    fun `implement-claude-code inherits the OpenCode implementer and overrides the agent`() {
        service.seed()

        val task = lib.lookup.resolver.resolve(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, null)!!

        assertThat(task.lineage).containsExactly(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_OPENCODE, BuiltInLibrary.BASE_AGENT)
        assertThat(task.executor).isEqualTo(TaskExecutor.IMPLEMENT)
        assertThat(task.config["agent"]).isEqualTo("CLAUDE_CODE")
        assertThat(task.config["push"]).isEqualTo(true)
        assertThat(task.config["branchTemplate"]).isEqualTo("feature/{{issue.key | lower}}")
        assertThat(task.config["mergeRequest"]).isEqualTo(mapOf("create" to true, "titleTemplate" to "{{issue.key}}: {{issue.summary}}", "draft" to false))
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.IMPLEMENTER)
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(task.skills.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.CONVENTIONAL_COMMITS)
        assertThat(task.prompt.system).contains("{{project.constitution}}").contains("Honesty")
        assertThat(task.prompt.user).contains("{{issue.key}}")
    }

    @Test
    fun `LLM templates carry schemas, confidence and the interface contract`() {
        service.seed()

        val clarify = lib.lookup.resolver.resolve(BuiltInLibrary.CLARIFY_SPEC, null)!!
        assertThat(clarify.executor).isEqualTo(TaskExecutor.LLM)
        assertThat(clarify.confidenceField).isEqualTo("confidence")
        assertThat(clarify.outputSchema).contains("\"questions\"").contains("whyItMatters").contains("suggestedAnswer")
        assertThat(clarify.produces).containsExactly("confidence", "clarifications")

        val review = lib.lookup.resolver.resolve(BuiltInLibrary.CODE_REVIEW, null)!!
        assertThat(review.consumes).containsExactly("diff")
        assertThat(review.produces).containsExactly("review")
        assertThat(review.outputSchema).contains("BLOCKER").contains("\"verdict\"").contains("\"score\"")
        assertThat(review.skills.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.SECURITY_REVIEW_CHECKLIST)
        assertThat(review.prompt.system).contains("MINOR").contains("NIT")

        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.NOTIFY_JIRA, null)!!.config).containsKey("messageTemplate")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.TRANSITION_ISSUE, null)!!.config).containsKey("toStatus")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!.config["phase"]).isEqualTo("PLAN")
    }

    @Test
    fun `skills follow the SKILL md convention`() {
        service.seed()
        lib.skills.rows.values.forEach { skill ->
            assertThat(skill.content).describedAs(skill.key).startsWith("---\nname: ${skill.key}\ndescription: ")
            assertThat(skill.content.lines().count { it.trim().matches(Regex("^\\d+\\..*")) }).describedAs(skill.key).isBetween(8, 30)
            assertThat(skill.builtIn).isTrue()
        }
    }
}
