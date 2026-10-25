package pl.mos.backoffice.shared.tasks

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/**
 * How a task is executed. The Projects context has one executor implementation per value; the Task
 * Library context only stores the value. `LLM` is a single structured model call in-process, `SPEC_KIT`
 * and `IMPLEMENT` run in the agent runner, the rest are integrations.
 */
enum class TaskExecutor { LLM, SPEC_KIT, IMPLEMENT, FETCH_ISSUE, NOTIFY, TRANSITION_ISSUE, START_WORKFLOW, PROJECT_DIGEST }

/** A reusable prompt fragment / Agent Skill attached to a task (docs/architecture/07-task-library.md). */
data class SkillSnapshot(val key: String, val name: String, val content: String)

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
    val executor: TaskExecutor,
    val modelProfileId: ModelProfileId?,
    val prompt: TaskPrompt,
    val skills: List<SkillSnapshot>,
    val config: Map<String, Any?>,
    /** JSON schema for structured output of `LLM` tasks. */
    val outputSchema: String?,
    /** Name of a numeric 0..1 field in the structured output that becomes `state.confidence`. */
    val confidenceField: String?,
    /** Artefact / state names this task produces (interface outputs are validated against it). */
    val produces: List<String>,
    /** Artefact / state names this task expects to exist before it runs. */
    val consumes: List<String>,
    /** Keys of the interfaces this task (or an ancestor) implements. */
    val interfaces: List<String>,
    /** Keys from the concrete template up to the root ancestor. */
    val lineage: List<String>,
    /** Output ports the node exposes in the workflow designer, e.g. `success`, `failure`. */
    val ports: List<String>,
    /** Version of the concrete (leaf) template, recorded in provenance attestations. */
    val version: Int? = null,
    /** Run start form fields this task asks for, merged over the lineage (ADR 0053). */
    val runInputs: List<RunInputField> = emptyList(),
)

data class TaskSummary(
    val key: String,
    val name: String,
    val description: String,
    val executor: TaskExecutor,
    val interfaces: List<String>,
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
