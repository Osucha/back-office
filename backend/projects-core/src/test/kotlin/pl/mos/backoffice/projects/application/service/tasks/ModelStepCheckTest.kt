package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.StepWorkspace
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelAvailability
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelOption
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRoute
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.ModelUse
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.usage.Pricing
import java.util.UUID

/** ADR 0050: each task step's model, resolved as a run resolves it, and whether the user can use it. */
class ModelStepCheckTest {

    private val gpt = ModelProfileId.new()
    private val reviewer = ModelProfileId.new()
    private val opusLogin = ModelProfileId.new()
    private val templateModel = ModelProfileId.new()

    private val catalog = object : TaskCatalog {
        override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? {
            val executor = when {
                key.startsWith("implement") -> TaskExecutor.IMPLEMENT
                key == "fetch-issue" -> TaskExecutor.FETCH_ISSUE
                else -> TaskExecutor.LLM
            }
            return ResolvedTask(
                key, key, executor, overrides.modelProfileId ?: templateModel.takeIf { key == "with-model" }, TaskPrompt(null, null), emptyList(),
                overrides.config, null, null, if (key == "code-review") listOf("review") else emptyList(), emptyList(), emptyList(), listOf(key),
                WorkflowNode.DEFAULT_TASK_PORTS,
            )
        }
        override fun palette(projectId: UUID?): List<TaskSummary> = emptyList()
    }

    /** A login model is usable only in CODING_AGENT steps with Claude Code; the template model's provider is not connected. */
    private val availability = object : ModelAvailability {
        val uses = mutableListOf<ModelUse>()
        override fun options(use: ModelUse): List<ModelOption> = emptyList()
        override fun check(profileId: ModelProfileId, use: ModelUse): ModelOption {
            uses += use
            val login = profileId == opusLogin
            val problem = when {
                login && (use.stepKind != StepKind.CODING_AGENT || use.harness != AgentHarness.CLAUDE_CODE) -> "coding steps with Claude Code only"
                profileId == templateModel -> "provider not connected"
                else -> null
            }
            return ModelOption(
                profileId, if (login) "Opus (my login)" else "Model", ModelRef(Provider.ANTHROPIC, "m"),
                if (login) ModelRuntime.CLAUDE_CODE_LOGIN else ModelRuntime.API, if (login) ModelRoute.OWN_LOGIN else ModelRoute.GATEWAY,
                personal = login, usable = problem == null, problem = problem, note = "runner offline".takeIf { login },
            )
        }
    }
    private val check = ModelStepCheck(catalog, availability)
    private val user = UUID.randomUUID()

    private fun node(id: String, key: String, model: ModelProfileId? = null, config: Map<String, Any?> = emptyMap()) =
        WorkflowNode.Task(id, id, task = TaskBinding(key, TaskOverrides(modelProfileId = model, config = config)))

    @Test
    fun `models come from the node, the template, the review default or the project default, steps without a model are left out`() {
        val project = Fixtures.project().copy(defaults = ProjectDefaults(modelProfileId = gpt, reviewModelProfileId = reviewer), personal = true)
        val nodes = listOf(
            node("fetch", "fetch-issue"),
            node("ask", "analyse-issue", model = gpt),
            node("tmpl", "with-model"),
            node("review", "code-review"),
            node("impl", "implement-claude-code", model = opusLogin, config = mapOf("agent" to "CLAUDE_CODE")),
            node("prep", "implement-opencode", config = mapOf(StepWorkspace.PREPARE_ONLY to true)),
        )

        val plan = check.plan(project, nodes, user).associateBy { it.nodeId }

        assertThat(plan.keys).containsExactly("ask", "tmpl", "review", "impl")
        assertThat(plan.mapValues { it.value.source }).containsExactlyInAnyOrderEntriesOf(
            mapOf("ask" to ModelStepCheck.Source.NODE, "tmpl" to ModelStepCheck.Source.TEMPLATE, "review" to ModelStepCheck.Source.PROJECT_REVIEW, "impl" to ModelStepCheck.Source.NODE),
        )
        assertThat(plan["review"]!!.profileId).isEqualTo(reviewer)
        assertThat(plan["impl"]!!.let { it.stepKind to it.harness }).isEqualTo(StepKind.CODING_AGENT to AgentHarness.CLAUDE_CODE)
        assertThat(plan["impl"]!!.problem).isNull()
        assertThat(plan["tmpl"]!!.problem).contains("provider not connected")
        assertThat(availability.uses).allSatisfy { assertThat(it.privateProject to it.userId).isEqualTo(true to user) }
    }

