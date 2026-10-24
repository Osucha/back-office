package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.service.JsonSyntax
import pl.mos.backoffice.tasks.support.Library

/** The SDLC part of the seed (ADR 0019): keys other contexts rely on, interface contracts, schemas and inheritance. */
class SdlcSeedTest {

    private val lib = Library()
    private val service = SeedTaskLibraryService(lib.interfaces, lib.templates, lib.skills, lib.clock)

    @Test
    fun `the keys the change-request workflow uses exist exactly`() {
        service.seed()
        listOf("impact-analysis", "write-user-story", "plan-task-list", "implement-task-list", "write-use-case", "write-adr", "review-against-spec").forEach {
            assertThat(lib.templates.findByKeyAndScope(it, Scope.Global)).describedAs(it).isNotNull()
        }
        listOf("requirement-writer", "change-analyst", "task-planner", "implementer", "reviewer").forEach {
            assertThat(lib.interfaces.findByKey(it)).describedAs(it).isNotNull()
        }
        listOf("agent-ground-rules", "ask-dont-guess", "definition-of-ready-done", "user-story-format", "use-case-format", "adr-format", "build-guardrails").forEach {
            assertThat(lib.skills.findByKeyAndScope(it, Scope.Global)).describedAs(it).isNotNull()
        }
    }

    @Test
    fun `seeding twice creates the SDLC entries once`() {
        val first = service.seed()
        val second = service.seed()
        assertThat(first.total).isEqualTo(9 + 26 + 12)
        assertThat(second.total).isZero()
        assertThat(lib.templates.rows.values.map { it.key }.filter { it in BuiltInLibrary.sdlcTemplateKeys }).hasSize(BuiltInLibrary.sdlcTemplateKeys.size)
        assertThat(lib.skills.rows.values.map { it.key }.filter { it in BuiltInLibrary.sdlcSkillKeys }).hasSize(BuiltInLibrary.sdlcSkillKeys.size)
        assertThat(lib.templates.rows.values).allSatisfy { assertThat(it.updatedBy).isEqualTo(Actor.SYSTEM) }
    }

