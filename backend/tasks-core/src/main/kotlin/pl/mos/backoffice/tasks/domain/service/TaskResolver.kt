package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.SkillSnapshot
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/** Result of rule 5: every interface in the lineage satisfied, or the list of what is missing. */
data class InterfaceCheck(val ok: Boolean, val problems: List<String>) {
    companion object {
        val OK = InterfaceCheck(true, emptyList())
        fun of(problems: List<String>) = InterfaceCheck(problems.isEmpty(), problems)
    }
}

/**
 * The merged view of a template chain before the "is it executable" decision: what the UI previews and
 * what [toResolvedTask] turns into the executor contract. `executor` is null only for chains without
 * one (an abstract root such as `base-agent`).
 */
data class TaskResolution(
    val key: String,
    val name: String,
    val description: String,
    val executor: TaskExecutor?,
    val modelProfileId: ModelProfileId?,
    val prompt: TaskPrompt,
    val skills: List<SkillSnapshot>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val interfaces: List<String>,
    val lineage: List<String>,
    val ports: List<String>,
    val tags: List<String>,
    val abstract: Boolean,
    val projectId: UUID?,
    val interfaceCheck: InterfaceCheck,
    /** Version of the leaf template. */
    val version: Int? = null,
    /** Run inputs merged root to leaf, child-wins by name (ADR 0053). */
    val runInputs: List<RunInputField> = emptyList(),
) {
    /** True when an executor can run this: concrete, has an executor, all interfaces satisfied. */
    val executable: Boolean get() = !abstract && executor != null && interfaceCheck.ok

    /** Throws `ValidationException` unless [executable]. */
    fun toResolvedTask(): ResolvedTask {
        val errors = buildList {
            if (abstract) add(ValidationException.FieldError("abstract", "template $key is abstract and cannot be executed"))
            if (executor == null) add(ValidationException.FieldError("executor", "template $key has no executor in its lineage"))
            interfaceCheck.problems.forEach { add(ValidationException.FieldError("interfaceCheck", it)) }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return ResolvedTask(
            key = key,
            name = name,
            executor = executor!!,
            modelProfileId = modelProfileId,
            prompt = prompt,
            skills = skills,
            config = config,
            outputSchema = outputSchema,
            confidenceField = confidenceField,
            produces = produces,
            consumes = consumes,
            interfaces = interfaces,
            lineage = lineage,
            ports = ports,
            version = version,
            runInputs = runInputs,
        )
    }
}

/**
 * Pure implementation of the resolution rules in docs/architecture/07-task-library.md:
 *
 * 1. project shadowing for the leaf and every ancestor,
 * 2. `extends` chain of at most [MAX_DEPTH] templates, cycles rejected,
 * 3. root-to-leaf merge (executor nearest wins; scalars child-wins; prompts append/replace; skills
 *    union minus `removeSkills`; config deep-merged with `null` deleting; list fields unioned; run inputs
 *    unioned by name, child field wins),
 * 4. node overrides,
 * 5. interface check (outputs in `produces`, required inputs in `consumes`, required `INPUT` slots in
 *    `runInputs`) and port union,
 * 6. abstract leaves preview but do not execute.
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

        val problems = mutableListOf<String>()
        val ports = TaskInterface.DEFAULT_PORTS.toMutableList()
        merged.interfaces.forEach { interfaceKey ->
            val definition = lookup.taskInterface(interfaceKey)
            if (definition == null) {
                problems += "interface $interfaceKey does not exist"
                return@forEach
            }
            definition.outputs.map { it.name }.distinct().filter { it !in merged.produces }.forEach {
                problems += "interface $interfaceKey: output $it is not in produces"
            }
            definition.requiredConsumes.filter { it !in merged.consumes }.forEach {
                problems += "interface $interfaceKey: required input $it is not in consumes"
            }
            val declared = merged.runInputs.map { it.name }
            definition.requiredRunInputs.filter { it !in declared }.forEach {
                problems += "interface $interfaceKey: required run input $it is not in runInputs"
            }
            definition.ports.forEach { if (it !in ports) ports += it }
        }
        val skills = merged.skills.mapNotNull { skillKey ->
            val skill = lookup.visibleSkill(skillKey, projectId)
            if (skill == null) problems += "skill $skillKey does not exist"
            skill?.let { SkillSnapshot(it.key, it.name, it.content) }
        }

        return TaskResolution(
            version = leaf.version,
            key = leaf.key,
            name = leaf.name,
            description = leaf.description,
            executor = merged.executor,
            modelProfileId = merged.modelProfileId,
            prompt = TaskPrompt(merged.system, merged.user),
            skills = skills,
            config = merged.config,
            outputSchema = merged.outputSchema,
            confidenceField = merged.confidenceField,
            produces = merged.produces,
            consumes = merged.consumes,
            interfaces = merged.interfaces,
            lineage = chain.map { it.key },
            ports = ports,
            tags = merged.tags,
            abstract = leaf.abstract,
            projectId = projectId,
            interfaceCheck = InterfaceCheck.of(problems),
            runInputs = merged.runInputs,
        )
    }

    /** Accumulator of the root-to-leaf merge. */
    private data class Merged(
        val executor: TaskExecutor? = null,
        val modelProfileId: ModelProfileId? = null,
        val system: String? = null,
        val user: String? = null,
        val skills: List<String> = emptyList(),
        val config: Map<String, Any?> = emptyMap(),
        val outputSchema: String? = null,
        val confidenceField: String? = null,
        val produces: List<String> = emptyList(),
        val consumes: List<String> = emptyList(),
        val interfaces: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val runInputs: List<RunInputField> = emptyList(),
    ) {
        fun merge(t: TaskTemplate): Merged = Merged(
            executor = t.executor ?: executor,
            modelProfileId = t.modelProfileId ?: modelProfileId,
            system = combine(system, t.prompt.system, t.prompt.mode),
            user = combine(user, t.prompt.user, t.prompt.mode),
            skills = (skills + t.skills).distinct().filter { it !in t.removeSkills },
            config = ConfigMerge.deepMerge(config, t.config),
            outputSchema = t.outputSchema ?: outputSchema,
            confidenceField = t.confidenceField ?: confidenceField,
            produces = (produces + t.produces).distinct(),
            consumes = (consumes + t.consumes).distinct(),
            interfaces = (interfaces + t.implements).distinct(),
            tags = (tags + t.tags).distinct(),
            runInputs = mergeRunInputs(runInputs, t.runInputs),
        )

        fun applyOverrides(o: TaskOverrides): Merged = copy(
            modelProfileId = o.modelProfileId ?: modelProfileId,
            system = combine(system, o.systemPromptAppend, PromptMode.APPEND),
            user = combine(combine(user, o.promptReplace, PromptMode.REPLACE), o.promptAppend, PromptMode.APPEND),
            skills = (skills + o.addSkills).distinct().filter { it !in o.removeSkills },
            config = ConfigMerge.deepMerge(config, o.config),
        )

        /** Union by name: a child field replaces the parent's field of the same name in its place; new fields go last. */
        private fun mergeRunInputs(parent: List<RunInputField>, child: List<RunInputField>): List<RunInputField> {
            val byName = child.associateBy { it.name }
            return parent.map { byName[it.name] ?: it } + child.filter { field -> parent.none { it.name == field.name } }
        }

        private fun combine(parent: String?, child: String?, mode: PromptMode): String? = when {
            child == null -> parent
            mode == PromptMode.REPLACE || parent.isNullOrBlank() -> child
            else -> parent.trimEnd() + "\n\n" + child.trimStart()
        }
    }

    companion object {
        const val MAX_DEPTH = 8
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
