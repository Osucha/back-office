package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.SkillSnapshot
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * Result of rule 5: every skill, rule and agent the step names exists, the agent can do the work (ADR 0056) and the
 * parameter values fit (ADR 0058), or the list of problems.
 */
data class ResolutionCheck(val ok: Boolean, val problems: List<String>) {
    companion object {
        val OK = ResolutionCheck(true, emptyList())
        fun of(problems: List<String>) = ResolutionCheck(problems.isEmpty(), problems)
    }
}

/**
 * The merged view of a template chain before the "is it executable" decision: what the UI previews and
 * what [toResolvedTask] turns into the executor contract. ADR 0056: [agent] is the step's agent, else the nearest in
 * the lineage, else the built-in model answer.
 */
data class TaskResolution(
    val key: String,
    val name: String,
    val description: String,
    val agent: AgentSnapshot,
    val modelProfileId: ModelProfileId?,
    val prompt: TaskPrompt,
    val skills: List<SkillSnapshot>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val category: TemplateCategory?,
    val lineage: List<String>,
    val ports: List<String>,
    val tags: List<String>,
    val abstract: Boolean,
    val projectId: UUID?,
    val check: ResolutionCheck,
    /** Version of the leaf template. */
    val version: Int? = null,
    /** Run inputs merged root to leaf, child-wins by name (ADR 0053). */
    val runInputs: List<RunInputField> = emptyList(),
    /** ADR 0055/0057: automatic rules (global, then project) first, then the agent's. */
    val rules: List<RuleSnapshot> = emptyList(),
    /** ADR 0058: parameter declarations of the lineage with the value the step uses. */
    val parameters: List<ResolvedParameter> = emptyList(),
) {
    /** True when a step can run this: concrete, every skill, rule and the agent found, the agent fits the work. */
    val executable: Boolean get() = !abstract && check.ok

    /** ADR 0058: values the template lineage sets (without the step's). */
    val templateParameterValues: Map<String, Any?>
        get() = parameters.filter { it.source == ParameterSource.TEMPLATE }.associate { it.name to it.value }

    /** ADR 0058: not a ready block: a required parameter has no value without the step. */
    val needsParameters: Boolean
        get() = parameters.any { it.definition.required && it.source != ParameterSource.TEMPLATE && it.source != ParameterSource.DEFAULT }

    /** Throws `ValidationException` unless [executable]. */
    fun toResolvedTask(): ResolvedTask {
        val errors = buildList {
            if (abstract) add(ValidationException.FieldError("abstract", "template $key is abstract and cannot be executed"))
            check.problems.forEach { add(ValidationException.FieldError("check", it)) }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return ResolvedTask(
            key = key,
            name = name,
            agent = agent,
            modelProfileId = modelProfileId,
            prompt = prompt,
            skills = skills,
            config = config,
            outputSchema = outputSchema,
            confidenceField = confidenceField,
            produces = produces,
            consumes = consumes,
            category = category,
            lineage = lineage,
            ports = ports,
            version = version,
            runInputs = runInputs,
            rules = rules,
            parameters = parameters,
        )
    }
}

/**
 * Pure implementation of the resolution rules in docs/architecture/07-task-library.md:
 *
 * 1. project shadowing for the leaf and every ancestor,
 * 2. `extends` chain of at most [MAX_DEPTH] templates, cycles rejected,
 * 3. root-to-leaf merge (agent nearest wins, ADR 0056; scalars child-wins; prompts append/replace; config deep-merged
 *    with `null` deleting; list fields unioned; run inputs and parameters unioned by name, child wins; parameter
 *    values nearest wins),
 * 4. node overrides (the step's agent and model win; model: step, else agent, else lineage),
 * 5. ADR 0057: the agent's environment: skills are the agent's plus the step's `addSkills` minus its `removeSkills`;
 *    rules are the automatic ones (global, then the project's) and then the agent's (ADR 0055: a skill key that names a
 *    rule, as skills turned rules did, counts as that rule); every one must exist,
 * 6. the agent looked up and checked against the work (Spec Kit phase, push or prepareOnly need a coding agent),
 * 7. ADR 0058: parameter values: the step's, else the nearest template's, else the default; unknown names, values of
 *    the wrong type and required parameters without a value are problems,
 * 8. abstract leaves preview but do not execute.
 *
 * A legacy config key `agent` (`CLAUDE_CODE`, `OPENCODE`) on a template or a step reads as its agent key.
 */
class TaskResolver(private val lookup: LibraryLookup) {

    /** Merged view for previews; null when [key] is unknown to the project. Throws only on cycles, missing parents and depth. */
    fun preview(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE): TaskResolution? {
        val leaf = lookup.visibleTemplate(key, projectId) ?: return null
        return resolveChain(chainOf(leaf, projectId), projectId, overrides)
    }

    /** Executor contract; null when unknown, `ValidationException` when the merged task is not executable. */
    fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE): ResolvedTask? =
        preview(key, projectId, overrides)?.toResolvedTask()

    /** Leaf first, root last. */
    fun chainOf(leaf: TaskTemplate, projectId: UUID?): List<TaskTemplate> {
        val chain = mutableListOf(leaf)
        var current = leaf
        while (true) {
            val parentKey = current.extendsKey ?: break
            // A project-scoped template that shadows a global one may extend it by its own key:
            // "specialise the built-in without copying it". The parent is then the GLOBAL template.
            val shadowsGlobal = current.scope is Scope.Project && parentKey == current.key
            if (!shadowsGlobal && chain.any { it.key == parentKey }) {
                throw ValidationException("inheritance cycle: ${(chain.map { it.key } + parentKey).joinToString(" -> ")}", "extendsKey")
            }
            if (chain.size >= MAX_DEPTH) {
                throw ValidationException("inheritance chain of ${leaf.key} exceeds $MAX_DEPTH templates", "extendsKey")
            }
            val parent = (if (shadowsGlobal) lookup.template(parentKey, Scope.Global) else lookup.visibleTemplate(parentKey, projectId))
                ?: throw ValidationException("template ${current.key} extends unknown template $parentKey", "extendsKey")
            chain += parent
            current = parent
        }
        return chain
    }

    private fun resolveChain(chain: List<TaskTemplate>, projectId: UUID?, overrides: TaskOverrides): TaskResolution {
        val leaf = chain.first()
        val merged = chain.asReversed().fold(Merged()) { acc, template -> acc.merge(template) }
            .applyOverrides(overrides)
        val agentKey = merged.agentKey ?: AgentSnapshot.MODEL_ANSWER

        val environment = environment(agentKey, projectId, overrides.addSkills, overrides.removeSkills)
        val agent = environment.agent
        val skills = environment.skills
        val rules = environment.rules
        val problems = environment.problems.toMutableList()
        problems += compatibilityProblems(agent, merged.config)
        val parameters = Parameters.resolve(merged.parameters, merged.parameterValues, overrides.parameters, leaf.key)
        problems += parameters.second

        return TaskResolution(
            version = leaf.version,
            key = leaf.key,
            name = leaf.name,
            description = leaf.description,
            agent = agent,
            modelProfileId = overrides.modelProfileId ?: agent.modelProfileId ?: merged.modelProfileId,
            prompt = TaskPrompt(merged.system, merged.user),
            skills = skills,
            config = merged.config,
            outputSchema = merged.outputSchema,
            confidenceField = merged.confidenceField,
            produces = merged.produces,
            consumes = merged.consumes,
            category = merged.category,
            lineage = chain.map { it.key },
            ports = DEFAULT_PORTS,
            tags = merged.tags,
            abstract = leaf.abstract,
            projectId = projectId,
            check = ResolutionCheck.of(problems),
            runInputs = merged.runInputs,
            rules = rules,
            parameters = parameters.first,
        )
    }

    /**
     * Rule 5 (ADR 0057): the environment of agent [agentKey] for [projectId]: its skills plus [addSkills] minus
     * [removeSkills] (a skill key that names a rule counts as that rule), the automatic rules (global, then the project's)
     * and then the agent's. An unknown agent (other than the built-in model answer) and missing skills or rules are
     * [AgentEnvironmentResolution.problems]; [AgentEnvironmentResolution.found] says whether the agent exists.
     * ADR 0059: benchmarks use it for their candidates.
     */
    fun environment(
        agentKey: String,
        projectId: UUID?,
        addSkills: List<String> = emptyList(),
        removeSkills: List<String> = emptyList(),
    ): AgentEnvironmentResolution {
        val problems = mutableListOf<String>()
        val stored = lookup.visibleAgent(agentKey, projectId)?.snapshot
        val agent = stored ?: when (agentKey) {
            AgentSnapshot.MODEL_ANSWER -> AgentSnapshot.DEFAULT
            else -> { problems += "agent $agentKey does not exist"; AgentSnapshot(agentKey, agentKey) }
        }
        val listedRules = agent.rules.toMutableList()
        val skillKeys = (agent.skills + addSkills).distinct().filter { it !in removeSkills }
        val skills = skillKeys.mapNotNull { skillKey ->
            val skill = lookup.visibleSkill(skillKey, projectId)
            when {
                skill != null -> SkillSnapshot(skill.key, skill.name, skill.content)
                lookup.visibleRule(skillKey, projectId) != null -> { if (skillKey !in listedRules) listedRules += skillKey; null }
                else -> { problems += "skill $skillKey does not exist"; null }
            }
        }
        val automatic = lookup.visibleAutomaticRules(projectId)
        val rules = automatic.map { RuleSnapshot(it.key, it.name, it.content, automatic = true) } +
            listedRules.filter { key -> automatic.none { it.key == key } }.mapNotNull { ruleKey ->
                val rule = lookup.visibleRule(ruleKey, projectId)
                if (rule == null) problems += "rule $ruleKey of agent ${agent.key} does not exist"
                rule?.let { RuleSnapshot(it.key, it.name, it.content, automatic = false) }
            }
        return AgentEnvironmentResolution(agent, skills, rules, problems, found = stored != null || agentKey == AgentSnapshot.MODEL_ANSWER)
    }

    /** What [environment] resolved; [problems] are resolution problems of a step that uses the agent. */
    data class AgentEnvironmentResolution(
        val agent: AgentSnapshot,
        val skills: List<SkillSnapshot>,
        val rules: List<RuleSnapshot>,
        val problems: List<String>,
        val found: Boolean,
    )

    /** ADR 0056 decision 4: work the agent cannot do (ADR 0057 lifted the output schema restriction). */
    private fun compatibilityProblems(agent: AgentSnapshot, config: Map<String, Any?>): List<String> = buildList {
        val needs = codingAgentReason(config)
        if (agent.modelAnswer && needs != null) add("$needs needs a coding agent; agent ${agent.key} gives a model answer")
    }

    /** Accumulator of the root-to-leaf merge; [modelProfileId] is the lineage's (the step's comes after the agent's). */
    private data class Merged(
        val agentKey: String? = null,
        val modelProfileId: ModelProfileId? = null,
        val system: String? = null,
        val user: String? = null,
        val config: Map<String, Any?> = emptyMap(),
        val outputSchema: String? = null,
        val confidenceField: String? = null,
        val produces: List<String> = emptyList(),
        val consumes: List<String> = emptyList(),
        val category: TemplateCategory? = null,
        val tags: List<String> = emptyList(),
        val runInputs: List<RunInputField> = emptyList(),
        val parameters: List<TemplateParameter> = emptyList(),
        /** Parameter values of the lineage: name to (value, template key that set it); the nearest template wins. */
        val parameterValues: Map<String, Pair<Any?, String>> = emptyMap(),
    ) {
        fun merge(t: TaskTemplate): Merged = Merged(
            agentKey = t.agentKey ?: legacyAgentKey(t.config) ?: agentKey,
            modelProfileId = t.modelProfileId ?: modelProfileId,
            system = combine(system, t.prompt.system, t.prompt.mode),
            user = combine(user, t.prompt.user, t.prompt.mode),
            config = ConfigMerge.deepMerge(config, withoutLegacyAgent(t.config)),
            outputSchema = t.outputSchema ?: outputSchema,
            confidenceField = t.confidenceField ?: confidenceField,
            produces = (produces + t.produces).distinct(),
            consumes = (consumes + t.consumes).distinct(),
            category = t.category ?: category,
            tags = (tags + t.tags).distinct(),
            runInputs = byName(runInputs, t.runInputs) { it.name },
            parameters = byName(parameters, t.parameters) { it.name },
            parameterValues = parameterValues + t.parameterValues.filterValues(Parameters::present).mapValues { (_, v) -> v to t.key },
        )

        fun applyOverrides(o: TaskOverrides): Merged = copy(
            agentKey = o.agentKey?.takeIf { it.isNotBlank() } ?: legacyAgentKey(o.config) ?: agentKey,
            system = combine(system, o.systemPromptAppend, PromptMode.APPEND),
            user = combine(combine(user, o.promptReplace, PromptMode.REPLACE), o.promptAppend, PromptMode.APPEND),
            config = ConfigMerge.deepMerge(config, withoutLegacyAgent(o.config)),
        )

        /** Union by name: a child entry replaces the parent's entry of the same name in its place; new entries go last. */
        private fun <T> byName(parent: List<T>, child: List<T>, name: (T) -> String): List<T> {
            val byName = child.associateBy(name)
            return parent.map { byName[name(it)] ?: it } + child.filter { entry -> parent.none { name(it) == name(entry) } }
        }

        private fun combine(parent: String?, child: String?, mode: PromptMode): String? = when {
            child == null -> parent
            mode == PromptMode.REPLACE || parent.isNullOrBlank() -> child
            else -> parent.trimEnd() + "\n\n" + child.trimStart()
        }
    }

    /** ADR 0058: the value of every declared parameter and the problems of the values. */
    internal object Parameters {
        /** A value counts when it is not null and not a blank string (an empty field in the designer is no value). */
        fun present(value: Any?): Boolean = value != null && !(value is String && value.isBlank())

        fun resolve(
            declared: List<TemplateParameter>,
            lineageValues: Map<String, Pair<Any?, String>>,
            stepValues: Map<String, Any?>,
            leafKey: String,
        ): Pair<List<ResolvedParameter>, List<String>> {
            val problems = mutableListOf<String>()
            val names = declared.map { it.name }.toSet()
            lineageValues.filterKeys { it !in names }.forEach { (name, set) ->
                problems += "template ${set.second} sets parameter $name, which no template of the lineage declares"
            }
            stepValues.filter { (name, value) -> name !in names && present(value) }.keys.forEach { name ->
                problems += "the step sets parameter $name, which template $leafKey does not declare"
            }
            val resolved = declared.map { p ->
                val step = stepValues[p.name]?.takeIf(::present)
                val template = lineageValues[p.name]?.first
                val (value, source) = when {
                    step != null -> step to ParameterSource.STEP
                    template != null -> template to ParameterSource.TEMPLATE
                    present(p.default) -> p.default to ParameterSource.DEFAULT
                    else -> null to ParameterSource.NONE
                }
                p.valueProblem(value)?.let { problems += "parameter ${p.name}: $it" }
                if (p.required && value == null) problems += "parameter ${p.name} (${p.label}) needs a value"
                ResolvedParameter(p, value, source)
            }
            return resolved to problems
        }
    }

    companion object {
        const val MAX_DEPTH = 8

        /** The former config key that chose the coding agent harness (ADR 0056 reads it as the agent key). */
        const val LEGACY_AGENT_CONFIG_KEY = "agent"

        /** `CLAUDE_CODE` / `OPENCODE` under the legacy config key `agent` as the built-in agent key; null otherwise. */
        fun legacyAgentKey(config: Map<String, Any?>): String? {
            val value = config[LEGACY_AGENT_CONFIG_KEY] as? String ?: return null
            return AgentHarness.entries.firstOrNull { it.name == value }?.let { AgentSnapshot.keyOf(it) }
        }

        /** [config] without a legacy `agent` key that names a harness. */
        fun withoutLegacyAgent(config: Map<String, Any?>): Map<String, Any?> =
            if (legacyAgentKey(config) != null) config - LEGACY_AGENT_CONFIG_KEY else config

        /**
         * Why a task's merged [config] needs a coding agent (ADR 0056): a Spec Kit `phase`, `push: true` or
         * `prepareOnly: true`; null when a model answer can do it.
         */
        fun codingAgentReason(config: Map<String, Any?>): String? = when {
            (config["phase"] as? String)?.isNotBlank() == true -> "a Spec Kit phase"
            config["push"] == true -> "push"
            config["prepareOnly"] == true -> "prepareOnly"
            else -> null
        }

        /** Output ports of every task node. */
        val DEFAULT_PORTS: List<String> = listOf("success", "failure")
    }
}

/** Deep merge of config maps: child keys win, nested maps recurse, `null` values delete the key. */
object ConfigMerge {
    fun deepMerge(base: Map<String, Any?>, patch: Map<String, Any?>): Map<String, Any?> {
        if (patch.isEmpty()) return base
        val result = LinkedHashMap(base)
        for ((key, value) in patch) {
            val existing = result[key]
            when {
                value == null -> result.remove(key)
                value is Map<*, *> && existing is Map<*, *> -> result[key] = deepMerge(existing.asStringMap(), value.asStringMap())
                value is Map<*, *> -> result[key] = deepMerge(emptyMap(), value.asStringMap())
                else -> result[key] = value
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.asStringMap(): Map<String, Any?> = this as Map<String, Any?>
}
