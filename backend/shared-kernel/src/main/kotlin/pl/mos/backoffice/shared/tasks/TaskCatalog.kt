package pl.mos.backoffice.shared.tasks

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.runner.AgentSession
import java.util.UUID

/**
 * ADR 0056/0057: who works on a task and how. Without a [harness] the step is a single model answer in-process (no tools,
 * no repository); with one it is a coding agent session in the runner sandbox. Without a [modelProfileId] the step uses
 * the defaults for its kind. [instructions] (markdown, may use `{{project.*}}`), the rules and skills (resolved into the
 * task; [rules] and [skills] are the agent's own keys, for display) and [mcpServers] are the agent's environment. The
 * Projects context decides how to run a step from the agent and the task's config.
 */
data class AgentSnapshot(
    val key: String,
    val name: String,
    val harness: AgentHarness? = null,
    val modelProfileId: ModelProfileId? = null,
    val instructions: String = "",
    val rules: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val mcpServers: List<AgentMcpServer> = emptyList(),
    /** ADR 0060: what the agent may change (coding agents; irrelevant for a model answer); ADR 0062: and its directory. */
    val access: AgentAccess = AgentAccess.FULL,
    /** ADR 0062: which executions of the agent continue one conversation (coding agents). */
    val session: AgentSession = AgentSession.PER_STEP,
) {
    val modelAnswer: Boolean get() = harness == null

    companion object {
        const val MODEL_ANSWER = "model-answer"
        const val OPENCODE = "opencode"
        const val CLAUDE_CODE = "claude-code"

        /** Used when the step names no agent and its work needs none (ADR 0061) and the library has none under [MODEL_ANSWER]. */
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

/** How the run start form shows a [RunInputField]; `ISSUE_KEY` values become the run's issue key (ADR 0053). */
enum class RunInputType { TEXT, MULTILINE, ISSUE_KEY }

/**
 * A field of the run start form (ADR 0053). [name] is the key of the value in the run parameters; a workflow's form is
 * the union of the fields of its steps. ADR 0062: task steps contribute the parameters they bind `START` ([parameter]
 * is then that declaration, whose type the run start checks); action nodes their own run inputs.
 */
data class RunInputField(
    val name: String,
    val label: String,
    val type: RunInputType = RunInputType.TEXT,
    val required: Boolean = false,
    val help: String? = null,
    /** ADR 0062: the template parameter this field asks for; null for the run inputs of actions. */
    val parameter: TemplateParameter? = null,
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
 * ADR 0058: how the designer shows a [TemplateParameter] and which values it accepts. ADR 0062: `ISSUE_KEY` is a Jira
 * issue key; bound `START` it becomes the run's issue key.
 */
enum class TemplateParameterType { TEXT, MULTILINE, NUMBER, BOOLEAN, CHOICE, ISSUE_KEY }

/**
 * ADR 0058: a parameter of a template. Instructions and text config read it as `{{params.<name>}}`. Declarations are
 * inherited, child wins by name. ADR 0062: the step binds where its value comes from ([ParameterBinding]): a fixed value,
 * the run start form or an artefact. Structural rules ([name], [label], [help]) are checked here; [problems] lists the
 * type rules the template validator reports.
 */
data class TemplateParameter(
    val name: String,
    val label: String,
    val type: TemplateParameterType = TemplateParameterType.TEXT,
    val required: Boolean = false,
    /** String, number or boolean by [type]; null = no default. */
    val default: Any? = null,
    /** CHOICE only: the values the parameter accepts. */
    val options: List<String> = emptyList(),
    val help: String? = null,
) {
    init {
        if (!NAME_PATTERN.matches(name)) throw ValidationException("parameter name must match ${NAME_PATTERN.pattern}", "parameters.name")
        if (label.isBlank()) throw ValidationException("parameter $name: label must not be blank", "parameters.label")
        if (label.length > MAX_LABEL_LENGTH) throw ValidationException("parameter $name: label is longer than $MAX_LABEL_LENGTH characters", "parameters.label")
        if ((help?.length ?: 0) > MAX_HELP_LENGTH) throw ValidationException("parameter $name: help is longer than $MAX_HELP_LENGTH characters", "parameters.help")
    }

    /** Why the declaration is inconsistent: CHOICE without (distinct) options, options on another type, a default of the wrong type. */
    fun problems(): List<String> = buildList {
        if (type == TemplateParameterType.CHOICE) {
            if (options.isEmpty() || options.any { it.isBlank() }) add("parameter $name: a CHOICE needs non-blank options")
            if (options.distinct().size != options.size) add("parameter $name: options must be distinct")
        } else if (options.isNotEmpty()) {
            add("parameter $name: options are only for CHOICE parameters")
        }
        default?.let { valueProblem(it) }?.let { add("parameter $name: default $it") }
    }

    /** Why [value] does not fit the type (`"is not a number"`...); null when it does. Null values fit (no value). */
    fun valueProblem(value: Any?): String? = when {
        value == null -> null
        value is Map<*, *> || value is Collection<*> || value is Array<*> -> "must be a single value"
        type == TemplateParameterType.NUMBER && value !is Number && value.toString().trim().toBigDecimalOrNull() == null -> "\"$value\" is not a number"
        type == TemplateParameterType.BOOLEAN && value !is Boolean && value.toString().lowercase() !in BOOLEAN_TEXT -> "\"$value\" is not true or false"
        type == TemplateParameterType.CHOICE && value.toString() !in options -> "\"$value\" is not one of ${options.joinToString()}"
        type == TemplateParameterType.ISSUE_KEY && !ISSUE_KEY.matches(value.toString().trim()) -> "\"$value\" is not an issue key such as ABC-123"
        else -> null
    }

    /**
     * ADR 0062: this parameter as a field of the run start form (bound `START`): MULTILINE and ISSUE_KEY keep their type,
     * the other types are a single line whose value the run start checks against the declaration ([RunInputField.parameter]).
     */
    fun asRunInput(): RunInputField = RunInputField(
        name = name,
        label = label,
        type = when (type) {
            TemplateParameterType.MULTILINE -> RunInputType.MULTILINE
            TemplateParameterType.ISSUE_KEY -> RunInputType.ISSUE_KEY
            else -> RunInputType.TEXT
        },
        required = required,
        help = startHelp(),
        parameter = this,
    )

    /** The help of the start form field; a CHOICE names its options when the help leaves room. */
    private fun startHelp(): String? {
        if (type != TemplateParameterType.CHOICE || options.isEmpty()) return help
        val withOptions = listOfNotNull(help?.trim()?.takeIf { it.isNotEmpty() }, "One of: ${options.joinToString()}.").joinToString(" ")
        return if (withOptions.length <= RunInputField.MAX_HELP_LENGTH) withOptions else help
    }

    companion object {
        val NAME_PATTERN: Regex = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        const val MAX_LABEL_LENGTH = 200
        const val MAX_HELP_LENGTH = 1_000
        private val BOOLEAN_TEXT = setOf("true", "false")

        /** A Jira issue key: project key, dash, number (`ABC-123`); upper or lower case. */
        val ISSUE_KEY: Regex = Regex("^[A-Z][A-Z0-9_]{0,19}-[0-9]{1,10}$", RegexOption.IGNORE_CASE)
    }
}

/** ADR 0062: where a step takes the value of a parameter from. */
enum class ParameterBindingSource { VALUE, START, ARTIFACT }

/**
 * ADR 0062: how a step binds one parameter of its template: [ParameterBindingSource.VALUE] a fixed [value] (the former
 * step `parameters`), [ParameterBindingSource.START] the run start form (field = the parameter), or
 * [ParameterBindingSource.ARTIFACT] the content of the latest artefact named [artifact] of the run.
 */
data class ParameterBinding(
    val source: ParameterBindingSource,
    val value: Any? = null,
    val artifact: String? = null,
) {
    companion object {
        val START = ParameterBinding(ParameterBindingSource.START)

        fun value(value: Any?) = ParameterBinding(ParameterBindingSource.VALUE, value = value)

        fun artifact(name: String) = ParameterBinding(ParameterBindingSource.ARTIFACT, artifact = name)
    }
}

/** ADR 0058: where the value of a parameter comes from (ADR 0062: a `START` or `ARTIFACT` binding is the step's). */
enum class ParameterSource { STEP, TEMPLATE, DEFAULT, NONE }

/**
 * ADR 0058: a parameter with the value the step uses. ADR 0062: a step that binds it `START` or `ARTIFACT` has its
 * [binding]; its value is read when the step runs ([value] is then null) and [fallback] (the preset value, else the
 * default) applies when the run has none.
 */
data class ResolvedParameter(
    val definition: TemplateParameter,
    val value: Any?,
    val source: ParameterSource,
    val binding: ParameterBinding? = null,
    val fallback: Any? = null,
) {
    val name: String get() = definition.name

    /** ADR 0062: the value comes from the run (start form or artefact), not from the design. */
    val boundAtRun: Boolean get() = binding != null && binding.source != ParameterBindingSource.VALUE
}

/**
 * Node-level customisation of a task template inside a workflow. Applied last, after the template's
 * inheritance chain has been merged. ADR 0062: [instructionsReplace] replaces the template's instructions,
 * [instructionsAppend] adds notes after them; [parameterBindings] say where each parameter's value comes from.
 */
data class TaskOverrides(
    /** ADR 0061: the agent that runs this step; none = `opencode` when the work needs a coding agent, else the model answer. */
    val agentKey: String? = null,
    val modelProfileId: ModelProfileId? = null,
    val instructionsAppend: String? = null,
    val instructionsReplace: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    /** ADR 0062: per parameter name, where its value comes from; unbound = the preset value or the default. */
    val parameterBindings: Map<String, ParameterBinding> = emptyMap(),
) {
    companion object {
        val NONE = TaskOverrides()

        /**
         * ADR 0062: the step's notes from the current [instructionsAppend] and the legacy `systemPromptAppend` /
         * `promptAppend` texts, joined by a blank line in that order; null when all are blank.
         */
        fun joinedAppend(vararg texts: String?): String? =
            texts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n\n").ifEmpty { null }

        /**
         * ADR 0062: [bindings] with the legacy step `parameters` values as VALUE bindings for the names [bindings] does not
         * bind (blank values are no value and are dropped).
         */
        fun withLegacyValues(bindings: Map<String, ParameterBinding>, legacy: Map<String, Any?>?): Map<String, ParameterBinding> =
            legacy.orEmpty().filter { (name, value) -> name !in bindings && value != null && !(value is String && value.isBlank()) }
                .mapValues { (_, value) -> ParameterBinding.value(value) } + bindings
    }
}

/**
 * Fully merged view of a task: template + all ancestors (`extends`) + project-scoped shadowing +
 * node overrides. This is what executors consume; they never see templates directly.
 */
data class ResolvedTask(
    val key: String,
    val name: String,
    /** ADR 0061: the step's agent, else `opencode` when the work needs a coding agent, else the built-in model answer. */
    val agent: AgentSnapshot,
    /** ADR 0061: the step's model, else the agent's; null = the defaults (ModelResolver). */
    val modelProfileId: ModelProfileId?,
    /** ADR 0062: the merged instructions of the lineage with the step's replace and append applied; null = none. */
    val instructions: String?,
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
    /** ADR 0062: the run start form fields of this step: the parameters it binds `START`. */
    val runInputs: List<RunInputField> = emptyList(),
    /** ADR 0055/0057: rules always in the task's context; automatic ones (global, then project) first, then the agent's. */
    val rules: List<RuleSnapshot> = emptyList(),
    /** ADR 0058: the merged parameter declarations of the lineage with the value the step uses. */
    val parameters: List<ResolvedParameter> = emptyList(),
    /**
     * ADR 0063: globs of files the agent leaves that come back as artefacts (named like the file without extension),
     * unioned over the lineage (the former config key `collectArtifacts`).
     */
    val outputFiles: List<String> = emptyList(),
) {
    /**
     * ADR 0058: `params` of the template model: parameter name to the value the design sets (null = none). ADR 0062:
     * parameters bound `START` or `ARTIFACT` have their value only in a run (`StepParameters` in the Projects context).
     */
    val parameterValues: Map<String, Any?> get() = parameters.associate { it.name to it.value }
}

data class TaskSummary(
    val key: String,
    val name: String,
    val description: String,
    val category: TemplateCategory?,
    val abstract: Boolean,
    val projectId: UUID?,
    val ports: List<String>,
    /** ADR 0054: kept for the steps that use it, not offered for new ones. */
    val deprecated: Boolean = false,
    /** ADR 0058: merged parameter declarations of the lineage. */
    val parameters: List<TemplateParameter> = emptyList(),
    /** ADR 0058: values the template lineage sets (step values win). */
    val parameterValues: Map<String, Any?> = emptyMap(),
    /** ADR 0058: a required parameter has no value without the step (not a ready block). */
    val needsParameters: Boolean = false,
    /** ADR 0061: the merged config needs a coding agent (codingAgent, prepareOnly); else any agent. */
    val needsCodingAgent: Boolean = false,
    /** ADR 0061: skills the work needs, merged over the lineage. */
    val requiresSkills: List<String> = emptyList(),
)

/** ADR 0061: a skill the work needs; [name] is null when the library has no skill with the key. */
data class RequiredSkill(val key: String, val name: String?)

/**
 * ADR 0061: what the agent audit needs of one task step: the agent it runs with ([agentKey], the step's or the default
 * of decision 2), the skills its template lineage requires and whether the work needs a coding agent (ADR 0063: agents
 * never push).
 */
data class StepNeeds(
    val agentKey: String,
    val requiredSkills: List<RequiredSkill>,
    val needsCodingAgent: Boolean,
)

/** ADR 0061: a skill of an agent with its description (the coherence check reads names and descriptions). */
data class SkillInfo(val key: String, val name: String, val description: String)

/**
 * ADR 0061: an agent as the audit and the coherence check see it: its library identity ([id] null for the implicit
 * model answer without a library entry; [projectId] null for a global agent), the resolved rules (automatic ones first)
 * and skills, and the keys it names that the library no longer has.
 */
data class AgentProfile(
    val id: UUID?,
    val agent: AgentSnapshot,
    val projectId: UUID?,
    val builtIn: Boolean,
    val rules: List<RuleSnapshot>,
    val skills: List<SkillInfo>,
    val unknownRules: List<String> = emptyList(),
    val unknownSkills: List<String> = emptyList(),
)

/**
 * ADR 0059: an agent with its environment resolved as a step without overrides gets it: the rules (automatic ones
 * first, then the agent's) and the skills. Benchmarks give it to their candidates.
 */
data class AgentEnvironmentSnapshot(
    val agent: AgentSnapshot,
    val rules: List<RuleSnapshot> = emptyList(),
    val skills: List<SkillSnapshot> = emptyList(),
)

/** Outbound port implemented by the Task Library context; used by the Projects and Evaluation contexts. */
interface TaskCatalog {
    /**
     * Resolves a template key for a project (project-scoped templates shadow global ones) and applies
     * overrides. Returns null when the key is unknown. Throws `ValidationException` on cycles or when
     * the resolved task is abstract.
     */
    fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE): ResolvedTask?

    /** Concrete (non-abstract) templates visible to a project, for the designer palette. */
    fun palette(projectId: UUID?): List<TaskSummary>

    /** ADR 0059: the agent [key] a project sees (null = global agents only, as benchmarks use them); null when unknown. */
    fun agent(key: String, projectId: UUID?): AgentSnapshot? = null

    /**
     * ADR 0059: the agent [key] with its resolved environment (rules and skills; missing ones are left out); null when the
     * agent is unknown.
     */
    fun agentEnvironment(key: String, projectId: UUID?): AgentEnvironmentSnapshot? = null

    /**
     * ADR 0061: what the agent audit needs of a step with template [key] and [overrides]; null when the template is
     * unknown or its chain cannot be resolved.
     */
    fun stepNeeds(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE): StepNeeds? = null

    /** ADR 0061: the agent [key] a project sees with its environment; null when unknown. */
    fun agentProfile(key: String, projectId: UUID?): AgentProfile? = null

    /** ADR 0061: the agent with library id [id] (any scope); its environment as seen from [projectId] or its own scope. */
    fun agentProfileById(id: UUID, projectId: UUID?): AgentProfile? = null
}
