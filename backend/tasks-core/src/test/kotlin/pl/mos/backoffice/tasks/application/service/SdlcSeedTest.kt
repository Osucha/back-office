package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.service.JsonSyntax
import pl.mos.backoffice.tasks.support.Library

/** The SDLC part of the seed (ADR 0019): keys other contexts rely on, categories, rules, schemas and inheritance. */
class SdlcSeedTest {

    private val lib = Library(builtInAgents = false)
    private val service = SeedTaskLibraryService(lib.templates, lib.skills, lib.rules, lib.agents, lib.clock)

    @Test
    fun `the keys the change-request workflow uses exist exactly`() {
        service.seed()
        listOf("impact-analysis", "write-user-story", "plan-task-list", "implement-task-list", "write-use-case", "write-adr", "review-against-spec").forEach {
            assertThat(lib.templates.findByKeyAndScope(it, Scope.Global)).describedAs(it).isNotNull()
        }
        listOf("agent-ground-rules", "ask-dont-guess").forEach {
            assertThat(lib.rules.findByKeyAndScope(it, Scope.Global)).describedAs(it).isNotNull()
        }
        listOf("definition-of-ready-done", "user-story-format", "use-case-format", "adr-format", "build-guardrails").forEach {
            assertThat(lib.skills.findByKeyAndScope(it, Scope.Global)).describedAs(it).isNotNull()
        }
    }

    @Test
    fun `seeding twice creates the SDLC entries once`() {
        val first = service.seed()
        val second = service.seed()
        assertThat(first.total).describedAs("templates, skills, rules and the five agents").isEqualTo(21 + 8 + 4 + 5)
        assertThat(second.total).isZero()
        assertThat(lib.templates.rows.values.map { it.key }.filter { it in BuiltInLibrary.sdlcTemplateKeys }).hasSize(BuiltInLibrary.sdlcTemplateKeys.size)
        assertThat(lib.skills.rows.values.map { it.key }.filter { it in BuiltInLibrary.sdlcSkillKeys }).hasSize(BuiltInLibrary.sdlcSkillKeys.size)
        assertThat(lib.templates.rows.values).allSatisfy { assertThat(it.updatedBy).isEqualTo(Actor.SYSTEM) }
    }

