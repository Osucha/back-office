package pl.mos.backoffice.shared.tasks

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import java.util.UUID

/**
 * ADR 0056: who runs a task. Without a [harness] the step is a single model answer in-process (no tools, no repository);
 * with one it is a coding agent session in the runner sandbox. Without a [modelProfileId] the step uses the defaults for
 * its kind. The Projects context decides how to run a step from the agent and the task's config.
 */
data class AgentSnapshot(
    val key: String,
    val name: String,
    val harness: AgentHarness? = null,
    val modelProfileId: ModelProfileId? = null,
) {
    val modelAnswer: Boolean get() = harness == null

    companion object {
        const val MODEL_ANSWER = "model-answer"
        const val OPENCODE = "opencode"
        const val CLAUDE_CODE = "claude-code"

        /** Used when no template in the lineage and no step names an agent and the library has none under [MODEL_ANSWER]. */
        val DEFAULT = AgentSnapshot(MODEL_ANSWER, "Model answer")

        /** Built-in agent key of a harness (the former config key `agent`). */
        fun keyOf(harness: AgentHarness?): String = when (harness) {
            null -> MODEL_ANSWER
            AgentHarness.OPENCODE -> OPENCODE
            AgentHarness.CLAUDE_CODE -> CLAUDE_CODE
        }
    }
}

/** A reusable prompt fragment / Agent Skill attached to a task (docs/architecture/07-task-library.md). */
data class SkillSnapshot(val key: String, val name: String, val content: String)

/**
 * ADR 0055: a rule the task always gets in its context (a skill is loaded on demand). [automatic] rules apply to every
 * step of their scope without being listed on a template.
 */
data class RuleSnapshot(val key: String, val name: String, val content: String, val automatic: Boolean = false)

/** ADR 0055: what kind of work a template does; groups the library and the designer palette. Inherited, child wins. */
enum class TemplateCategory { ANALYSIS, SPECIFICATION, PLANNING, IMPLEMENTATION, REVIEW, JIRA, OPERATIONS, GENERAL }

data class TaskPrompt(val system: String?, val user: String?)

/** How the run start form shows a [RunInputField]; `ISSUE_KEY` values become the run's issue key (ADR 0053). */
enum class RunInputType { TEXT, MULTILINE, ISSUE_KEY }

/**
 * A field of the run start form declared by a library task (ADR 0053). [name] is the key of the value in the run
 * parameters; a workflow's form is the union of the fields of its task steps.
 */
data class RunInputField(
    val name: String,
    val label: String,
    val type: RunInputType = RunInputType.TEXT,
    val required: Boolean = false,
    val help: String? = null,
) {
    init {
        if (name.isBlank()) throw ValidationException("run input name must not be blank", "runInputs.name")
        // the name is a run-parameter key and appears in error paths (`parameters.<name>`)
        if (!NAME_PATTERN.matches(name)) throw ValidationException("run input name must match ${NAME_PATTERN.pattern}", "runInputs.name")
        if (label.isBlank()) throw ValidationException("run input $name: label must not be blank", "runInputs.label")
        if (label.length > MAX_LABEL_LENGTH) throw ValidationException("run input $name: label is longer than $MAX_LABEL_LENGTH characters", "runInputs.label")
        if ((help?.length ?: 0) > MAX_HELP_LENGTH) throw ValidationException("run input $name: help is longer than $MAX_HELP_LENGTH characters", "runInputs.help")
    }

    companion object {
        /** Longest value accepted for one field on a checked start; bounds prompt size and cost. */
        const val MAX_LENGTH = 20_000
        const val MAX_LABEL_LENGTH = 200
        const val MAX_HELP_LENGTH = 1_000
        val NAME_PATTERN: Regex = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    }
}

/**
 * Node-level customisation of a task template inside a workflow. Applied last, after the template's
 * inheritance chain has been merged.
 */
data class TaskOverrides(
    /** ADR 0056: the agent that runs this step instead of the template's. */
    val agentKey: String? = null,
    val modelProfileId: ModelProfileId? = null,
    val systemPromptAppend: String? = null,
    val promptAppend: String? = null,
    val promptReplace: String? = null,
    val addSkills: List<String> = emptyList(),
    val removeSkills: List<String> = emptyList(),
    val config: Map<String, Any?> = emptyMap(),
) {
    companion object {
        val NONE = TaskOverrides()
    }
}

/**
 * Fully merged view of a task: template + all ancestors (`extends`) + project-scoped shadowing +
 * node overrides. This is what executors consume; they never see templates directly.
 */
data class ResolvedTask(
    val key: String,
    val name: String,
    /** ADR 0056: the step's agent, else the nearest in the lineage, else the built-in model answer. */
    val agent: AgentSnapshot,
    /** ADR 0056: the step's model, else the agent's, else the template lineage's; null = the defaults (ModelResolver). */
    val modelProfileId: ModelProfileId?,
    val prompt: TaskPrompt,
    val skills: List<SkillSnapshot>,
    val config: Map<String, Any?>,
    /** JSON schema of a structured answer (model answer agents). */
    val outputSchema: String?,
    /** Name of a numeric 0..1 field in the structured output that becomes `state.confidence`. */
    val confidenceField: String?,
    /** Artefact / state names this task produces. */
    val produces: List<String>,
    /** Artefact / state names this task expects to exist before it runs. */
    val consumes: List<String>,
    /** ADR 0055: the nearest category in the lineage; null when none sets one. */
    val category: TemplateCategory? = null,
    /** Keys from the concrete template up to the root ancestor. */
    val lineage: List<String>,
    /** Output ports the node exposes in the workflow designer, e.g. `success`, `failure`. */
    val ports: List<String>,
    /** Version of the concrete (leaf) template, recorded in provenance attestations. */
    val version: Int? = null,
    /** Run start form fields this task asks for, merged over the lineage (ADR 0053). */
    val runInputs: List<RunInputField> = emptyList(),
    /** ADR 0055: rules always in the task's context; automatic ones (global, then project) first. */
    val rules: List<RuleSnapshot> = emptyList(),
)

data class TaskSummary(
    val key: String,
    val name: String,
    val description: String,
    val agent: AgentSnapshot,
    val category: TemplateCategory?,
    val abstract: Boolean,
    val projectId: UUID?,
    val ports: List<String>,
    /** ADR 0054: kept for the steps that use it, not offered for new ones. */
    val deprecated: Boolean = false,
)

/** Outbound port implemented by the Task Library context; used by the Projects context. */
interface TaskCatalog {
    /**
     * Resolves a template key for a project (project-scoped templates shadow global ones) and applies
     * overrides. Returns null when the key is unknown. Throws `ValidationException` on cycles or when
     * the resolved task is abstract.
     */
    fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE): ResolvedTask?

    /** Concrete (non-abstract) templates visible to a project, for the designer palette. */
    fun palette(projectId: UUID?): List<TaskSummary>
}
