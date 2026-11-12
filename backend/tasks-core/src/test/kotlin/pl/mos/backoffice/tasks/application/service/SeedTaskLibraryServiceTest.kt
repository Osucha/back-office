package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

class SeedTaskLibraryServiceTest {

    private val lib = Library(builtInAgents = false)
    private val service = SeedTaskLibraryService(lib.templates, lib.skills, lib.rules, lib.agents, lib.clock)
    private val opencode = AgentSnapshot(AgentSnapshot.OPENCODE, "OpenCode", AgentHarness.OPENCODE)
    private val alice = Actor(PrincipalKind.USER, UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "Alice")

    @Test
    fun `seeds the documented library once and never twice`() {
        val first = service.seed()
        assertThat(first.rulesCreated).isEqualTo(4)
        assertThat(first.templatesCreated).isEqualTo(21)
        assertThat(first.skillsCreated).isEqualTo(8)
        assertThat(first.agentsCreated).isEqualTo(5)

        val second = service.seed()
        assertThat(second.total).isZero()
        assertThat(lib.templates.rows).hasSize(21)
        assertThat(lib.agents.rows.values.associate { it.key to it.harness }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                AgentSnapshot.MODEL_ANSWER to null, BuiltInLibrary.ANALYST to null, BuiltInLibrary.REVIEWER to null,
                AgentSnapshot.OPENCODE to AgentHarness.OPENCODE, AgentSnapshot.CLAUDE_CODE to AgentHarness.CLAUDE_CODE,
            ),
        )
        // ADR 0057 section 6: every built-in agent has the ground rules and the skills of its role
        assertThat(lib.agents.rows.values).allSatisfy {
            assertThat(it.rules).describedAs(it.key).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
            assertThat(it.skills).describedAs(it.key).containsExactlyElementsOf(BuiltInLibrary.agentSkills.getValue(it.key))
            it.skills.forEach { k -> assertThat(lib.skills.findByKeyAndScope(k, Scope.Global)).describedAs("${it.key} skill $k").isNotNull() }
        }
        assertThat(lib.agents.findByKeyAndScope(BuiltInLibrary.ANALYST, Scope.Global)!!.instructions).contains("analyst")
        assertThat(lib.agents.findByKeyAndScope(BuiltInLibrary.REVIEWER, Scope.Global)!!.instructions).contains("reviewer")
        assertThat(lib.agents.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.agentKeys)
        assertThat(lib.agents.rows.values).allSatisfy { assertThat(it.builtIn).isTrue(); assertThat(it.updatedBy).isEqualTo(Actor.SYSTEM) }
        assertThat(lib.rules.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.ruleKeys)
        assertThat(lib.templates.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.templateKeys)
        assertThat(lib.skills.rows.values.map { it.key }).containsExactlyInAnyOrderElementsOf(BuiltInLibrary.skillKeys)
        assertThat(lib.templates.rows.values).allSatisfy { assertThat(it.builtIn).isTrue(); assertThat(it.scope).isEqualTo(Scope.Global) }
        assertThat(lib.rules.rows.values).allSatisfy {
            assertThat(it.builtIn).isTrue(); assertThat(it.automatic).isFalse(); assertThat(it.updatedBy).isEqualTo(Actor.SYSTEM)
            assertThat(it.content).describedAs(it.key).doesNotStartWith("---")
        }
        assertThat(BuiltInLibrary.skillKeys).doesNotContainAnyElementsOf(BuiltInLibrary.ruleKeys)
    }

    @Test
    fun `entries the owner already has are left untouched, missing ones are restored`() {
        lib.template(BuiltInLibrary.CODE_REVIEW, name = "My review")
        lib.skill(BuiltInLibrary.SPEC_WRITING, content = "# mine")
        lib.template(BuiltInLibrary.CODE_REVIEW, scope = Scope.Project(lib.projectId), name = "Project review")

        val result = service.seed()

        assertThat(result.templatesCreated).isEqualTo(20)
        assertThat(result.skillsCreated).isEqualTo(7)
        assertThat(result.rulesCreated).isEqualTo(4)
        assertThat(result.templatesUpgraded + result.skillsUpgraded + result.rulesUpgraded).describedAs("entries that are not built-in are never upgraded").isZero()
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!.name).isEqualTo("My review")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.SPEC_WRITING, Scope.Global)!!.content).isEqualTo("# mine")
    }

    @Test
    fun `untouched built-ins of an older seed are upgraded completely, edited ones are left alone`() {
        service.seed()
        val review = lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!
        val oldReview = review.copy(prompt = Prompt(system = "old system", user = "old user"), config = mapOf("maxChunkLines" to 1500), requiresSkills = emptyList())
        lib.templates.save(oldReview)
        val analyse = lib.templates.findByKeyAndScope(BuiltInLibrary.ANALYSE_ISSUE, Scope.Global)!!
        lib.templates.save(analyse.copy(prompt = Prompt(user = "owner's prompt"), version = 2, updatedBy = alice))
        val skill = lib.skills.findByKeyAndScope(BuiltInLibrary.USER_STORY_FORMAT, Scope.Global)!!
        lib.skills.save(skill.copy(content = "---\nname: user-story-format\ndescription: old\n---\n# old", description = "old"))
        val editedSkill = lib.skills.findByKeyAndScope(BuiltInLibrary.ADR_FORMAT, Scope.Global)!!
        lib.skills.save(editedSkill.copy(content = "# my ADR rules", updatedBy = alice))
        val rule = lib.rules.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!
        lib.rules.save(rule.copy(content = "# old", description = "old description", automatic = true, updatedBy = null))
        val editedRule = lib.rules.findByKeyAndScope(BuiltInLibrary.KOTLIN_HEXAGONAL, Scope.Global)!!
        lib.rules.save(editedRule.copy(content = "# our layers", updatedBy = alice))

        val result = service.seed()

        assertThat(result.templatesUpgraded).isEqualTo(1)
        assertThat(result.skillsUpgraded).isEqualTo(1)
        assertThat(result.rulesUpgraded).isEqualTo(1)
        val upgraded = lib.templates.findByKeyAndScope(BuiltInLibrary.CODE_REVIEW, Scope.Global)!!
        assertThat(upgraded.id).isEqualTo(review.id)
        assertThat(upgraded.version).isEqualTo(1)
        assertThat(upgraded.prompt.user).isEqualTo(BuiltInLibrary.REVIEW_USER)
        assertThat(upgraded.prompt.system).startsWith(BuiltInLibrary.REVIEW_SYSTEM).contains("{{runInputText}}")
        assertThat(upgraded.config).describedAs("a removed config key goes away").doesNotContainKey("maxChunkLines")
        assertThat(upgraded.requiresSkills).containsExactly(BuiltInLibrary.SECURITY_REVIEW_CHECKLIST)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.ANALYSE_ISSUE, Scope.Global)!!.prompt.user).isEqualTo("owner's prompt")
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.USER_STORY_FORMAT, Scope.Global)!!.content).isEqualTo(BuiltInLibrary.USER_STORY_FORMAT_MD)
        assertThat(lib.skills.findByKeyAndScope(BuiltInLibrary.ADR_FORMAT, Scope.Global)!!.content).isEqualTo("# my ADR rules")
        val upgradedRule = lib.rules.findByKeyAndScope(BuiltInLibrary.ASK_DONT_GUESS, Scope.Global)!!
        assertThat(upgradedRule.id).isEqualTo(rule.id)
        assertThat(upgradedRule.content).isEqualTo(BuiltInLibrary.withoutFrontmatter(BuiltInLibrary.ASK_DONT_GUESS_MD))
        assertThat(upgradedRule.automatic).isFalse()
        assertThat(upgradedRule.description).isNotEqualTo("old description")
        assertThat(upgradedRule.updatedBy).isEqualTo(Actor.SYSTEM)
        assertThat(lib.rules.findByKeyAndScope(BuiltInLibrary.KOTLIN_HEXAGONAL, Scope.Global)!!.content).describedAs("an edited rule is never touched").isEqualTo("# our layers")
        assertThat(service.seed().total).describedAs("an upgraded library is stable").isZero()
    }

    @Test
    fun `untouched built-in agents are upgraded, edited ones and project copies are left alone`() {
        service.seed()
        val answer = lib.agents.findByKeyAndScope(AgentSnapshot.MODEL_ANSWER, Scope.Global)!!
        lib.agents.save(answer.copy(description = "old", updatedBy = null))
        val claude = lib.agents.findByKeyAndScope(AgentSnapshot.CLAUDE_CODE, Scope.Global)!!
        lib.agents.save(claude.copy(name = "Our Claude", updatedBy = alice))

        val result = service.seed()

        assertThat(result.agentsUpgraded).isEqualTo(1)
        assertThat(lib.agents.findByKeyAndScope(AgentSnapshot.MODEL_ANSWER, Scope.Global)!!.description).isNotEqualTo("old")
        assertThat(lib.agents.findByKeyAndScope(AgentSnapshot.MODEL_ANSWER, Scope.Global)!!.id).isEqualTo(answer.id)
        assertThat(lib.agents.findByKeyAndScope(AgentSnapshot.CLAUDE_CODE, Scope.Global)!!.name).isEqualTo("Our Claude")
        assertThat(service.seed().total).isZero()
    }

    @Test
    fun `the former built-in action templates are removed unless another template extends them`() {
        BuiltInLibrary.formerActionTemplateKeys.forEach { lib.templates.save(lib.template(it).copy(builtIn = true)) }
        lib.template(BuiltInLibrary.NOTIFY_JIRA, scope = Scope.Project(lib.projectId))
        lib.template("our-notify", extendsKey = BuiltInLibrary.TRANSITION_ISSUE)

        val result = service.seed()

        assertThat(result.templatesRemoved).isEqualTo(4)
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.FETCH_ISSUE, Scope.Global)).isNull()
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.TRANSITION_ISSUE, Scope.Global)).describedAs("still extended").isNotNull()
        assertThat(lib.templates.findByKeyAndScope(BuiltInLibrary.NOTIFY_JIRA, Scope.Project(lib.projectId))).describedAs("a project copy stays").isNotNull()
        assertThat(service.seed().templatesRemoved).isZero()
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
            assertThat(preview.check.problems).describedAs(template.key).isEmpty()
            if (!template.abstract) assertThat(preview.executable).describedAs(template.key).isTrue()
        }
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_AGENT, null)!!.abstract).isTrue()
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_LLM, null)!!.abstract).isTrue()
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_CODING_AGENT, null)!!.abstract).isTrue()
    }

    @Test
    fun `every produced name of every built-in template is backed by its schema, its collected files or its agent`() {
        service.seed()

        val problems = lib.templates.rows.values.filterNot { it.abstract }.flatMap { template ->
            val task = lib.lookup.resolver.resolve(template.key, null)!!
            BuiltInLibrary.producedNameProblems(task.key, task.agent, task.produces, task.outputSchema, task.confidenceField, task.config)
        }

        assertThat(problems).isEmpty()
    }

    @Test
    fun `the produces check catches a schema field that does not match the produced name`() {
        val schema = """{ "type": "object", "properties": { "userStory": { "type": "string", "properties": { "x": {} } }, "confidence": { "type": "number" } } }"""
        assertThat(BuiltInLibrary.schemaProperties(schema)).containsExactly("userStory", "confidence")
        val mismatch = BuiltInLibrary.producedNameProblems("write-user-story", AgentSnapshot.DEFAULT, listOf("user-story", "confidence"), schema, "confidence", emptyMap())
        assertThat(mismatch).hasSize(1)
        assertThat(mismatch.single()).contains("\"user-story\"")
        assertThat(BuiltInLibrary.producedNameProblems("implement-task-list", opencode, listOf("diff", "work-log"), null, null, mapOf("collectArtifacts" to listOf("work-log.md")))).isEmpty()
        assertThat(BuiltInLibrary.producedNameProblems("x", opencode, listOf("work-log"), null, null, emptyMap())).hasSize(1)
        assertThat(BuiltInLibrary.producedNameProblems("x", opencode, listOf("plan"), null, null, mapOf("phase" to "PLAN"))).describedAs("a Spec Kit job").isEmpty()
        assertThat(BuiltInLibrary.producedNameProblems("x", opencode, listOf("plan"), null, null, emptyMap())).describedAs("an agent job").hasSize(1)
        assertThat(BuiltInLibrary.producedNameProblems("x", null, listOf("plan"), null, null, emptyMap())).isEmpty()
        assertThat(BuiltInLibrary.producedNameProblems("x", AgentSnapshot.DEFAULT, listOf("a", "b"), null, null, emptyMap())).describedAs("only one name can take the whole answer").hasSize(2)
        val answerSchema = """{ "type": "object", "properties": { "report": { "type": "string" }, "confidence": { "type": "number" } } }"""
        assertThat(BuiltInLibrary.producedNameProblems("x", opencode, listOf("report", "confidence"), answerSchema, "confidence", emptyMap()))
            .describedAs("ADR 0057: a coding agent with an output schema is backed by its fields").isEmpty()
    }

    @Test
    fun `implement-claude-code inherits the OpenCode implementer and the step names Claude Code`() {
        service.seed()

        val task = lib.lookup.resolver.resolve(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, null, TaskOverrides(agentKey = AgentSnapshot.CLAUDE_CODE))!!
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, null)!!.agent.key).describedAs("ADR 0061: no step agent, coding work").isEqualTo(AgentSnapshot.OPENCODE)

        assertThat(task.lineage).containsExactly(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_OPENCODE)
        assertThat(task.agent.key).isEqualTo(AgentSnapshot.CLAUDE_CODE)
        assertThat(task.agent.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(task.config).doesNotContainKey("agent")
        assertThat(task.config["push"]).isEqualTo(true)
        assertThat(task.config["branchTemplate"]).isEqualTo("feature/{{issue.key | lower}}")
        // the keys RunnerTaskSupport reads (a nested mergeRequest map was never read)
        assertThat(task.config["mergeRequest"]).isEqualTo(true)
        assertThat(task.config["mergeRequestTitleTemplate"]).isEqualTo("{{issue.key}}: {{issue.summary}}")
        assertThat(task.config["mergeRequestDraft"]).isEqualTo(false)
        assertThat(task.category).isEqualTo(TemplateCategory.IMPLEMENTATION)
        assertThat(task.produces).containsExactly("diff", "mr")
        assertThat(task.skills.map { it.key }).containsExactlyElementsOf(BuiltInLibrary.agentSkills.getValue(AgentSnapshot.CLAUDE_CODE))
        assertThat(task.rules.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        assertThat(task.prompt.system).describedAs("the preamble is the environment's now").doesNotContain("project.constitution").doesNotContain("Honesty")
            .contains("{{runInputText}}")
        assertThat(task.prompt.user).contains("{{issue.key}}").contains("{{artifacts.review}}").contains("verifyCommand").contains("Do not commit or push")
            .contains("Follow your rules.")
            .doesNotContain("conventional commits")
    }

    @Test
    fun `LLM templates carry schemas, confidence and the review category`() {
        service.seed()

        val clarify = lib.lookup.resolver.resolve(BuiltInLibrary.CLARIFY_SPEC, null, TaskOverrides(agentKey = BuiltInLibrary.ANALYST))!!
        assertThat(clarify.agent.modelAnswer).isTrue()
        assertThat(clarify.confidenceField).isEqualTo("confidence")
        assertThat(BuiltInLibrary.schemaProperties(clarify.outputSchema)).containsExactlyInAnyOrder("confidence", "clarifications", "summary")
        assertThat(clarify.prompt.user).contains("why it matters").contains("suggested default")
        assertThat(clarify.produces).containsExactly("confidence", "clarifications")
        assertThat(clarify.lineage).containsExactly(BuiltInLibrary.CLARIFY_SPEC)
        assertThat(clarify.agent.key).isEqualTo(BuiltInLibrary.ANALYST)
        assertThat(clarify.prompt.system).contains("implementer").doesNotContain("no repository")

        val review = lib.lookup.resolver.resolve(BuiltInLibrary.CODE_REVIEW, null, TaskOverrides(agentKey = BuiltInLibrary.REVIEWER))!!
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.CODE_REVIEW, null, TaskOverrides(agentKey = BuiltInLibrary.REVIEWER))!!.check.warnings).describedAs("the reviewer has the required skill").isEmpty()
        assertThat(review.consumes).containsExactly("diff")
        assertThat(review.produces).containsExactly("review")
        assertThat(review.outputSchema).contains("BLOCKER").contains("\"verdict\"").contains("\"score\"")
        assertThat(review.agent.key).isEqualTo(BuiltInLibrary.REVIEWER)
        assertThat(review.skills.map { it.key }).containsExactly(BuiltInLibrary.SECURITY_REVIEW_CHECKLIST, BuiltInLibrary.DEFINITION_OF_READY_DONE)
        assertThat(review.rules.map { it.key }).containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS)
        assertThat(review.category).isEqualTo(TemplateCategory.REVIEW)
        assertThat(review.prompt.system).contains("MINOR").contains("NIT")
        assertThat(review.config).doesNotContainKey("maxChunkLines")
        val user = review.prompt.user!!
        assertThat(user.indexOf("return only the JSON")).describedAs("instructions come before the data").isLessThan(user.indexOf("{{artifacts.diff}}"))
        assertThat(user).contains("{{input.ci.mergeRequest.title}}").contains("{{#issue.key}}")
        assertThat(user.trimEnd()).endsWith("{{artifacts.diff}}")

        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!.config["phase"]).isEqualTo("PLAN")
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.AGENT_TASK, null)!!.agent.key).describedAs("ADR 0061: no push, so any agent; the step names one").isEqualTo(AgentSnapshot.MODEL_ANSWER)
        assertThat(lib.lookup.resolver.resolve(BuiltInLibrary.PREPARE_WORKSPACE, null)!!.agent.key).isEqualTo(AgentSnapshot.OPENCODE)
        val llmTask = lib.lookup.resolver.resolve(BuiltInLibrary.LLM_TASK, null)!!
        assertThat(llmTask.agent.key).isEqualTo(AgentSnapshot.MODEL_ANSWER)
        // ADR 0058: the task is the parameter task, its default the former placeholder
        assertThat(llmTask.prompt.user).isEqualTo("{{params.task}}")
        assertThat(llmTask.parameterValues).isEqualTo(mapOf("task" to BuiltInLibrary.LLM_TASK_USER))
        val agentTask = lib.lookup.resolver.resolve(BuiltInLibrary.AGENT_TASK, null, pl.mos.backoffice.shared.tasks.TaskOverrides(parameters = mapOf("task" to "Fix it")))!!
        assertThat(agentTask.parameterValues).isEqualTo(mapOf("task" to "Fix it"))
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.AGENT_TASK, null)!!.needsParameters).describedAs("a ready block").isFalse()
    }

    @Test
    fun `Spec Kit prompts start with the subject and the issue and do not paste artefacts Spec Kit reads from disk`() {
        service.seed()

        val specify = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_SPECIFY, null)!!.prompt.user!!
        assertThat(specify.lines().first()).isEqualTo("{{issue.key}}: {{issue.summary}}")
        assertThat(specify).contains("{{issue.acceptanceCriteria}}").contains("Apply the skill ${BuiltInLibrary.SPEC_WRITING}")
        assertThat(specify.indexOf("{{issue.description}}")).isLessThan(specify.indexOf("Phase SPECIFY"))
        assertThat(specify).doesNotContain("You are an engineering agent")

        val resolvedPlan = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_PLAN, null)!!
        val plan = resolvedPlan.prompt.user!!
        val tasks = lib.lookup.resolver.resolve(BuiltInLibrary.SPEC_KIT_TASKS, null)!!.prompt.user!!
        assertThat(plan).doesNotContain("{{artifacts.spec}}").contains("{{feedback}}")
        // ADR 0053: the technical hints are a declared run input, rendered in the inherited "Run input" section
        assertThat(resolvedPlan.runInputs.map { it.name }).contains("techHints")
        assertThat(resolvedPlan.prompt.system).contains("{{runInputText}}")
        assertThat(tasks).doesNotContain("{{artifacts.plan}}").contains("{{feedback}}")
    }

    @Test
    fun `runner templates name their skills, skills say when to use them and agent-task guards an empty issue`() {
        service.seed()

        listOf(BuiltInLibrary.SPEC_KIT_SPECIFY, BuiltInLibrary.SPEC_KIT_PLAN, BuiltInLibrary.SPEC_KIT_TASKS, BuiltInLibrary.IMPLEMENT_OPENCODE,
            BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_TASK_LIST, BuiltInLibrary.AGENT_TASK).forEach { key ->
            val task = lib.lookup.resolver.resolve(key, null)!!
            val prompt = (task.prompt.system ?: "") + "\n" + (task.prompt.user ?: "")
            listOf(BuiltInLibrary.SPEC_WRITING, BuiltInLibrary.DEFINITION_OF_READY_DONE).filter { prompt.contains("skill $it") }
                .forEach { k -> assertThat(task.skills.map { it.key }).describedAs("$key applies $k, which its agent has").contains(k) }
        }
        lib.skills.rows.values.forEach { skill ->
            // agents choose skills by their description; ": " would break the SKILL.md frontmatter
            assertThat(skill.description).describedAs(skill.key).contains("Use when").doesNotContain(": ")
        }
        listOf(BuiltInLibrary.KOTLIN_HEXAGONAL, BuiltInLibrary.ANGULAR_NG_ZORRO).forEach {
            assertThat(lib.rules.findByKeyAndScope(it, Scope.Global)!!.description).describedAs(it).startsWith("Example for the Back Office stack")
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

    @Test
    fun `withoutFrontmatter strips a leading SKILL md block and leaves other text alone`() {
        assertThat(BuiltInLibrary.withoutFrontmatter("---\nname: x\ndescription: y\n---\n\n# Body\ntext")).isEqualTo("# Body\ntext")
        assertThat(BuiltInLibrary.withoutFrontmatter("  \n---\nname: x\n---\nbody")).isEqualTo("body")
        assertThat(BuiltInLibrary.withoutFrontmatter("# No frontmatter\n---\n")).isEqualTo("# No frontmatter\n---\n")
        assertThat(BuiltInLibrary.withoutFrontmatter("---\nunterminated")).isEqualTo("---\nunterminated")
        BuiltInLibrary.rules(lib.now).forEach { assertThat(it.content).describedAs(it.key).doesNotContain("description: ").isNotBlank() }
    }

    @Test
    fun `every concrete built-in template has a category in its resolved lineage`() {
        service.seed()
        lib.templates.rows.values.filterNot { it.abstract }.forEach { template ->
            assertThat(lib.lookup.resolver.preview(template.key, null)!!.category).describedAs(template.key).isNotNull()
        }
        assertThat(lib.lookup.resolver.preview(BuiltInLibrary.BASE_AGENT, null)!!.category).isNull()
    }

    @Test
    fun `the rule checksum covers every editable field`() {
        val rule = BuiltInLibrary.rules(lib.now).first()
        assertThat(BuiltInChecksum.of(rule)).isEqualTo(BuiltInChecksum.of(rule.copy(updatedBy = alice, updatedAt = lib.now.plusSeconds(5))))
        listOf(
            rule.copy(name = "x"), rule.copy(description = "x"), rule.copy(content = "x"), rule.copy(automatic = true), rule.copy(tags = listOf("x")),
        ).forEach { assertThat(BuiltInChecksum.of(it)).isNotEqualTo(BuiltInChecksum.of(rule)) }
        val template = BuiltInLibrary.templates(lib.now).first { it.key == BuiltInLibrary.CODE_REVIEW }
        listOf(
            template.copy(category = null),
            template.copy(parameters = listOf(pl.mos.backoffice.shared.tasks.TemplateParameter("x", "X"))),
            template.copy(parameterValues = mapOf("x" to 1)),
        ).forEach { assertThat(BuiltInChecksum.of(it)).isNotEqualTo(BuiltInChecksum.of(template)) }
        val agent = BuiltInLibrary.agents(lib.now).first { it.key == BuiltInLibrary.ANALYST }
        listOf(
            agent.copy(instructions = "x"), agent.copy(rules = listOf("x")), agent.copy(skills = listOf("x")),
            agent.copy(mcpServers = listOf(pl.mos.backoffice.shared.runner.AgentMcpServer("x", "https://x.example"))),
        ).forEach { assertThat(BuiltInChecksum.of(it)).isNotEqualTo(BuiltInChecksum.of(agent)) }
    }
}