    @Test
    fun `every SDLC template resolves executable without check problems and valid schemas`() {
        service.seed()
        BuiltInLibrary.sdlcTemplateKeys.forEach { key ->
            val preview = lib.lookup.resolver.preview(key, null)!!
            assertThat(preview.check.problems).describedAs(key).isEmpty()
            assertThat(preview.executable).describedAs(key).isTrue()
            preview.outputSchema?.let { assertThat(JsonSyntax.problem(it)).describedAs("$key schema").isNull() }
            assertThat(preview.rules.map { it.key }).describedAs("$key gets the ground rules from its agent")
                .contains(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
            assertThat(preview.prompt.system.orEmpty()).describedAs("ADR 0057: the environment carries the constitution").doesNotContain("project.constitution")
        }
    }

    @Test
    fun `ADR 0057 - every concrete built-in gets the ground rules from its agent`() {
        service.seed()
        lib.templates.rows.values.filter { !it.abstract }.forEach {
            assertThat(lib.lookup.resolver.preview(it.key, null)!!.rules.map { r -> r.key }).describedAs(it.key)
                .startsWith(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        }
    }

    @Test
    fun `write-user-story is an LLM requirement writer with confidence and the story skills`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.WRITE_USER_STORY, null, TaskOverrides(agentKey = BuiltInLibrary.ANALYST))!!
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.WRITE_USER_STORY, null, TaskOverrides(agentKey = BuiltInLibrary.ANALYST))!!.check.warnings).isEmpty()
        assertThat(task.agent.key).isEqualTo(BuiltInLibrary.ANALYST)
        assertThat(task.agent.modelAnswer).isTrue()
        assertThat(task.category).isEqualTo(TemplateCategory.SPECIFICATION)
        assertThat(task.lineage).describedAs("flat built-ins, ADR 0058").containsExactly(BuiltInLibrary.WRITE_USER_STORY)
        assertThat(task.skills.map { it.key }).contains(BuiltInLibrary.USER_STORY_FORMAT, BuiltInLibrary.DEFINITION_OF_READY_DONE)
        assertThat(task.rules.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
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
        assertThat(task.category).isEqualTo(TemplateCategory.ANALYSIS)
        assertThat(task.outputSchema).contains("businessJustification", "artefacts", "components", "newStories", "specification", "estimate", "options", "recommendation", "confidence")
        assertThat(task.produces).containsExactly("impact-analysis", "confidence")
    }

    @Test
    fun `plan-task-list enforces the task format and the final reformat lint and test task`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.PLAN_TASK_LIST, null)!!
        assertThat(task.category).isEqualTo(TemplateCategory.PLANNING)
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
        assertThat(task.lineage).containsExactly(BuiltInLibrary.IMPLEMENT_TASK_LIST, BuiltInLibrary.IMPLEMENT_OPENCODE)
        assertThat(task.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(task.category).describedAs("inherited from implement-opencode").isEqualTo(TemplateCategory.IMPLEMENTATION)
        assertThat(task.produces).contains("diff", "mr", "work-log")
        assertThat(task.config["collectArtifacts"]).isEqualTo(listOf("work-log.md"))
        assertThat(task.config).doesNotContainKey("agent")
        // a STOP fails the step: the verification command checks the RESULT line of the work log
        assertThat(task.config["verifyCommand"]).isEqualTo(BuiltInLibrary.WORK_LOG_VERIFY_COMMAND)
        assertThat(BuiltInLibrary.WORK_LOG_VERIFY_COMMAND).contains("work-log.md").contains(BuiltInLibrary.WORK_LOG_GREEN)
        assertThat(task.prompt.user).contains("test_command").contains("STOP").contains("Noticed out of scope").contains(BuiltInLibrary.WORK_LOG_GREEN)
            .contains("{{artifacts.review}}").contains("Do not commit").doesNotContain("Work through the task list in order when")
            .doesNotContain("commit with the task title")
        assertThat(task.skills.map { it.key }).describedAs("the OpenCode agent's").doesNotContain(BuiltInLibrary.CONVENTIONAL_COMMITS)
            .contains(BuiltInLibrary.DEFINITION_OF_READY_DONE)
    }

    @Test
    fun `review-against-spec is a reviewer whose schema the review policy understands`() {
        service.seed()
        val task = lib.lookup.resolver.resolve(BuiltInLibrary.REVIEW_AGAINST_SPEC, null)!!
        assertThat(task.category).isEqualTo(TemplateCategory.REVIEW)
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
        assertThat(lib.rules.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!.content).contains("[?]", "owner", "TODO", "TBD", "status")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.BUILD_GUARDRAILS, Scope.Global)!!.content).contains("GR-A", "GR-S", "GR-Q", "GR-D", "negative test")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.DEFINITION_OF_READY_DONE, Scope.Global)!!.content).contains("DoD by vibe", "recorded decision")
    }

    @Test
    fun `ADR 0057 - an untouched built-in agent from an older seed receives its environment, an edited one does not`() {
        val analyst = BuiltInLibrary.agents(lib.now).first { it.key == BuiltInLibrary.ANALYST }
        lib.agents.save(analyst.copy(rules = emptyList(), skills = emptyList(), instructions = "", updatedBy = null))
        val reviewer = BuiltInLibrary.agents(lib.now).first { it.key == BuiltInLibrary.REVIEWER }
        val alice = pl.mos.backoffice.shared.security.Actor(pl.mos.backoffice.shared.security.PrincipalKind.USER, java.util.UUID.randomUUID(), "Alice")
        lib.agents.save(reviewer.copy(skills = emptyList(), updatedBy = alice))

        val result = service.seed()

        assertThat(result.agentsUpgraded).isEqualTo(1)
        val upgraded = lib.agents.findByKeyAndScope(BuiltInLibrary.ANALYST, Scope.Global)!!
        assertThat(upgraded.rules).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        assertThat(upgraded.skills).containsExactlyElementsOf(BuiltInLibrary.agentSkills.getValue(BuiltInLibrary.ANALYST))
        assertThat(upgraded.instructions).isEqualTo(BuiltInLibrary.ANALYST_INSTRUCTIONS)
        assertThat(lib.agents.findByKeyAndScope(BuiltInLibrary.REVIEWER, Scope.Global)!!.skills).describedAs("edited copy stays as the owner left it").isEmpty()
        assertThat(service.seed().total).isZero()
    }

}