    @Test
    fun `every SDLC template resolves executable without interface problems and valid schemas`() {
        service.seed()
        BuiltInLibrary.sdlcTemplateKeys.forEach { key ->
            val preview = lib.lookup.resolver.preview(key, null)!!
            assertThat(preview.interfaceCheck.problems).describedAs(key).isEmpty()
            assertThat(preview.executable).describedAs(key).isTrue()
            preview.outputSchema?.let { assertThat(JsonSyntax.problem(it)).describedAs("$key schema").isNull() }
            assertThat(preview.skills.map { it.key }).describedAs("$key inherits the ground rules")
                .contains(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
            assertThat(preview.prompt.system).describedAs(key).contains("{{project.constitution}}")
        }
    }

    @Test
    fun `every agent inherits the ground rules from base-agent`() {
        service.seed()
        lib.templates.rows.values.filter { it.extendsKey != null }.forEach {
            assertThat(lib.lookup.resolver.preview(it.key, null)!!.skills.map { s -> s.key }).describedAs(it.key)
                .startsWith(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        }
    }

    @Test
    fun `write-user-story is an LLM requirement writer with confidence and the story skills`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.WRITE_USER_STORY, null)!!
        assertThat(task.executor).isEqualTo(TaskExecutor.LLM)
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.REQUIREMENT_WRITER)
        assertThat(task.lineage).containsExactly(BuiltInLibrary.WRITE_USER_STORY, BuiltInLibrary.BASE_LLM, BuiltInLibrary.BASE_AGENT)
        assertThat(task.skills.map { it.key }).contains(BuiltInLibrary.USER_STORY_FORMAT, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.DEFINITION_OF_READY_DONE)
        assertThat(task.confidenceField).isEqualTo("confidence")
        // the schema field is named like the produced artefact, so the story is stored as markdown, not as the whole JSON
        assertThat(BuiltInLibrary.schemaProperties(task.outputSchema)).contains("user-story", "openQuestions", "confidence")
        assertThat(task.produces).contains("user-story")
        assertThat(task.prompt.system).contains("US-<AREA>-<NN>")
        assertThat(task.outputSchema).contains("\"required\": [\"question\", \"whyItMatters\"]")
    }

    @Test
    fun `write-use-case produces the use-case field and write-adr leaves number, date and status to people`() {
        service.seed()
        val useCase = lib.lookup.resolver.resolve(BuiltInLibrary.WRITE_USE_CASE, null)!!
        assertThat(BuiltInLibrary.schemaProperties(useCase.outputSchema)).contains("use-case")
        assertThat(useCase.produces).contains("use-case")
        val adr = lib.lookup.resolver.resolve(BuiltInLibrary.WRITE_ADR, null)!!
        assertThat(adr.prompt.system).contains("`NNNN`").contains("`YYYY-MM-DD`").contains("Never mark the ADR Accepted")
        val dod = lib.skills.findByKeyAndScope(BuiltInLibrary.DEFINITION_OF_READY_DONE, Scope.Global)!!.content
        assertThat(dod).contains("an agent never sets them").contains("DoD (people only)")
    }

    @Test
    fun `impact-analysis covers the change request sections and reports confidence`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.IMPACT_ANALYSIS, null)!!
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.CHANGE_ANALYST)
        assertThat(task.outputSchema).contains("businessJustification", "artefacts", "components", "newStories", "specification", "estimate", "options", "recommendation", "confidence")
        assertThat(task.produces).containsExactly("impact-analysis", "confidence")
    }

    @Test
    fun `plan-task-list enforces the task format and the final reformat lint and test task`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.PLAN_TASK_LIST, null)!!
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.TASK_PLANNER)
        assertThat(task.produces).containsExactly("tasks")
        assertThat(task.outputSchema).contains("\"title\"", "\"description\"", "\"verification\"", "\"test_command\"", "\"completed\"")
            .contains("\"enum\": [false]").contains("\"finalTaskTitle\": { \"type\": \"string\", \"enum\": [\"reformat, lint, and test\"] }")
        assertThat(task.prompt.system).contains("reformat, lint, and test")
        // the approved impact analysis of a change request reaches the planner
        assertThat(task.prompt.user).contains("{{artifacts.impact-analysis}}").contains("{{artifacts.plan}}").contains("{{artifacts.user-story}}")
        // a revision starts from the previous task list, not from scratch
        assertThat(task.prompt.user).contains("{{#feedback}}\n{{#artifacts.tasks}}", "Your previous task list", "{{artifacts.tasks}}")
        assertThat(task.consumes).contains("impact-analysis")
    }

    @Test
    fun `implement-task-list extends the OpenCode implementer and keeps a work log`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.IMPLEMENT_TASK_LIST, null)!!
        assertThat(task.lineage).containsExactly(BuiltInLibrary.IMPLEMENT_TASK_LIST, BuiltInLibrary.IMPLEMENT_OPENCODE, BuiltInLibrary.BASE_CODING_AGENT, BuiltInLibrary.BASE_AGENT)
        assertThat(task.executor).isEqualTo(TaskExecutor.IMPLEMENT)
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.IMPLEMENTER)
        assertThat(task.produces).contains("diff", "mr", "work-log")
        assertThat(task.config["collectArtifacts"]).isEqualTo(listOf("work-log.md"))
        assertThat(task.config["agent"]).isEqualTo("OPENCODE")
        // a STOP fails the step: the verification command checks the RESULT line of the work log
        assertThat(task.config["verifyCommand"]).isEqualTo(BuiltInLibrary.WORK_LOG_VERIFY_COMMAND)
        assertThat(BuiltInLibrary.WORK_LOG_VERIFY_COMMAND).contains("work-log.md").contains(BuiltInLibrary.WORK_LOG_GREEN)
        assertThat(task.prompt.user).contains("test_command").contains("STOP").contains("Noticed out of scope").contains(BuiltInLibrary.WORK_LOG_GREEN)
            .contains("{{artifacts.review}}").contains("Do not commit").doesNotContain("Work through the task list in order when")
            .doesNotContain("commit with the task title")
        assertThat(task.skills.map { it.key }).doesNotContain(BuiltInLibrary.CONVENTIONAL_COMMITS, BuiltInLibrary.BUILD_GUARDRAILS)
    }

    @Test
    fun `review-against-spec is a reviewer whose schema the review policy understands`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.REVIEW_AGAINST_SPEC, null)!!
        assertThat(task.interfaces).containsExactly(BuiltInLibrary.REVIEWER)
        assertThat(task.produces).containsExactly("review")
        assertThat(task.outputSchema).contains("\"findings\"", "\"verdict\"", "\"score\"", "\"coverage\"", "MISSING")
        assertThat(task.config).doesNotContainKey("maxChunkLines")
        val user = task.prompt.user!!
        assertThat(user.indexOf("return only")).isLessThan(user.indexOf("{{artifacts.diff}}"))
    }

    @Test
    fun `skills have frontmatter and 15 to 40 lines of concrete rules`() {
        service.seed()
        BuiltInLibrary.sdlcSkillKeys.forEach { key ->
            val skill = lib.skills.findByKeyAndScope(key, Scope.Global)!!
            assertThat(skill.content).describedAs(key).startsWith("---\nname: $key\ndescription: ")
            assertThat(skill.content.lines().size).describedAs(key).isBetween(15, 45)
        }
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!.content).contains("[?]", "owner", "TODO", "TBD", "status")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.BUILD_GUARDRAILS, Scope.Global)!!.content).contains("GR-A", "GR-S", "GR-Q", "GR-D", "negative test")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.DEFINITION_OF_READY_DONE, Scope.Global)!!.content).contains("DoD by vibe", "recorded decision")
    }

    @Test
    fun `an untouched built-in base-agent from an older seed receives the ground rules, an edited one does not`() {
        val old = BuiltInLibrary.templates(lib.now).first { it.key == BuiltInLibrary.BASE_AGENT }.copy(skills = emptyList(), updatedBy = null)
        lib.templates.save(old)
        val review = BuiltInLibrary.templates(lib.now).first { it.key == BuiltInLibrary.CODE_REVIEW }
        lib.templates.save(review.copy(skills = emptyList(), version = 3))

        val result = service.seed()

        assertThat(result.templatesUpgraded).isEqualTo(1)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.BASE_AGENT, Scope.Global)!!.skills).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!.skills).describedAs("edited copy stays as the owner left it").isEmpty()
        assertThat(service.seed().total).isZero()
    }
}