    @Test
    fun `an unusable or missing model blocks the start, a note is only a designer warning`() {
        val project = Fixtures.project().copy(personal = true, defaults = ProjectDefaults())
        val nodes = listOf(
            node("ask", "analyse-issue", model = opusLogin),
            node("none", "analyse-issue"),
            node("impl", "implement-claude-code", model = opusLogin, config = mapOf("agent" to "CLAUDE_CODE")),
        )

        val blockers = check.runBlockers(project, nodes, user)
        assertThat(blockers).hasSize(2)
        assertThat(blockers[0]).isEqualTo("step \"ask\" (ask): Opus (my login): coding steps with Claude Code only")
        assertThat(blockers[1]).contains("no model profile is set")

        val warnings = check.designIssues(project, nodes, user)
        assertThat(warnings.map { it.nodeId }).containsExactly("ask", "none", "impl")
        assertThat(warnings.last().message).isEqualTo("Opus (my login): runner offline")
        assertThat(warnings).allSatisfy { assertThat(it.field).isEqualTo(ModelStepCheck.FIELD) }
    }

    @Test
    fun `at run time an LLM step refuses a login model and a shared project refuses it in a coding step`() {
        val snapshot = ModelProfileSnapshot(opusLogin, "Opus (my login)", ModelRef(Provider.ANTHROPIC, "opus"), Pricing.FREE, true, ModelRuntime.CLAUDE_CODE_LOGIN)
        val resolver = ModelResolver(object : ModelCatalog {
            override fun find(id: ModelProfileId) = snapshot.takeIf { id == opusLogin }
            override fun findAllEnabled() = listOf(snapshot)
            override fun findPricing(ref: ModelRef): Pricing? = null
        })
        val llm = catalog.resolve("analyse-issue", null, TaskOverrides(modelProfileId = opusLogin))!!
        val coding = catalog.resolve("implement-claude-code", null, TaskOverrides(modelProfileId = opusLogin))!!

        assertThatThrownBy { resolver.forLlmCall(llm, Fixtures.project().copy(personal = true)) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("needs a model of a connected provider")
        assertThatThrownBy { resolver.forCodingAgent(coding, Fixtures.project()) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("only in private projects")
        assertThat(resolver.forCodingAgent(coding, Fixtures.project().copy(personal = true))).isEqualTo(snapshot)
    }

    @Test
    fun `after the project defaults the user's own default and then the platform default are used (ADR 0050 step 2)`() {
        val personal = ModelProfileId.new()
        val defaults = object : pl.mos.backoffice.shared.llm.ModelDefaults {
            override fun resolve(userId: UUID?, stepKind: StepKind) = when {
                userId == user && stepKind == StepKind.CODING_AGENT -> pl.mos.backoffice.shared.llm.DefaultModel(personal, personal = true)
                else -> pl.mos.backoffice.shared.llm.DefaultModel(gpt, personal = false)
            }
        }
        val withDefaults = ModelStepCheck(catalog, availability, defaults)
        val project = Fixtures.project().copy(personal = true, defaults = ProjectDefaults())
        val plan = withDefaults.plan(project, listOf(node("ask", "analyse-issue"), node("impl", "implement-claude-code", config = mapOf("agent" to "CLAUDE_CODE"))), user)
            .associateBy { it.nodeId }
        assertThat(plan["ask"]!!.let { it.source to it.profileId }).isEqualTo(ModelStepCheck.Source.PLATFORM_DEFAULT to gpt)
        assertThat(plan["impl"]!!.let { it.source to it.profileId }).isEqualTo(ModelStepCheck.Source.PERSONAL_DEFAULT to personal)

        val foreign = ModelProfileSnapshot(personal, "Their key", ModelRef(Provider.OPENAI, "gpt-5"), Pricing.FREE, true, ModelRuntime.API, ownerUserId = UUID.randomUUID())
        val resolver = ModelResolver(object : ModelCatalog {
            override fun find(id: ModelProfileId) = foreign.takeIf { id == personal }
            override fun findAllEnabled() = listOf(foreign)
            override fun findPricing(ref: ModelRef): Pricing? = null
        }, defaults)
        val llm = catalog.resolve("analyse-issue", null, TaskOverrides(modelProfileId = personal))!!
        assertThatThrownBy { resolver.forLlmCall(llm, project, user) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("another user")
    }

}
