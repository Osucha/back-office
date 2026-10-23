package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import java.util.UUID

/**
 * ADR 0052 step 2: `START_WORKFLOW` starts another workflow of the same project. The run parameters come from the
 * artefact `parametersFrom` (a JSON object = one run, an array = one run per entry, e.g. an agent's proposals); with
 * `selection: DECISION` only the entries whose numbers (1-based) the owner wrote in the comment of the gate just
 * before are started ("all" starts every one). The step must follow a human gate directly: an agent proposes, a
 * person accepts, Back Office starts.
 */
class StartWorkflowTaskExecutor(
    private val startRun: () -> StartRun,
    private val workflows: WorkflowDefinitionRepository,
    private val json: JsonCodec,
) : TaskExecutorStrategy {

    override val executor = TaskExecutor.START_WORKFLOW

    @Suppress("UNCHECKED_CAST")
    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val previous = context.run.nodes.lastOrNull { it.status == NodeExecutionStatus.SUCCEEDED }
        if (previous?.kind != NodeKind.HUMAN_GATE) {
            throw InvalidStateException("${task.key} starts workflows only right after a human gate: connect it to a gate's option")
        }
        val target = resolveTarget(context, task.config["workflow"]?.toString()?.trim().orEmpty())
        val from = task.config["parametersFrom"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("set parametersFrom to the artefact with the run parameters", "parametersFrom")
        val artifact = context.run.artifactNamed(from) ?: throw InvalidStateException("artefact $from is missing")
        val entries = when (val parsed = runCatching { json.parseObject("{\"value\":" + artifact.content.orEmpty() + "}")["value"] }.getOrNull()) {
            is Map<*, *> -> listOf(parsed as Map<String, Any?>)
            is List<*> -> parsed.map { it as? Map<String, Any?> ?: throw ValidationException("every entry of $from must be a JSON object", "parametersFrom") }
            else -> throw ValidationException("artefact $from must be a JSON object or an array of objects", "parametersFrom")
        }
        val picked = when (task.config["selection"]?.toString()?.uppercase() ?: "ALL") {
            "DECISION" -> select(entries, decisionComment(context))
            else -> entries.withIndex().toList()
        }
        if (picked.isEmpty()) return NodeResult(port = WorkflowNode.PORT_SUCCESS, summary = "nothing selected, no workflow started")
        val started = picked.map { (index, entry) ->
            val parameters = entry.filterValues { it is String || it is Number || it is Boolean } +
                mapOf("startedByRun" to context.run.id.toString(), "proposal" to (index + 1))
            val run = startRun().start(target.id, RunInput(null, parameters), TriggerSource.API)
            context.log("started ${target.name} as run ${run.id} for entry ${index + 1}" + (entry["title"]?.let { ": $it" } ?: ""))
            mapOf("runId" to run.id.toString(), "entry" to index + 1, "title" to entry["title"])
        }
        return NodeResult(
            port = WorkflowNode.PORT_SUCCESS,
            summary = "started ${started.size} run(s) of ${target.name}: " + started.joinToString { "#${it["entry"]} ${it["title"] ?: ""}".trim() },
            artifacts = listOf(NewArtifact(STARTED_RUNS, ArtifactKind.JSON, "application/json", json.toJson(started))),
            stateUpdates = mapOf(RunStateKeys.STARTED_RUNS to started),
        )
    }

    /** The target by id or by name, in the run's project and not the running workflow itself. */
    private fun resolveTarget(context: NodeContext, ref: String) = run {
        if (ref.isEmpty()) throw ValidationException("set workflow to the id or name of the workflow to start", "workflow")
        val byId = runCatching { WorkflowDefinitionId(UUID.fromString(ref)) }.getOrNull()?.let(workflows::findById)
        val found = byId ?: workflows.findByProjectId(context.project.id).firstOrNull { it.name.equals(ref, ignoreCase = true) }
        val target = found?.takeIf { it.projectId == context.project.id }
            ?: throw ValidationException("no workflow \"$ref\" in project ${context.project.key}", "workflow")
        if (target.id == context.run.workflowId) throw ValidationException("a workflow cannot start itself", "workflow")
        target
    }

    @Suppress("UNCHECKED_CAST")
    private fun decisionComment(context: NodeContext): String =
        (context.state[RunStateKeys.LAST_DECISION] as? Map<String, Any?>)?.get("comment")?.toString().orEmpty()

    companion object {
        const val STARTED_RUNS = "started-runs"
        private val ALL = Regex("\\b(all|wszystkie|wszystko)\\b", RegexOption.IGNORE_CASE)
        private val NUMBER = Regex("\\b\\d{1,3}\\b")

        /** Entries named by number in [comment] (1-based, in their order), every entry for "all"; none without numbers. */
        fun <T> select(entries: List<T>, comment: String): List<IndexedValue<T>> {
            if (ALL.containsMatchIn(comment)) return entries.withIndex().toList()
            return NUMBER.findAll(comment).map { it.value.toInt() - 1 }.filter { it in entries.indices }.distinct()
                .map { IndexedValue(it, entries[it]) }.toList()
        }
    }
}
